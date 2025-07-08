import Foundation
import os.log

/// Writes `[Responsly]`-prefixed lines to the unified log.
final class SDKLogger: @unchecked Sendable {
    private static let osLog = OSLog(subsystem: "com.responsly.sdk", category: "sdk")

    private let lock = NSLock()
    private var currentLevel: ResponslyLogLevel = .error
    private let sink: ((String) -> Void)?

    /// - Parameter sink: receives every line instead of the unified log (tests).
    init(sink: ((String) -> Void)? = nil) {
        self.sink = sink
    }

    var level: ResponslyLogLevel {
        get {
            lock.lock()
            defer { lock.unlock() }
            return currentLevel
        }
        set {
            lock.lock()
            currentLevel = newValue
            lock.unlock()
        }
    }

    func error(_ message: @autoclosure () -> String) {
        log(.error, message())
    }

    func info(_ message: @autoclosure () -> String) {
        log(.info, message())
    }

    func debug(_ message: @autoclosure () -> String) {
        log(.debug, message())
    }

    private func log(_ messageLevel: ResponslyLogLevel, _ message: @autoclosure () -> String) {
        guard messageLevel != .none, messageLevel <= level else { return }
        let line = "[Responsly] " + message()
        if let sink = sink {
            sink(line)
        } else {
            os_log("%{public}@", log: SDKLogger.osLog, type: messageLevel == .error ? .error : .default, line)
        }
    }
}
