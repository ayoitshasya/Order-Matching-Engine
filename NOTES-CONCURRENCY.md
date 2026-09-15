# Concurrency Notes

Races and bugs found (and fixed) while building the multithreaded `MatchingEngine`
(Phase 4), and why each fix is the right one rather than the simplest one available.

## 1. `OrderBook.listeners` was a plain `ArrayList`

`OrderBook` was written under the assumption that only its single writer thread ever
touches it (true for order and cancel traffic) — but `MatchingEngine.addListener` needs to
register a listener on a symbol's book from whatever thread the caller happens to be on,
which is never the writer thread. That call mutates `listeners` (`ArrayList.add`)
concurrently with the writer thread iterating the very same list inside `notifyTrade` /
`notifyStatusChangeIfChanged` mid-match. `ArrayList` gives no safety guarantee for that: at
best a torn read, at worst a `ConcurrentModificationException` thrown from inside matching
logic that has nothing to do with the listener itself.

**Fix:** `listeners` is a `CopyOnWriteArrayList`. Registration is rare (setup time, or a
new symbol's first listener); iteration (once per trade, once per status change) is
frequent. That is exactly the access pattern `CopyOnWriteArrayList` is for: readers never
block and never see a half-updated list, at the cost of copying the backing array on
write — a cost paid only when a listener is added, never on the matching path.

**Follow-on fix:** with registration now safe from any thread, `MatchingEngine.addListener`
also has to register the same listener on every worker that already exists *and* have new
workers pick it up automatically going forward (see #2 below). A worker created concurrently
with `addListener`'s loop over existing workers could receive the same listener twice — once
via its constructor (which starts from a fresh snapshot of `MatchingEngine.listeners` that
already includes the new one) and once via the loop finding that worker already in the map.
`OrderBook.addListener` was made idempotent (`if (!listeners.contains(listener)) ...`) so a
caller never has to reason about this ordering to avoid double delivery.

## 2. A worker created after `shutdown()` had already finished

The naive version of `MatchingEngine.submit` checked a `volatile boolean shuttingDown`
flag, then — if false — looked up or created the symbol's `SymbolWorker` via
`ConcurrentHashMap.computeIfAbsent` and queued the command. `shutdown()` set the flag, then
iterated the existing workers to close and join each one.

The race: a submitting thread reads `shuttingDown == false` right before `shutdown()` sets
it. `shutdown()` then runs to completion — flips the flag, shuts down and joins every
worker currently in the map. Only *after* that does the submitting thread reach
`computeIfAbsent`, creating a brand new worker (and its writer thread) for a symbol
`shutdown()` never saw. That worker is never joined, its command sits in a queue nobody
will ever drain, and the caller's future never completes — a silent hang, and a thread the
engine can no longer stop.

**Fix:** a `ReentrantReadWriteLock` around the two operations. `submit` takes the read
lock for its entire body (check the flag, create-or-look-up the worker, offer the task);
`shutdown` takes the write lock to flip the flag. Many submissions run concurrently (shared
read lock), but none can be in progress while a shutdown is (exclusive write lock), and a
shutdown cannot start until every in-flight submission has finished creating whatever
worker it was going to create. By the time `shutdown` acquires the write lock, `workers`
is guaranteed to contain every worker that will ever exist; by the time any later `submit`
acquires the read lock, `shuttingDown` is guaranteed visible as `true`.

## 3. A task queued after the poison pill would never run — or fail

`SymbolWorker`'s writer thread loop takes from a queue until it sees a sentinel ("poison
pill") value, then exits. If a task could be added to the queue *after* the poison pill —
by a call to `offer` racing with `shutdown`'s call to add the pill — that task would sit
behind the pill forever: the writer thread has already stopped taking from the queue by the
time it would be reached. Its future never completes, successfully or otherwise. This is
strictly worse than the engine-level race in #2: there, the caller at least gets an
`EngineShutdownException`, quickly. Here, the caller gets nothing, ever.

**Fix:** `offer` and `shutdown` are both `synchronized` on the worker instance, guarded by
a `closed` flag. `shutdown` sets `closed = true` and enqueues the pill atomically with
respect to `offer`; `offer` checks `closed` and rejects (returning `false`) rather than
enqueueing, atomically with respect to `shutdown`. Between the two locks (#2 protects
*which* workers exist and receive commands at all; this one protects each worker's own
queue), no command can ever be silently stranded.

In practice `MatchingEngine.submit` never sees `offer` return `false` — the read/write lock
from #2 already guarantees a worker cannot be closed while a submission holds the read
lock — so it does not branch on the return value. `SymbolWorkerTest` exercises `offer`
returning `false` directly, at the unit level, since `MatchingEngine`'s own public API can
never reach that path by construction.

## 4. A command that throws must not take its writer thread down with it

`SymbolWorker.Task.runOn` wraps the call to the book in `try { ... } catch (RuntimeException e)
{ future.completeExceptionally(e); }`. Without this, an `InvalidOrderException` (a
duplicate order ID, say) or `OrderNotFoundException` (cancelling an order that isn't
there) thrown out of `OrderBook.submit` / `cancel` / `cancelStop` would propagate out of the
writer thread's `run()` loop entirely, silently killing that thread — and with it, all
further matching for that symbol, with no exception visible anywhere except a thread death
nobody was watching for. `MatchingEngineTest` covers this directly: a command that throws
completes its own future exceptionally, and a subsequent, valid command on the same symbol
still succeeds, proving the thread survived.

## 5. A slow listener must not stall the writer thread

`OrderBook` calls its listeners synchronously and inline with matching — deliberately, so a
listener sees events in the exact order they occurred without needing its own queue. That
means a listener that runs slowly (a blocking network call in a market-data feed, for
instance) would stall the writer thread for as long as it runs, backing up that entire
symbol's command queue behind it — a much larger blast radius than the one slow listener.

**Fix:** `MatchingEngine` never registers a raw listener with an `OrderBook`. Every listener
is wrapped in an `AsyncTradeListener`, backed by its own dedicated single-thread executor.
The writer thread's call to `onTrade` / `onOrderStatusChanged` only has to enqueue a task on
that executor and return; the actual listener code runs on the executor's thread, whenever
it gets there. Because the executor is a single thread draining a FIFO queue, one symbol's
events still arrive at the listener in the order its writer thread produced them. A listener
registered across several symbols may see those symbols' events interleaved with each other,
but nothing ever ordered that interleaving to begin with, so nothing is lost by not
preserving it.

`MatchingEngineListenerIsolationTest` proves this with a listener that sleeps 200ms per
trade: submitting several crossing orders in a row completes in a small fraction of
`tradeCount * 200ms`, which it could not if the writer thread had to wait for the listener
after each one.

## Residual, intentionally uncovered branches

A few defensive branches are structurally unreachable through the public API (and are
covered instead by direct, same-package unit tests where that is possible — see
`SymbolWorkerTest`, `AsyncTradeListenerTest`) or depend on genuinely interrupting a specific
internal thread from outside, which nothing in this codebase does and which a test would
have to fabricate via reflection to exercise:

- The `InterruptedException` catch in `SymbolWorker.run()`'s `queue.take()` — nothing ever
  interrupts a writer thread.
- The `InterruptedException` catch in `MatchingEngine.shutdown()`'s `worker.awaitTermination()`
  and in `AsyncTradeListener.close()`'s `awaitTermination` — nothing ever interrupts the
  thread calling `shutdown()` either.

These are left in place as correct, conventional handling for an interruption that this
codebase never triggers itself, rather than removed to chase 100% coverage.
