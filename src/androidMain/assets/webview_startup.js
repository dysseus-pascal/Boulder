((global) => {
    const PebbleEventTypes = {
        READY: 'ready',
        SHOW_CONFIGURATION: 'showConfiguration',
        WEBVIEW_OPENED: 'webviewopened',
        WEBVIEW_CLOSED: 'webviewclosed',
        APP_MESSAGE: 'appmessage',
        APP_MESSAGE_ACK: 'appmessage_ack',
        APP_MESSAGE_NACK: 'appmessage_nack',
        GET_TIMELINE_TOKEN_SUCCESS: 'getTimelineTokenSuccess',
        GET_TIMELINE_TOKEN_FAILURE: 'getTimelineTokenFailure',
    };
    Object.freeze(PebbleEventTypes);
    const DEFAULT_TIMEOUT = 5000; // 5 seconds

    class PebbleEventListener {
        constructor() {
            this.events = new Map();
            this._eventInitializers = {};
        }

        addEventListener(type, callback, useCapture /* ignored */) {
            if (typeof callback !== 'function') {
                console.warn(`Pebble JS Bridge: addEventListener called with non-function callback for type "${type}"`);
                return;
            }

            if (!this.events.has(type)) {
                this.events.set(type, new Set());

                // Call the event initializer if this is the first time
                if (typeof this._eventInitializers[type] === 'function') {
                    try {
                        this._eventInitializers[type]();
                    } catch(e) {
                        console.error(`Pebble JS Bridge: Error in event initializer for "${type}"`, e);
                    }
                }
            }
            this.events.get(type).add(callback);
        }

        removeEventListener(type, callback) {
            const listeners = this.events.get(type);
            if (!listeners) {
                return;
            }
            listeners.delete(callback);
            if (listeners.size === 0) {
                this.events.delete(type);
            }
        }

        dispatchEvent(event) {
            const listeners = this.events.get(event.type);
            if (!listeners || listeners.size === 0) {
                return false; // Indicate no listeners were called
            }

            // Clone the listeners to avoid modifying the set while iterating
            const listenersCopy = [...listeners];
            let allSucceeded = true;

            listenersCopy.forEach(listener => {
                try {
                    const removeListener = listener(event);
                    if (removeListener === true) {
                        listeners.delete(listener);
                    }
                } catch (e) {
                    console.error(`Pebble JS Bridge: Error in listener for event "${event.type}"`, e);
                    allSucceeded = false;
                }
            });
             if (listeners.size === 0) {
                this.events.delete(event.type);
            }
            return allSucceeded;
        }
    }

    const pebbleEventHandler = new PebbleEventListener();
    const appMessageAckCallbacks = new Map();
    const appMessageNackCallbacks = new Map();

    const dispatchPebbleEvent = (type, detail = {}) => {
        const event = new CustomEvent(type, { detail, bubbles: true, cancelable: true });
        Object.assign(event, detail);
        if ('payload' in detail) event.data = detail.payload;
        if ('response' in detail) event.data = detail.response;
        if ('ready' in detail) event.data = detail.ready;
        if ('opened' in detail) event.data = detail.opened;

        return pebbleEventHandler.dispatchEvent(event);
    };
    const removeAppMessageCallbacksForTransactionId = (tid) => {
        const ackCallback = appMessageAckCallbacks.get(tid);
        if (ackCallback) {
            pebbleEventHandler.removeEventListener('appmessage_ack', ackCallback);
            appMessageAckCallbacks.delete(tid);
        }

        const nackCallback = appMessageNackCallbacks.get(tid);
        if (nackCallback) {
            pebbleEventHandler.removeEventListener('appmessage_nack', nackCallback);
            appMessageNackCallbacks.delete(tid);
        }
    }

    const signalLoaded = () => {
        _Pebble.signalAppScriptLoadedByBootstrap();
    }

    global.signalWebviewOpenedEvent = (data) => {
        dispatchPebbleEvent(PebbleEventTypes.WEBVIEW_OPENED, { opened: data });
    }
    global.signalWebviewClosedEvent = (data) => {
        let decoded = data;
        if (data && data.length > 0) {
            try {
                decoded = JSON.parse(data);
            } catch (e) {
                console.error("Pebble JS Bridge: Error parsing webview closed data", e);
            }
        }
        dispatchPebbleEvent(PebbleEventTypes.WEBVIEW_CLOSED, { closed: decoded });
    }
    global.signalReady = (data) => {
        const success = dispatchPebbleEvent(PebbleEventTypes.READY, { ready: data });
        try {
            _Pebble.privateFnConfirmReadySignal(success);
        } catch (e) {
            console.error("Pebble JS Bridge: Error confirming ready signal", e);
        }
    }
    global.signalNewAppMessageData = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.APP_MESSAGE, { payload });
    }
    global.signalAppMessageAck = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.APP_MESSAGE_ACK, { ack: payload });

        if (payload.transactionId !== undefined) {
            removeAppMessageCallbacksForTransactionId(payload.transactionId);
        }
    }
    global.signalAppMessageNack = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.APP_MESSAGE_NACK, { nack: payload });

        if (payload.transactionId !== undefined) {
            removeAppMessageCallbacksForTransactionId(payload.transactionId);
        }
    }
    global.signalSettingsWebuiLaunchOpportunity = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent('showConfiguration', { payload });
        // Legacy event
        dispatchPebbleEvent('settings_webui_allowed', { payload });
    };
    global.signalTimelineTokenSuccess = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, { payload });
    };
    global.signalTimelineTokenFailure = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, { payload });
    };
    global.loadScript = (url) => {
        const head = document.getElementsByTagName("head")[0];
        const script = document.createElement("script");
        script.type = "text/javascript";
        script.src = url;
        script.onreadystatechange = signalLoaded;
        script.onload = signalLoaded;

        head.appendChild(script);
    }

    const setupXHRInterceptor = () => {
        if (!XMLHttpRequest.prototype.hasOwnProperty('originalSend')) {
            XMLHttpRequest.prototype.originalSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.originalOpen = XMLHttpRequest.prototype.open;

            XMLHttpRequest.prototype.send = function(body) {
                const xhr = this;
                _Pebble.logInterceptedSend();
                if (xhr.timeout === 0 && xhr.pebbleAsync) {
                    xhr.timeout = DEFAULT_TIMEOUT;
                }
                xhr.originalSend(body);
            }
            XMLHttpRequest.prototype.open = function(method, url, async = true, user, password) {
                this.pebbleAsync = async;
                if (arguments.length > 4) {
                    this.originalOpen(method, url, async, user, password);
                } else if (arguments.length > 3) {
                    this.originalOpen(method, url, async, user);
                } else {
                    this.originalOpen(method, url, async);
                }
            }
        }
    }

    const PebbleAPI = {
        addEventListener: (type, callback, useCapture) => {
            pebbleEventHandler.addEventListener(type, callback, useCapture);
        },
        removeEventListener: (type, callback) => {
            pebbleEventHandler.removeEventListener(type, callback);
        },
        sendAppMessage: (data, onSuccess, onFailure) => {
            const transactionId = _Pebble.sendAppMessageString(JSON.stringify(data));
            if (onSuccess) {
                const callback = (e) => {
                    try {
                        if (e.data.transactionId === transactionId) {
                            onSuccess(e);
                        }
                    } catch (error) {}
                }
                appMessageAckCallbacks.set(transactionId, callback);
                pebbleEventHandler.addEventListener(PebbleEventTypes.APP_MESSAGE_ACK, callback);
            }
            if (onFailure) {
                const callback = (e) => {
                    try {
                        if (e.data.transactionId === transactionId) {
                            onFailure(e);
                        }
                    } catch (error) {}
                }
                appMessageNackCallbacks.set(transactionId, callback);
                pebbleEventHandler.addEventListener(PebbleEventTypes.APP_MESSAGE_NACK, callback);
            }
            return transactionId;
        },
        getTimelineToken: (onSuccess, onFailure) => {
            const callId = _Pebble.getTimelineTokenAsync();
            const successCallback = (e) => {
                if (e.data.callId === callId) {
                    onSuccess(e.data.userToken);
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, successCallback);
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, failureCallback);
                }
            }
            const failureCallback = (e) => {
                if (e.data.callId === callId) {
                    onFailure();
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, successCallback);
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, failureCallback);
                }
            }
            pebbleEventHandler.addEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, successCallback);
            pebbleEventHandler.addEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, failureCallback);
        },
        timelineSubscribe: (token, onSuccess, onFailure) => {
            //TODO
        },
        timelineUnsubscribe: (token, onSuccess, onFailure) => {
            //TODO
        },
        timelineSubscriptions: (onSuccess, onFailure) => {
            //TODO
        },
        getActiveWatchInfo: () => {
            const data = _Pebble.getActivePebbleWatchInfo();
            return data ? JSON.parse(data) : null;
        },
        getAccountToken: () => {
            //TODO
            return null;
        },
        getWatchToken: () => {
            //TODO
            return null;
        },
        appGlanceReload: (appGlanceSlices, onSuccess, onFailure) => {
            //TODO
        },
        openURL: (url) => {
            //TODO
        }
    }
    setupXHRInterceptor();
    global.Pebble.addEventListener = PebbleAPI.addEventListener;
    global.Pebble.removeEventListener = PebbleAPI.removeEventListener;
    global.Pebble.sendAppMessage = PebbleAPI.sendAppMessage;
    global.Pebble.getTimelineToken = PebbleAPI.getTimelineToken;
    global.Pebble.timelineSubscribe = PebbleAPI.timelineSubscribe;
    global.Pebble.timelineUnsubscribe = PebbleAPI.timelineUnsubscribe;
    global.Pebble.timelineSubscriptions = PebbleAPI.timelineSubscriptions;
    global.Pebble.getActiveWatchInfo = PebbleAPI.getActiveWatchInfo;
    global.Pebble.getAccountToken = PebbleAPI.getAccountToken;
    global.Pebble.getWatchToken = PebbleAPI.getWatchToken;
    global.Pebble.appGlanceReload = PebbleAPI.appGlanceReload;
    global.Pebble.openURL = PebbleAPI.openURL;

    console.log("Pebble JS Bridge initialized.");

})(window);