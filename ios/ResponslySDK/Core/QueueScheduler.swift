import Foundation

/// Hops scheduled blocks onto the core queue and makes cancellation reliable across the hop.
final class QueueScheduler: Scheduler {
    private final class Token: Cancellable {
        var isCancelled = false
        var inner: Cancellable?

        func cancel() {
            isCancelled = true
            inner?.cancel()
            inner = nil
        }
    }

    private let base: Scheduler
    private let queue: DispatchQueue

    init(base: Scheduler, queue: DispatchQueue) {
        self.base = base
        self.queue = queue
    }

    func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> Cancellable {
        let token = Token()
        let queue = self.queue
        token.inner = base.schedule(after: delay) {
            queue.async {
                guard !token.isCancelled else { return }
                block()
            }
        }
        return token
    }
}
