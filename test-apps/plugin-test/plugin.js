// Demo plugin bundled in the dashboard test app. It serves one `demo/message` source whose text
// the config page sets over the plugin config-message channel — enough to exercise pbw plugin
// sideloading end to end (manifest in appinfo -> registry -> this script -> a served source).

var STORE_KEY = 'demo_message';

function currentText() {
  return localStorage.getItem(STORE_KEY) || 'Hello from the demo plugin';
}

Pebble.registerSourceHandler(function (request, respond) {
  respond.data({
    instances: [
      { instanceId: 'message', properties: { text: { shortText: { text: currentText() } } } },
    ],
  });
});

Pebble.registerConfigHandler(function (message, respond) {
  if (message && message.type === 'setText') {
    localStorage.setItem(STORE_KEY, String(message.text || ''));
    Pebble.refreshSources(['demo/message']);
    respond({ ok: true, text: currentText() });
  } else if (message && message.type === 'status') {
    respond({ text: currentText() });
  } else {
    respond({ ok: false });
  }
});
