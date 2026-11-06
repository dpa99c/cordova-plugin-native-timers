# Cordova Native Timers

Schedules one-shot and repeating timers on Android and iOS, then delivers timer events through Cordova's native-to-JavaScript bridge. This avoids WebView timer throttling for short user-visible work while an app is backgrounded.

## API

The API is exposed as `cordova.plugins.nativeTimers` after `deviceready`:

```js
var timerId = cordova.plugins.nativeTimers.setTimeout(function (event) {
  console.log('Timer fired:', event.label, event.lateMs);
}, 1500, {
  label: 'tts-auto-read',
  wakeLock: true,
  onError: function (error) {
    console.error('Native timer failed:', error);
  }
});

cordova.plugins.nativeTimers.clearTimeout(timerId);
```

`setInterval(callback, intervalMs, options)` is also available. Intervals must be at least 100 ms. Both clear methods accept either timer ID, and `clearAll()` cancels all timers owned by the plugin.

Timer callbacks receive an event with the timer ID, label, scheduled wall-clock time, firing wall-clock time, and lateness in milliseconds. Errors are reported through `options.onError`, or to the JavaScript console if no error handler is supplied.

## Background behaviour

On Android, the timer runs on a dedicated `HandlerThread`. Without a wake lock, Android's uptime-based scheduling does not advance during CPU deep sleep; short user-facing timers can set `wakeLock: true` to request a bounded partial wake lock. Wake locks are limited to delays of 60 seconds or less and are held for up to 3 seconds after firing so the JavaScript callback can run. Clearing a pending timer releases its wake lock immediately.

On iOS, wall-clock deadlines avoid stretching the delay when the device sleeps. Setting `wakeLock: true` requests a finite background task so the app can finish the timer callback. The task is held for up to 3 seconds after firing so the JavaScript callback can run; clearing a pending timer ends it immediately. If iOS expires the background task, it is ended immediately and a `Background task expired` warning is logged; the timer itself still fires. iOS may still suspend an app when the operating system ends that task.

These timers are not a replacement for durable background jobs or alarms. Platform policies can delay execution, and native timer callbacks should remain short.

## Development

Run the JavaScript bridge tests with:

```sh
npm test
```

Platform builds and physical-device checks are required to validate timer delivery, wake-lock handling, app suspension, and background execution.
