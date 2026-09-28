# Boulder

Boulder ist die Begleit-App für die Pebble-Uhren von dysseus-pascal. Sie ist ein Fork von **Gravel** (entgoogelte Pebble-App, selbst ein Fork der [Pebble-App von Core Devices](https://github.com/coredevices/mobileapp)) samt der SpO2-Anpassung für die Firmware 4.38.1.

Gravels eigene Health-Funktionen werden ersetzt durch die von **Kiesel-Helper**: Schlaf mit Phasen aus den Minutendaten der Uhr, Training mit Strecke und Puls, Trinken und Supplemente, und alles in Health Connect. Die Uhr-Apps Kieselsport, Drinktervall, SupCycle und Kieselstrasse sprechen direkt mit Boulder.

## Stand

- [x] 0.1.0 – Gravel umbenannt, eigenes Symbol, Firmware auf 4.38.1 gehalten
- [ ] Gravels Health-Oberfläche und Health-Connect-Abgleich entfernen (Rohdaten bleiben)
- [ ] Kiesel-Helper als Modul, Uhr-Apps ohne PebbleKit-Umweg
- [ ] Minutendaten nach Health Connect, Schlafanalyse aus den Minutendaten
- [ ] Health-Einstellungen der Uhr, Wochenschnitte für die Health-App der Uhr
- [ ] Genauere Nachtmessung

## Wichtig

- **Gravel und Boulder lassen sich nicht gleichzeitig installieren**: Beide beanspruchen die PebbleKit-Schnittstelle `com.getpebble.android.provider.basalt`.
- **Firmware:** Boulder bietet nichts Neueres als 4.38.1 an. Ab 4.38.2 schickt die Uhr kein SpO2 mehr in den Minutendaten.

## Bauen

```
./gradlew :androidApp:assembleDebug
```

Die Fassung steht in `BOULDER_VERSION`. Die CI baut jeden Push auf `main` und legt zu jeder neuen Fassung ein Release an; die Notizen stehen in `.github/release/<fassung>.md`.

## Lizenz

GNU GPL v3, wie Gravel und Kiesel-Helper. Siehe `LICENSE`.
