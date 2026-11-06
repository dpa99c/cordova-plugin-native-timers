'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const pluginRoot = path.resolve(__dirname, '..');

function loadModule(exec) {
  const module = {exports: {}};
  vm.runInNewContext(
    fs.readFileSync(path.join(pluginRoot, 'www/native-timers.js'), 'utf8'),
    {
      module: module,
      exports: module.exports,
      require: function (name) {
        assert.equal(name, 'cordova/exec');
        return exec;
      },
      console: console,
      Error: Error,
      TypeError: TypeError,
      Object: Object,
      String: String,
      isFinite: isFinite
    },
    {filename: 'www/native-timers.js'}
  );
  return module.exports;
}

test('generates IDs and forwards timer arguments to Cordova', function () {
  const calls = [];
  const api = loadModule(function (success, failure, service, action, args) {
    calls.push({success: success, failure: failure, service: service, action: action, args: args});
  });
  const callback = function () {};

  const firstId = api.setTimeout(callback, 250, {label: 'first', wakeLock: true});
  const secondId = api.setInterval(callback, 500, {label: 'second'});

  assert.equal(firstId, 'native-timer-1');
  assert.equal(secondId, 'native-timer-2');
  assert.equal(calls[0].service, 'NativeTimers');
  assert.equal(calls[0].action, 'setTimeout');
  assert.equal(calls[0].args[0], firstId);
  assert.equal(calls[0].args[1], 250);
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0].args[2])), {
    label: 'first',
    wakeLock: true
  });
  assert.equal(calls[1].action, 'setInterval');
});

test('fires timeout callbacks once and keeps interval callbacks registered', function () {
  const calls = [];
  const api = loadModule(function (success, _failure, _service, action) {
    calls.push({success: success, action: action});
  });
  const timeoutCallback = [];
  const intervalCallback = [];

  api.setTimeout(function (event) {
    timeoutCallback.push(event);
  }, 100, {label: 'timeout'});
  api.setInterval(function (event) {
    intervalCallback.push(event);
  }, 100, {label: 'interval'});

  const timeoutEvent = {id: 'timeout-id', label: 'timeout', dueAt: 1, firedAt: 2, lateMs: 1};
  calls[0].success(timeoutEvent);
  calls[0].success(timeoutEvent);
  assert.deepEqual(timeoutCallback, [timeoutEvent]);

  const intervalEvent = {id: 'interval-id', label: 'interval', dueAt: 3, firedAt: 4, lateMs: 1};
  calls[1].success(intervalEvent);
  calls[1].success(intervalEvent);
  assert.deepEqual(intervalCallback, [intervalEvent, intervalEvent]);
});

test('ignores a timer event that arrives after clear', function () {
  let success;
  let clearAction;
  const callback = [];
  const api = loadModule(function (onSuccess, _failure, _service, action) {
    if (action === 'clear') {
      clearAction = action;
    } else {
      success = onSuccess;
    }
  });
  const id = api.setTimeout(function (event) {
    callback.push(event);
  }, 100, {label: 'cleared'});

  api.clearTimeout(id);
  success({id: id, label: 'cleared'});

  assert.equal(clearAction, 'clear');
  assert.deepEqual(callback, []);
});

test('routes native timer errors to the supplied error handler', function () {
  let failure;
  const errors = [];
  const api = loadModule(function (_success, onError) {
    failure = onError;
  });

  api.setTimeout(function () {}, 100, {
    label: 'error',
    onError: function (error) {
      errors.push(error);
    }
  });
  failure({code: 'SCHEDULE_FAILED', message: 'No timer thread'});

  assert.equal(errors.length, 1);
  assert.equal(errors[0].code, 'SCHEDULE_FAILED');
  assert.equal(errors[0].message, 'No timer thread');
});

test('validates callbacks and timer delays', function () {
  const api = loadModule(function () {});

  assert.throws(function () {
    api.setTimeout(null, 100);
  }, TypeError);
  assert.throws(function () {
    api.setTimeout(function () {}, -1);
  }, TypeError);
  assert.throws(function () {
    api.setTimeout(function () {}, Infinity);
  }, TypeError);
  assert.throws(function () {
    api.setInterval(function () {}, 99);
  }, TypeError);
});
