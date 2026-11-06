import Cordova
import Foundation
import OSLog
import UIKit

@objc(NativeTimersPlugin)
class NativeTimersPlugin: CDVPlugin {
    private static let minIntervalMs = 100
    private static let leewayMs = 10
    private static let lateLogThresholdMs: Double = 1000

    private let timerQueue = DispatchQueue(label: "uk.co.workingedge.nativetimers")
    private let timerQueueKey = DispatchSpecificKey<Bool>()
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "NativeTimers",
        category: "NativeTimers"
    )
    private var timers: [String: TimerEntry] = [:]

    override func pluginInitialize() {
        super.pluginInitialize()
        timerQueue.setSpecific(key: timerQueueKey, value: true)
    }

    @objc(setTimeout:)
    func setTimeout(_ command: CDVInvokedUrlCommand) {
        schedule(command, repeating: false)
    }

    @objc(setInterval:)
    func setInterval(_ command: CDVInvokedUrlCommand) {
        schedule(command, repeating: true)
    }

    @objc(clear:)
    func clear(_ command: CDVInvokedUrlCommand) {
        let id = command.argument(at: 0) as? String ?? ""
        timerQueue.async {
            self.clearTimer(id)
            self.sendSuccess(command.callbackId)
        }
    }

    @objc(clearAll:)
    func clearAll(_ command: CDVInvokedUrlCommand) {
        timerQueue.async {
            self.clearAllTimers()
            self.sendSuccess(command.callbackId)
        }
    }

    private func schedule(_ command: CDVInvokedUrlCommand, repeating: Bool) {
        guard command.arguments.count >= 2,
              let id = command.argument(at: 0) as? String,
              !id.isEmpty,
              let delayNumber = command.argument(at: 1) as? NSNumber else {
            sendError(command.callbackId, code: "INVALID_ARGUMENTS", message: "Timer ID and delay are required")
            return
        }

        let requestedDelayMs = delayNumber.doubleValue
        guard requestedDelayMs.isFinite, requestedDelayMs >= 0 else {
            sendError(
                command.callbackId,
                code: "INVALID_DELAY",
                message: "Timer delay must be a non-negative finite number"
            )
            return
        }
        if repeating && requestedDelayMs < Double(Self.minIntervalMs) {
            sendError(
                command.callbackId,
                code: "INVALID_INTERVAL",
                message: "Timer interval must be at least \(Self.minIntervalMs)ms"
            )
            return
        }

        let delayMs = Int(requestedDelayMs.rounded())
        let options = command.argument(at: 2) as? [String: Any] ?? [:]
        let label = options["label"] as? String ?? ""
        let wakeLock = !repeating && options["wakeLock"] as? Bool == true
        let intervalMs = repeating ? delayMs : 0

        timerQueue.async {
            self.clearTimer(id)
            let source = DispatchSource.makeTimerSource(queue: self.timerQueue)
            let entry = TimerEntry(
                id: id,
                label: label,
                intervalMs: intervalMs,
                repeating: repeating,
                dueAtMs: Date().timeIntervalSince1970 * 1000 + Double(delayMs),
                callbackId: command.callbackId,
                source: source
            )

            if wakeLock {
                let taskId = self.beginBackgroundTask(id: id, label: label)
                guard taskId != .invalid else {
                    self.sendError(
                        command.callbackId,
                        code: "BACKGROUND_TASK_UNAVAILABLE",
                        message: "Could not begin background task for timer \(label)"
                    )
                    return
                }
                entry.backgroundTaskId = taskId
            }

            let scheduledAt = DispatchWallTime.now() + .milliseconds(delayMs)
            if repeating {
                source.schedule(
                    wallDeadline: scheduledAt,
                    repeating: .milliseconds(intervalMs),
                    leeway: .milliseconds(Self.leewayMs)
                )
            } else {
                source.schedule(
                    wallDeadline: scheduledAt,
                    leeway: .milliseconds(Self.leewayMs)
                )
            }
            let weakSelf = self
            source.setEventHandler { [weak weakSelf] in
                weakSelf?.fireTimer(id)
            }
            self.timers[id] = entry

            let pending = CDVPluginResult(status: .noResult)
            pending.setKeepCallbackAs(true)
            self.commandDelegate.send(pending, callbackId: command.callbackId)
            self.logger.debug(
                """
                Set timer: id=\(id, privacy: .public) label=\(label, privacy: .public) \
                delayMs=\(delayMs, privacy: .public) repeating=\(repeating, privacy: .public) \
                backgroundTask=\(wakeLock, privacy: .public)
                """
            )
            source.resume()
        }
    }

    private func fireTimer(_ id: String) {
        guard let entry = timers[id] else { return }

        let firedAtMs = Date().timeIntervalSince1970 * 1000
        let lateMs = firedAtMs - entry.dueAtMs
        let event: [String: Any] = [
            "id": entry.id,
            "label": entry.label,
            "dueAt": entry.dueAtMs,
            "firedAt": firedAtMs,
            "lateMs": lateMs
        ]
        let result = CDVPluginResult(status: .ok, messageAs: event)
        result.setKeepCallbackAs(entry.repeating)
        commandDelegate.send(result, callbackId: entry.callbackId)
        logger.info(
            """
            Timer fired: id=\(entry.id, privacy: .public) label=\(entry.label, privacy: .public) \
            lateMs=\(Int(lateMs), privacy: .public)
            """
        )
        if lateMs > Self.lateLogThresholdMs {
            logger.warning(
                """
                Timer fired late: id=\(entry.id, privacy: .public) \
                label=\(entry.label, privacy: .public) lateMs=\(Int(lateMs), privacy: .public)
                """
            )
        }

        if entry.repeating {
            entry.dueAtMs += Double(entry.intervalMs)
        } else {
            timers.removeValue(forKey: id)
            entry.source.cancel()
            endBackgroundTask(entry.backgroundTaskId)
            entry.backgroundTaskId = .invalid
        }
    }

    private func clearTimer(_ id: String) {
        guard let entry = timers.removeValue(forKey: id) else { return }
        entry.source.cancel()
        endBackgroundTask(entry.backgroundTaskId)
        entry.backgroundTaskId = .invalid
        commandDelegate.send(
            CDVPluginResult(status: .noResult),
            callbackId: entry.callbackId
        )
        logger.debug("Cleared timer: id=\(id, privacy: .public) label=\(entry.label, privacy: .public)")
    }

    private func clearAllTimers() {
        for id in Array(timers.keys) {
            clearTimer(id)
        }
    }

    private func beginBackgroundTask(id: String, label: String) -> UIBackgroundTaskIdentifier {
        var taskId = UIBackgroundTaskIdentifier.invalid
        let weakSelf = self
        let beginTask = { [weak weakSelf] in
            taskId = UIApplication.shared.beginBackgroundTask(withName: "NativeTimers:\(label)") { [weak weakSelf] in
                guard let self = weakSelf else { return }
                self.timerQueue.async {
                    guard let entry = self.timers[id],
                          entry.backgroundTaskId != .invalid else { return }
                    let expiredTaskId = entry.backgroundTaskId
                    entry.backgroundTaskId = .invalid
                    self.endBackgroundTask(expiredTaskId)
                    self.logger.warning(
                        """
                        Background task expired: id=\(id, privacy: .public) \
                        label=\(label, privacy: .public)
                        """
                    )
                }
            }
        }
        if Thread.isMainThread {
            beginTask()
        } else {
            DispatchQueue.main.sync(execute: beginTask)
        }
        return taskId
    }

    private func endBackgroundTask(_ taskId: UIBackgroundTaskIdentifier) {
        guard taskId != .invalid else { return }
        let endTask = {
            UIApplication.shared.endBackgroundTask(taskId)
        }
        if Thread.isMainThread {
            endTask()
        } else {
            DispatchQueue.main.async(execute: endTask)
        }
    }

    private func sendSuccess(_ callbackId: String) {
        commandDelegate.send(CDVPluginResult(status: .ok), callbackId: callbackId)
    }

    private func sendError(_ callbackId: String, code: String, message: String) {
        commandDelegate.send(
            CDVPluginResult(status: .error, messageAs: ["code": code, "message": message]),
            callbackId: callbackId
        )
    }

    private func clearAllSynchronously() {
        if DispatchQueue.getSpecific(key: timerQueueKey) != nil {
            clearAllTimers()
        } else {
            timerQueue.sync {
                self.clearAllTimers()
            }
        }
    }

    override func onReset() {
        clearAllSynchronously()
        super.onReset()
    }

    override func dispose() {
        clearAllSynchronously()
        super.dispose()
    }

    private final class TimerEntry {
        let id: String
        let label: String
        let intervalMs: Int
        let repeating: Bool
        let callbackId: String
        let source: DispatchSourceTimer
        var dueAtMs: Double
        var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid

        init(
            id: String,
            label: String,
            intervalMs: Int,
            repeating: Bool,
            dueAtMs: Double,
            callbackId: String,
            source: DispatchSourceTimer
        ) {
            self.id = id
            self.label = label
            self.intervalMs = intervalMs
            self.repeating = repeating
            self.dueAtMs = dueAtMs
            self.callbackId = callbackId
            self.source = source
        }
    }
}
