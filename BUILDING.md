# Building Boulder

```
./gradlew :androidApp:assembleDebug
```

Your own builds are signed with your own debug key, so they cannot update an installed Boulder. Boulder's release key is not in this repository.

The version is in `BOULDER_VERSION`, the notes for each version in `.github/release/<version>.md`, and the builds under [Releases](https://github.com/dysseus-pascal/Boulder/releases).

- **Boulder and Gravel cannot be installed at the same time.** Both claim the PebbleKit provider `com.getpebble.android.provider.basalt`, and so does the official Pebble app.
- **Firmware:** Boulder only offers the Boulder firmware. The official firmware since 4.38.2 no longer sends SpO2 in the minute data and does not know nights with phases.
