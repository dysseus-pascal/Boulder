globalThis._LibPebbleTimeoutCallbacks = new Map();
globalThis._LibPebbleTriggerTimeout = function (timeoutId) {
    if (globalThis._LibPebbleTimeoutCallbacks.has(timeoutId)) {
        const { callback, args } = globalThis._LibPebbleTimeoutCallbacks.get(timeoutId);
        callback(...args);
        globalThis._LibPebbleTimeoutCallbacks.delete(timeoutId);
    }
}
globalThis._LibPebbleTriggerInterval = function (intervalId) {
    if (globalThis._LibPebbleTimeoutCallbacks.has(intervalId)) {
        const { callback, args } = globalThis._LibPebbleTimeoutCallbacks.get(intervalId);
        callback(...args);
    }
}
globalThis.setTimeout = function (callback, delay, ...args) {
    if (typeof callback !== 'function') {
        throw new TypeError('First argument must be a function');
    }

    const timeoutId = _Timeout.setTimeout(delay);
    globalThis._LibPebbleTimeoutCallbacks.set(timeoutId, { callback, args });
    return timeoutId;
}
globalThis.clearTimeout = function (timeoutId) {
    if (timeoutId === undefined || timeoutId === null) {
        return;
    }
    if (typeof timeoutId !== 'number') {
        throw new TypeError('First argument must be a number');
    }

    if (globalThis._LibPebbleTimeoutCallbacks.has(timeoutId)) {
        globalThis._Timeout.clearTimeout(timeoutId);
        globalThis._LibPebbleTimeoutCallbacks.delete(timeoutId);
    }
}
globalThis.setInterval = function (callback, delay, ...args) {
    if (typeof callback !== 'function') {
        throw new TypeError('First argument must be a function');
    }

    const intervalId = _Timeout.setInterval(delay);
    globalThis._LibPebbleTimeoutCallbacks.set(intervalId, { callback, args });
    return intervalId;
}
globalThis.clearInterval = function (intervalId) {
    if (intervalId === undefined || intervalId === null) {
        return;
    }
    if (typeof intervalId !== 'number') {
        throw new TypeError('First argument must be a number');
    }

    if (globalThis._LibPebbleTimeoutCallbacks.has(intervalId)) {
        globalThis._Timeout.clearInterval(intervalId);
        globalThis._LibPebbleTimeoutCallbacks.delete(intervalId);
    }
}