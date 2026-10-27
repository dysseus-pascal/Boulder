# Boulder

Dieses Repo ist **Boulder**, ein Fork von Gravel (unten beschrieben). Es gelten
die Regeln von Gravel, mit diesen Abweichungen:

- App-Kennung `ch.dysseus.boulder`, Name Boulder. Fassung in `BOULDER_VERSION`,
  versionCode = Zahl der Commits. Kein F-Droid, keine `version.properties`.
- Direkt auf `main`; jede neue Fassung wird ein Release (`.github/release/<fassung>.md`,
  deutsch, erste Zeile Titel ohne Nummer). Gebaut wird lokal, als Release-Build
  (`:androidApp:assembleRelease` mit `LOCAL_RELEASE_BUILD=true` in `local.properties`,
  signiert mit Boulders festem Schluessel in `~/.android/debug.keystore`, APK aus
  `androidApp/build/outputs/apk/release/`); das Release legt
  `gh release create v<fassung>` an, Titel `<fassung> - <erste Zeile>`. Der Tag
  traegt ein `v`: die CI (`.github/workflows/build.yml`) laeuft wieder und legt
  selbst ein Release `v<fassung>` an, wenn dieser Tag nach ihren Tests noch fehlt
  - ein Tag ohne `v` ergab deshalb doppelte Releases (0.26.0 bis 0.35.0).
  Das Server-Buendel (`boulder-server-<fassung>.tar.gz`, `server/LIESMICH.md`) haengt die CI an,
  auch an ein lokal angelegtes Release, sobald sie den Stand gebaut hat, auf den dessen Tag zeigt.
  Der feste Schluessel kommt nie ins Repo (es ist oeffentlich, jeder koennte sonst Updates fuer
  installierte Boulder bauen): die CI liest ihn aus dem Secret `BOULDER_APK_KEYSTORE` (base64),
  eine Sitzung aus der gleichnamigen Umgebungsvariable:
  `printf '%s' "$BOULDER_APK_KEYSTORE" | base64 -d > ~/.android/debug.keystore`. sha256 der Datei
  `8398e03d...ff0d544`; ein anderer Schluessel ergibt APKs, die sich nicht als Update installieren.
  Der Debug-Build braucht auf dem Telefon rund die Haelfte mehr Speicher und ist
  nur zum Entwickeln da. Release-Builds sind nicht debuggable: `run-as` geht dort
  nicht, die Datenbank kommt nur ueber die Sicherung heraus.
- Das Repo ist zum Veroeffentlichen neu angelegt (23.10.2026): ganze Geschichte, aber ohne
  `ci/debug.keystore`, darum mit anderen Commit-Kennungen. Releases und Tags bis 0.57.1 liegen
  nur im privaten Archiv `dysseus-pascal/Boulder-archiv`, dessen Commits den Schluessel tragen:
  nie von dort oder aus einem Klon von vor dem 23.10. hierher pushen.
- Gravels Health-Teil ist ersetzt durch den von Kiesel-Helper (archiviert). Er
  steht in `pebble/src/androidMain/kotlin/coredevices/pebble/kiesel/`: Reiter
  Health und Training, Nachtansicht, Trends, Widget, Sicherung, die Begleiter der
  Uhr-Apps (Kieselsport eingebaut, Drinktervall und SupCycle neben ihrem PKJS,
  siehe `BuiltInCompanions` in libpebble3) und Kieselstrasse (OsmAnd, Modul
  `:osmand-api`). Seine Einstellungen haengen ueber `boulderSettingsItems` in
  Gravels Einstellungsliste (Sektionen Health, Sport, Navigation).
- Datalogging, Parser und Rohdaten-Tabellen der Minutendaten bleiben die Quelle;
  die Naechte rechnet die Boulder-Firmware (Tag 88 bzw. 0x4001, siehe `BoulderDls`).
- Zwei Datenbanken: `libpebble3.db` zaehlt nur Upstream (Version =
  `UPSTREAM_VERSATZ` 1000 + Upstream-Version, heute 1050); einzige Boulder-Reste
  dort sind `spo2_readings` und `overlay_data.manual`. Eigene Tabellen nur in
  `BoulderDatenbank` (`boulder.db`, eigene Zaehlung). Aus Upstreams
  `AutoMigration(from = 49, to = 50)` wird `from = UPSTREAM_VERSATZ + 49, to =
  UPSTREAM_VERSATZ + 50`. `SchemaWaechterTest` vergleicht mit Upstream 50 (und mit 45, dem
  Stand von 0.50 bis 0.53).
- Core-Stand: 1.16.0.1 (Merge `d7292d166`). Cores Fassungen kommen per `git merge` ihres Tags
  vom Remote `core` (`coredevices/mobileapp`, nie dorthin pushen). Cores Geschichte steckt seit
  dem Neuanlegen des Repos mit anderen Kennungen darin; seit `d7292d166` ist Cores Commit
  `721670d28` die Merge-Basis, ein Merge geht wieder direkt. Danach in `tools/boulder/vorlage.txt`
  die Zeile `core` auf Cores Commit setzen (den zweiten Elternteil des Merges) und neu messen.
- Nah an der Vorlage (Entscheid vom 25.10.2026): neue Boulder-Logik steht in eigenen Dateien, in
  Cores Dateien nur ein Haken mit `// Boulder:`, der sie ruft. `tools/boulder/vorlage.txt` haelt
  fest, wie viele Zeilen jede Core-Datei abweicht; `tools/boulder/vorlage_waechter.py` prueft das
  in der CI vor den Tests (lokal ebenso aufrufen). Die Zahlen sollen nur sinken: eine weitere
  Core-Datei oder mehr Zeilen brauchen einen Grund im Commit.
- Rueckweg 0.50 -> 0.49 (oder aelter, per `adb install -d`): 0.50 hebt
  `libpebble3.db` auf 1045, und der alte Code von 0.49 (Fassung 50,
  `fallbackToDestructiveMigrationOnDowngrade`, ohne `DowngradeSchutz`) leert sie
  beim Oeffnen GANZ - health_data, overlay_data samt manual, spo2_readings,
  Uhren, Locker, Prefs. Die Naechte liegen in `boulder.db` und bleiben, sind fuer
  0.49 aber unsichtbar; was 0.49 dann an Naechten empfaengt, sieht 0.50 danach
  nicht (der Umzug laeuft nur einmal). Laeuft auf der Uhr schon b25, verwirft
  0.49 jede neue Nacht (0x4001/0x4002 nach dem ACK). Vorher `libpebble3.db` und
  `boulder.db` sichern und die Uhr zuerst auf b24 stellen. Im Code nicht zu
  beheben: das Leeren macht die alte Fassung.
- Firmware kommt aus den Releases von `dysseus-pascal/boulder-firmware`
  (`vX.Y.Z-bN`, siehe `GithubReleases.kt`). Ihr Quellcode liegt oeffentlich in
  `dysseus-pascal/PebbleOS` (Standardzweig `v2`). Dort ist auch der Zweig `notizen` oeffentlich:
  keine Zugangsdaten, Schluessel oder anderes Privates hineinschreiben.
- Gravel-Stand: 0.3.3, der letzte. Boulder baut nicht mehr auf Gravel auf, weil
  Gravel selbst ein Fork ist: keine Gravel-Fassungen mehr nachziehen (Entscheid vom
  13.10.2026). Herkunft ist Gravel (`david-cant-code/pebble-app-degoogled`), ohne
  gemeinsame Git-Geschichte. Nicht uebernommen wurde Gravels Firmware-Auswahl
  (Changelog-Liste, Build-Picker, Firmware-Kanal): sie boete Core-Builds an und
  ersetzte die Boulder-Firmware. Neue Oberflaechentexte sagen "Boulder", nicht "Gravel".
- Copyright: Der README-Abschnitt "Credits, copyright and license" nennt Core Devices, Gravel
  (samt den uebernommenen Dateien), Boulder und die Schrift Inter; die GPL verlangt diese
  Vermerke. Wer Code aus einem anderen Projekt uebernimmt, traegt ihn dort nach, und kein
  Vermerk wird geloescht (0.1.0 hatte Gravels Zeile entfernt, Issue #1).
- Texte nur in den Sprachen, die Gravel hat (derzeit Englisch).
- Der Gravel-Teil unten ist Gravels Original (Stand 0.3.3); seine Regeln gelten weiter. Was
  dort F-Droid, `FdroidGuardrailsTest`, `version.properties` und `changelogs/`
  betrifft, gibt es in Boulder nicht.
- Neue Eintraege in Health Connect bekommen den Zeitzonenversatz (`versatz(t)`
  in `Akte.kt`), sonst stehen sie auf Reisen in der falschen Ortszeit.
- Neuer eigener Code: deutsche Bezeichner und Kommentare, Kommentare erklaeren
  das WARUM.
- Boulders Datenbanktests in libpebble3 laufen als Android-Hosttests unter Robolectric (Room
  liest beim Upsert die Meldung der SQLException, Androids Attrappe verwirft sie) und mit der
  JVM-Fassung von sqlite-bundled (`libpebble3/build.gradle.kts`): Core hat das JVM-Ziel entfernt.

---

# Gravel

Gravel is an **unofficial, Android-only fork** of the Core Devices Pebble
mobile app (upstream name: CoreApp), not affiliated with or endorsed by
Core Devices, distributed under GPLv3 (see `LICENSE`). What Gravel is, its
goals, status, and scope live in `README.md`; this file is the rules for
changing it. How the de-Googling is implemented (the DI seams, the
Firebase stubs, the unplugged Ring module) lives in `DESIGN_NOTES.md`.

## Project rules

- **Android-only.** iOS sources remain in-tree but are unmaintained. Do not
  write, fix, or build iOS code.
- **De-Google at the DI seam, not by mass deletion.** Swap Firebase/GMS-backed
  implementations for no-op or GMS-free ones at the Koin module level, keeping
  upstream call sites intact so upstream merges stay cheap. `DESIGN_NOTES.md`
  maps the existing seams (the `:firebase-stubs` and `:haversine-stubs`
  modules, the unplugged `experimental` module, the no-op `LibIndex`);
  extend those seams rather than deleting upstream code. The FCM push stack
  is the one deliberate exception to the seam rule: push either registers a
  device token with Google or does not exist, so `PushMessaging`,
  `PushService`, and their call sites were deleted outright rather than
  no-opped; do not read that deletion as precedent for other strips. The
  google-services plugin and the Firebase SDKs are gone, so no
  `google-services.json` (dummy or real) is needed or consumed.
- **The speech engine is whisper.cpp, built from source.** Upstream's
  proprietary Cactus engine modules are replaced by Gravel's
  `:whisper`/`:whisper-native` pair; the engine is a pinned git submodule
  at `whisper-native/src/main/cpp/whisper.cpp`, so clone with
  `--recursive` (or run `git submodule update --init`) before building.
  Model weights are integrity-pinned runtime downloads, never committed;
  the re-pin procedure lives in `WhisperModelCatalog`.
- **Distinct branding: the app is Gravel.** applicationId is
  `com.anopticlabs.gravel`; the Kotlin `namespace` and source packages
  deliberately stay `coredevices.coreapp` so upstream merges stay cheap;
  only the applicationId and user-facing branding are renamed (asset and
  color details in `DESIGN_NOTES.md`). adb commands in upstream code
  comments (the debug receivers' KDocs, for example) still name
  `coredevices.coreapp` as the installed package; substitute
  `com.anopticlabs.gravel`. Nominative references to Pebble watches and
  Core Devices stay (they state compatibility and attribution); using their
  branding as ours does not.
- **Verify against the artifact.** De-Google changes are confirmed against the
  built APK (no `com.google.firebase`/`com.google.android.gms` classes, no
  unexpected network endpoints), not just against the source tree.
- **Minimal new dependencies.** Every new or upgraded dependency is vetted
  before code is written against it: actively maintained, current stable
  version, no known advisories, non-deprecated API surface, and a real need
  that the standard library or existing deps cannot meet.
- **Stay F-Droid deliverable.** Gravel is in F-Droid's main repository,
  so a change must not add non-free dependencies, dependencies from
  repositories F-Droid does not allow, or prebuilt binaries in the tree.
  `FdroidGuardrailsTest` in `:androidApp` replicates F-Droid's source
  scanner over the tracked tree, the whisper.cpp submodule included, minus
  the paths the F-Droid recipe removes (dependency lines in every gradle
  file, iOS-only and unplugged modules included; maven URLs; checked-in
  binaries; lockfile-less dependency manifests), and defines the envelope:
  it must stay green, and a change that needs it loosened is a change that
  breaks the listing. The recipe contract the tree assumes is in
  `DESIGN_NOTES.md` (F-Droid section).
- **Security first.** No known security flaw ships, however small; genuinely
  deferred issues are documented in `KNOWN_ISSUES.md` with rationale. Any
  cross-app interface (e.g. the third-party mic API) must be explicitly
  authorization-gated (signature/knownSigner-level permissions or equivalent),
  never openly exported. Vulnerability reports follow `SECURITY.md`.
- **Tests come with the change.** New logic (parsers, encoders, state
  machines, anything a later unrelated change could silently break) gets
  unit tests in the same change; prefer unit tests over instrumented ones.
  Android unit tests live in `src/androidHostTest` in the KMP modules and
  instrumented tests in `androidApp/src/androidTest`. Do not add tests under
  the pre-AGP 9 names `androidUnitTest` / `androidInstrumentedTest`: the
  current plugins silently ignore those directories.
- **Comments: intent and breakable constraints, up to a ceiling.** New and
  changed code gets a comment where its intent is not obvious from its name,
  and wherever it relies on a constraint someone could break (ordering,
  threading, a platform behaviour, a difference from upstream). It does not
  get comments that restate the code, repeat a fact stated elsewhere, or
  claim more than a test or a cited source backs. Also banned: comments
  naming a rejected alternative ("X, not Y"), ticket or issue references,
  and comments defending why a change is correct; that story goes in the
  commit message. Roughly one comment line per five lines of code across a
  change is a ceiling, not a target: fewer is fine, code that is clear from
  its names and structure needs no comment, and nothing is added to reach
  the ratio. The reason Gravel comments more than upstream is working
  conditions: this is one person's free-time project, worked on AI-assisted,
  mostly at weekends around a demanding day job, so whoever picks the code up
  next (the maintainer after a week of unrelated work, or an agent with no
  session history) starts cold, and most Gravel changes remove or substitute
  code Gravel did not write.
- **License compliance.** Keep `LICENSE` and copyright notices intact; changes
  are tracked through git history per GPLv3 §5. Gravel has no commercial
  license and no contributor license agreement.
- **Branch discipline.** Feature work happens on branches, each reviewed
  before merging to master. Commits are logical units that build and pass
  tests, with an imperative subject and a body explaining what and why.
- **Upstream syncs keep Gravel's side.** A merge or cherry-pick from upstream
  must not bring back what Gravel removed or replaced, including toolchain
  pins, the tag-packed versionCode (both below), Firebase/GMS dependencies,
  and upstream's `LICENSE-COMMERCIAL`. `README.md`, `CLAUDE.md`,
  `CONTRIBUTING.md`, and `SECURITY.md` are Gravel's own documents: on a
  conflict keep Gravel's version and carry over only upstream changes that
  apply to Gravel.

## Build and versioning

- **Any JDK 17 or newer builds the tree; there is no toolchain pin.**
  F-Droid's buildserver ships a single JDK (21) with Gradle toolchain
  provisioning switched off, so upstream's `jvmToolchain(17)` calls are
  removed and every JVM-flavoured target (android and `jvm()` alike) sets
  its bytecode target explicitly (`jvmTarget` / `compileOptions`, 17); a
  target without one follows the JDK running Gradle and emits different
  class files on 21 than on 17. `FdroidGuardrailsTest` fails on any
  toolchain pin (`jvmToolchain(` or `jvmToolchain {`, or a Java
  `toolchain {` block), which is what an upstream sync would bring back.
  CI builds on both 17 and 21.
- **versionCode is the commit count; versionName is `git describe --tags
  --first-parent HEAD`.** Upstream packs its newest reachable tag into
  versionCode; Gravel keeps the commit count because F-Droid's update
  checker and the version file below are built on it, and derives
  versionName from HEAD so a tag checkout reports exactly its own tag. The
  first-parent walk keeps upstream's tags, reachable through every sync
  merge, from describing Gravel commits, so release tags go on master's
  first-parent line (tag the release commit on master). Both are set lazily
  in `androidComponents.onVariants` so the git commands do not run during
  configuration.
- **A release commit bumps `androidApp/version.properties`.** F-Droid's
  update checker reads a tag's versionCode from that one-line file because
  it cannot count commits the way the build does. The commit that gets
  tagged (the same one that adds
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`) sets it
  to the commit count that commit will have, which is `git rev-list --count
  HEAD` plus one when committing on top of HEAD; between releases the file
  names the last release. The value checks fire only on a tag checkout,
  so after tagging and before pushing the tag run `./gradlew
  :androidApp:verifyReleaseVersionFile` (or the guardrail suite) on the
  tagged commit; otherwise a stale file is first rejected once the tag is
  public, by Gradle's `preBuild` (`VerifyReleaseVersionFile`),
  `FdroidGuardrailsTest`, and the tag-build step in CI alike. The test
  also requires `changelogs/<versionCode>.txt` to exist for the value the
  file names, on every commit.
- Gradle wrapper at the root (`./gradlew`); version catalog in
  `gradle/libs.versions.toml`. Debug build: `./gradlew
  :androidApp:assembleDebug`.

## Codebase

- Kotlin Multiplatform / Compose Multiplatform. Module source sets follow
  standard KMP (`src/commonMain/kotlin`, `src/androidMain/kotlin`); write
  shared code in `commonMain` and split out to `androidMain` only when the
  platform requires it.
- DI is Koin; navigation uses `androidx.navigation.compose`; logging uses
  Kermit; HTTP is Ktor (OkHttp engine); storage is Room.
- Compose resources are generated per module under
  `coreapp.<module>.generated.resources` (for example
  `coreapp.composeapp.generated.resources`).
- The module map is in `README.md` (Architecture).

## Code guidelines

- **Never use Kotlin `init` blocks.** They run on class creation, which
  happens during DI graph initialization and can block the main thread or
  run at an unexpected time. Use an explicit initialization method, called
  from somewhere sensible.
- **Minimal fixes.** Make the smallest change that fixes the problem. Do not
  bundle retry logic, defensive code, or other "improvements" with a
  root-cause fix.
- **Reuse existing abstractions at the correct scope.** Search for existing
  services, injectables, and utilities before writing new ones; `util` is
  the home for reusable generic utilities. Put state at the right DI scope:
  per-watch state belongs in per-watch services (for example
  `SystemService`), not in singletons such as `LibPebble3`.
- **Understand root causes before fixing.** No brute-force workarounds for
  symptoms, such as disabling every page transition to hide one broken
  animation.
- **Distinguish transient from deterministic failures.** Network errors are
  transient and may be retried; model or processing errors are
  deterministic and fail permanently, so they do not hold up a queue with
  retries.
- **Never blur state boundaries for marginal performance.** If the system
  defines states (transferring, complete), do not make something appear
  complete while work such as a file write is still running.
- **Use the existing permission flow.** Most code runs in background
  services where requesting permissions is not possible, and the app
  already prompts for missing permissions (see `RequiredPermissions` in
  `util`). Do not add manual `requestPermission()` calls in feature code;
  handle the denied case gracefully.
- **Keep changes single-purpose.** One concern per branch or PR.
- **Prefer simple architectures.** For example, keep a storage format
  unchanged and convert at the boundary rather than adding format dispatch
  or dual code paths.

## Android local release install

When asked to make a release build and install it on a local device:

1. Add or confirm `LOCAL_RELEASE_BUILD=true` in the root `local.properties`.
   This makes the release variant use the debug signing config, so it can
   install over an existing local/debug app without uninstalling.
2. Build from the repo root with `./gradlew :androidApp:assembleRelease
   --stacktrace --no-daemon`. Do not skip release lint unless the user
   explicitly asks.
3. Install over the existing app with `adb -s <device-id> install -r
   androidApp/build/outputs/apk/release/androidApp-release.apk`. Do not
   uninstall first unless explicitly requested.
4. Launch and verify with logcat:
    - `adb -s <device-id> logcat -c`
    - `adb -s <device-id> shell monkey -p com.anopticlabs.gravel -c
      android.intent.category.LAUNCHER 1`
    - wait long enough for `PebbleService` to start, then check for
      `FATAL EXCEPTION`, `ClassNotFoundException`, `Room cannot verify`, and
      `Process: com.anopticlabs.gravel`.

Release builds are minified. If a release-only crash appears, check the R8
keep rules before changing app logic.
