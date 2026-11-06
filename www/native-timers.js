'use strict';

var exec = require('cordova/exec');
var SERVICE = 'NativeTimers';
var MIN_INTERVAL_MS = 100;
var counter = 0;
var callbacks = Object.create(null);

function normaliseError(error) {
  var source = error || {};
  var message = typeof source === 'string'
    ? source
    : source.message || 'Native timer operation failed';
  var result = new Error(message);
  result.code = source.code || 'NATIVE_ERROR';
  return result;
}

function schedule(callback, delayMs, options, repeating) {
  if (typeof callback !== 'function') {
    throw new TypeError('Timer callback must be a function');
  }
  if (typeof delayMs !== 'number' || !isFinite(delayMs) || delayMs < 0) {
    throw new TypeError('Timer delay must be a non-negative finite number');
  }
  if (repeating && delayMs < MIN_INTERVAL_MS) {
    throw new TypeError('Timer interval must be at least ' + MIN_INTERVAL_MS + 'ms');
  }

  options = options || {};
  var id = 'native-timer-' + (++counter);
  var label = typeof options.label === 'string' ? options.label : '';
  var entry = {
    callback: callback,
    repeating: repeating,
    onError: options.onError
  };
  callbacks[id] = entry;

  try {
    exec(
      function (event) {
        var activeEntry = callbacks[id];
        if (!activeEntry) return;
        if (!activeEntry.repeating) delete callbacks[id];
        activeEntry.callback(event);
      },
      function (error) {
        var activeEntry = callbacks[id];
        if (!activeEntry) return;
        delete callbacks[id];
        var timerError = normaliseError(error);
        if (typeof activeEntry.onError === 'function') {
          activeEntry.onError(timerError);
        } else {
          console.error(timerError);
        }
      },
      SERVICE,
      repeating ? 'setInterval' : 'setTimeout',
      [id, delayMs, {
        label: label,
        wakeLock: options.wakeLock === true
      }]
    );
  } catch (error) {
    delete callbacks[id];
    throw error;
  }

  return id;
}

function clear(id) {
  delete callbacks[id];
  exec(function () {}, function () {}, SERVICE, 'clear', [id]);
}

var NativeTimers = {
  /** Schedules a one-shot native timer and returns its synchronous ID. */
  setTimeout: function (callback, delayMs, options) {
    return schedule(callback, delayMs, options, false);
  },

  /** Schedules a repeating native timer and returns its synchronous ID. */
  setInterval: function (callback, intervalMs, options) {
    return schedule(callback, intervalMs, options, true);
  },

  /** Clears a native timeout or interval. */
  clearTimeout: clear,

  /** Clears a native timeout or interval. */
  clearInterval: clear,

  /** Clears every registered native timer and callback. */
  clearAll: function () {
    Object.keys(callbacks).forEach(function (id) {
      delete callbacks[id];
    });
    exec(function () {}, function () {}, SERVICE, 'clearAll', []);
  }
};

module.exports = NativeTimers;
