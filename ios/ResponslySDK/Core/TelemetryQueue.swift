import Foundation

protocol Cancellable: AnyObject {
    func cancel()
}

/// Runs a block after a delay. The core wraps it so that blocks always run on the core queue.
protocol Scheduler {
    func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> Cancellable
}

final class DispatchScheduler: Scheduler {
    private final class Item: Cancellable {
        let workItem: DispatchWorkItem

        init(_ workItem: DispatchWorkItem) {
            self.workItem = workItem
        }

        func cancel() {
            workItem.cancel()
        }
    }

    private let queue: DispatchQueue

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> Cancellable {
        let workItem = DispatchWorkItem(block: block)
        queue.asyncAfter(deadline: .now() + max(0, delay), execute: workItem)
        return Item(workItem)
    }
}

/// Best-effort telemetry (protocol section 2): batches of at most 20, flushed when 20 events are
/// waiting, when the app goes to the background and at most every 30 seconds while events are
/// waiting. Events that cannot be sent are kept (at most 200) and retried later.
///
/// Not thread-safe by itself: every call, including the `send` completion, must arrive on the
/// same serial queue.
final class TelemetryQueue {
    static let batchSize = 20
    static let maxQueued = 200
    static let flushInterval: TimeInterval = 30

    typealias Send = (_ events: [TelemetryEvent], _ completion: @escaping (TelemetrySendResult) -> Void) -> Void

    private let store: StateStore
    private let scheduler: Scheduler
    private let isEnabled: () -> Bool
    private let send: Send

    private(set) var events: [TelemetryEvent]
    private var inFlightCount = 0
    private var generation = 0
    private var timer: Cancellable?
    /// A send failed; wait for the timer (or an explicit flush) instead of retrying on every event.
    private var waitingToRetry = false
    private var flushCompletions: [() -> Void] = []

    init(store: StateStore, scheduler: Scheduler, isEnabled: @escaping () -> Bool, send: @escaping Send) {
        self.store = store
        self.scheduler = scheduler
        self.isEnabled = isEnabled
        self.send = send
        self.events = store.loadTelemetry()
    }

    func enqueue(type: TelemetryEventType, surveyId: String) {
        events.append(TelemetryEvent(type: type, surveyId: surveyId))
        while events.count > TelemetryQueue.maxQueued {
            // Drop the oldest event that is not part of the batch being sent.
            events.remove(at: min(inFlightCount, events.count - 1))
        }
        store.saveTelemetry(events)

        if events.count >= TelemetryQueue.batchSize && !waitingToRetry {
            flush()
        } else {
            scheduleFlushIfNeeded()
        }
    }

    /// Starts the 30-second timer when events are waiting and nothing else will flush them.
    func scheduleFlushIfNeeded() {
        guard timer == nil, inFlightCount == 0, !events.isEmpty, isEnabled() else { return }
        timer = scheduler.schedule(after: TelemetryQueue.flushInterval) { [weak self] in
            guard let self = self else { return }
            self.timer = nil
            self.flush()
        }
    }

    /// Sends everything that is waiting, batch by batch, until the queue is empty or a send fails.
    func flush(completion: (() -> Void)? = nil) {
        if let completion = completion {
            flushCompletions.append(completion)
        }
        guard inFlightCount == 0 else { return }
        guard isEnabled(), !events.isEmpty else {
            finishFlush()
            return
        }
        timer?.cancel()
        timer = nil
        waitingToRetry = false

        let batch = Array(events.prefix(TelemetryQueue.batchSize))
        inFlightCount = batch.count
        let sentGeneration = generation
        send(batch) { [weak self] result in
            guard let self = self else { return }
            guard sentGeneration == self.generation else { return }
            let sentCount = self.inFlightCount
            self.inFlightCount = 0
            switch result {
            case .sent, .rejected:
                self.events.removeFirst(min(sentCount, self.events.count))
                self.store.saveTelemetry(self.events)
                if self.events.isEmpty {
                    self.finishFlush()
                } else {
                    self.flush()
                }
            case .retry:
                self.waitingToRetry = true
                self.scheduleFlushIfNeeded()
                self.finishFlush()
            }
        }
    }

    /// Drops everything that is waiting (`reset()`).
    func clear() {
        generation += 1
        inFlightCount = 0
        waitingToRetry = false
        timer?.cancel()
        timer = nil
        events = []
        store.saveTelemetry(events)
        finishFlush()
    }

    /// Stops the timer without touching the queue (tracking was disabled).
    func suspend() {
        timer?.cancel()
        timer = nil
    }

    private func finishFlush() {
        let completions = flushCompletions
        flushCompletions = []
        completions.forEach { $0() }
    }
}
