# Boulder

Boulder ist die Begleit-App für die Pebble-Uhren von dysseus-pascal. Sie ist ein Fork von **Gravel** (entgoogelte Pebble-App, selbst ein Fork der [Pebble-App von Core Devices](https://github.com/coredevices/mobileapp)) und gehört zur **Boulder-Firmware** (Fork von PebbleOS 4.38.1, Releases in [boulder-firmware](https://github.com/dysseus-pascal/boulder-firmware)).

Gravels eigene Health-Oberfläche ist ersetzt durch die von **Kiesel-Helper**, das seither archiviert ist:

- **Health:** Bewegung, Schlaf mit Phasen, Herz, SpO2, Wasser, Präparate, Koffein; Nachtansicht, Trends und ein Widget.
- **Training:** die Trainings von Kieselsport (auf der Uhr eingebaut) mit Strecke, Puls, Zonen und einer Auswertung je Sportart.
- **Uhr-Apps:** Kieselsport, Drinktervall und SupCycle sprechen direkt mit Boulder; Kieselstrasse bekommt die Abzweigungen von OsmAnd.
- **Health Connect:** Minutendaten, Nächte mit Phasen, Ruhepuls, HRV, Trainings, Wasser und Präparate.
- **Sicherung** in einen Ordner nach Wahl, auch die Übernahme aus einer Kiesel-Helper-Sicherung.

Die Einstellungen dazu stehen in Gravels Einstellungen unter Health, Sport und Navigation.

## Wichtig

- **Gravel und Boulder lassen sich nicht gleichzeitig installieren**: Beide beanspruchen die PebbleKit-Schnittstelle `com.getpebble.android.provider.basalt`.
- **Firmware:** Boulder bietet nur die Boulder-Firmware an. Die offizielle ab 4.38.2 schickt kein SpO2 mehr in den Minutendaten und kennt die Nächte mit Phasen nicht.

## Bauen

```
./gradlew :androidApp:assembleDebug
```

Die Fassung steht in `BOULDER_VERSION`; die Notizen zu jeder Fassung in `.github/release/<fassung>.md`, die Releases unter [Releases](https://github.com/dysseus-pascal/Boulder/releases).

## Lizenz

GNU GPL v3, wie Gravel, Kiesel-Helper und OsmAnds Schnittstelle (`osmand-api`). Siehe `LICENSE`.
