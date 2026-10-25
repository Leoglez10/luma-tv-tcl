# TCL TV Launcher

Custom lightweight home screen for a TCL Google TV (`G10_4K_GB_NF`, Android 12), replacing the stock Google TV launcher to remove ads and reduce RAM usage.

## Status

- [x] Debloat and optimization notes — see [`docs/tv-optimization-notes.md`](docs/tv-optimization-notes.md)
- [x] Device analysis (2026-10) — see [Work log](#work-log)
- [x] Launcher scaffold + Home UI v1 (clock, greeting, app row)
- [x] Reference-based light home with content tabs, hero/posters and an app banner dock
- [ ] Install, set as HOME, rollback script
- [ ] RAM before/after measurement

## Design

A light, reference-based home inspired by the composition in [the user's image](docs/design/reference-home.png), not an exact Apple TV reproduction:
- **Header:** “Para ti”, focusable Explorar/Películas/Series tabs, round Wi-Fi/Entradas/Ajustes controls and a small clock.
- **Content:** one wide backdrop hero followed by portrait posters, title captions and the hero's media type, first known genre and year. Tabs select TMDB trending, popular movies and popular TV respectively.
- **Dock:** a rounded translucent panel of wide installed-app banners, with icon and label fallback. A soft gradient suggests frosted glass without a live blur or extra dependencies.

Initial focus is the first dock app. Left/right stays in each row (header tabs lead to system controls); Up goes dock → first content card → selected tab, and Down returns content → first dock app. Without content, the dock links directly to tabs. Dark focus outlines remain inside padded card margins; cards do not scale into neighboring content. System controls appear only when resolvable.

Without credentials or usable cached/network content, the content area stays empty while tabs, clock, dock and system controls remain usable. Cached TMDB content can remain visible offline. No greeting or oversized clock is shown.

**Why a custom launcher:** the stock Google TV launcher (`launcherx` + `coreservices`) uses ~252 MB on a TV with 1.8 GB of RAM, which pushes the system into swap. It also shows ads and recommendations. Replacing it frees an estimated 200–300 MB together with the assistant.

**What is lost by design:** Google TV recommendations, watchlist, "Continue watching", home ads, and voice search if the assistant (`katniss`) is disabled.

**What keeps working:** all installed apps and their sessions, Play Store, system Settings, HDMI inputs, Wi-Fi, Bluetooth, remote, Chromecast, and the screensaver.

## Build

```bash
cd launcher
ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
# APK: launcher/app/build/outputs/apk/debug/app-debug.apk
```

- The JDK is selected by `launcher/gradle/gradle-daemon-jvm.properties` (`toolchainVersion=21`, Gradle 8.8+): Gradle runs its daemon on any installed JDK 21, so no absolute path is committed. If Gradle reports that no JDK 21 is found, install one (`brew install --cask corretto@21`, or any JDK 21 into `~/.gradle/jdks`) or run with `JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- Plain `android.app.Activity` in Java, no AndroidX or runtime dependencies. A launcher stays in memory permanently, so every dependency costs RAM.
- `minSdk 31` (the TV runs Android 12), `targetSdk 34`, `compileSdk 36`.
- `launcher/local.properties` (SDK path) and `launcher/tmdb.properties` (TMDB credentials) are gitignored and must be created locally. Never commit the TMDB key.
- TMDB credentials: create `launcher/tmdb.properties` with two keys (get them from your free TMDB account, Settings > API):

  ```properties
  tmdb.api.key=<v3 API key>
  tmdb.api.token=<v4 read access token>
  ```

  The build reads them into `BuildConfig`. The v4 token is sent as a `Bearer` header; the v3 key is only used if no token is set. If the file is missing the build still succeeds and the poster rows stay hidden.

Unit tests: `./gradlew :app:testDebugUnitTest`. The only test dependencies are JUnit and `org.json:json` (Android's `org.json` is stubbed on the JVM); the app itself has no runtime dependencies.

## Work log

### 2026-10-25 — Device analysis

- TV is now at `192.168.x.x:5555` (it was `.2` in September).
- Memory: 1.8 GB total, ~1.37 GB used. Largest consumers: `launcherx` 165 MB, `launcherx:coreservices` 87 MB, `vending` 140 + 123 MB, `gms` 119 MB, `dreamx` (screensaver) 84 MB, `katniss:interactor` 78 MB.
- A firmware update reverted part of the September cleanup:
  - `com.tcl.waterfall.overseas` (ACR + home ads) was re-enabled. **Re-disabled** with `pm disable-user --user 0`; 18 packages disabled again.
  - Apple TV, Kick and PrendeTV were reinstalled. Pending decision on whether to remove them again.
- REA (reverse engineering) was considered and not used: nothing needs decompiling; ADB inspection answers every question, and the stock launcher is being replaced, not reproduced.

### 2026-10-25 — Launcher scaffold and Home UI v1

- Gradle project under `launcher/`, package `ar.gentleman.tvlauncher`.
- `MainActivity` registers `HOME` + `DEFAULT` + `LEANBACK_LAUNCHER`, so Android offers it as the home screen.
- Home UI: clock updated every minute, Spanish greeting by time of day, horizontal row of launchable TV apps (excluding itself and disabled apps), focus scale animation, D-pad navigation, empty-state message.
- Works fully offline; no network code yet.
- Greeting logic tested first (RED → GREEN), including hour boundaries.
- Known caveat: AGP 8.7.2 is not tested against compileSdk 36; the warning is suppressed in `gradle.properties`.

### 2026-10-25 — Review fixes

Fixes for the native review of the Home UI v1 commit (`417d5cb`):

- Added fixed "Entradas" (`com.android.tv.action.VIEW_INPUTS`) and "Ajustes" (`android.settings.SETTINGS`) tiles at the end of the row, shown only when the device resolves them — WHY: HDMI inputs and Settings were unreachable once the stock launcher is replaced (spec S4).
- Added a `<queries>` block to the manifest — WHY: with `targetSdk 34` package visibility hides apps and system activities from `resolveActivity` unless declared.
- Back no longer finishes the HOME activity — WHY: a launcher that exits on Back leaves the user without a home screen.
- App row is rebuilt in `onResume` (focus kept on the same tile when it still exists) — WHY: the row was only built in `onCreate`, so installs and uninstalls were never reflected.
- Clock ticks start in `onResume` and stop in `onPause` — WHY: avoids ticking while the home is in the background and keeps the time correct on return.
- Launch failures show a "No se pudo abrir <app>" toast and catch `ActivityNotFoundException`/`SecurityException` — WHY: a dead tile used to fail silently.
- Removed machine-specific `org.gradle.java.home` and added `gradle/gradle-daemon-jvm.properties` with `toolchainVersion=21` — WHY: the committed absolute path only worked on one machine, and the system JDK 26 is not accepted by this Gradle/AGP combination.
- Extracted app filtering/sorting into the Android-free `AppCatalog` and covered it with `AppCatalogTest` (written first, RED → GREEN: excludes own package and disabled apps, case-insensitive sort, de-duplication, empty input) — WHY: this logic had no tests and decides what the user can open.

### 2026-10-25 — TMDB poster rows

- Three poster rows ("Tendencias", "Películas populares", "Series populares") below the app row, D-pad focusable with the same ~1.08 focus scale, inside a vertically scrolling home — WHY: spec S1/S2, Apple TV style content rows.
- Selecting a poster only shows a Toast with the title — WHY: the spec does not define what a poster should open (spec gap, to decide later).
- No image or HTTP library (`HttpURLConnection`, `org.json`, `BitmapFactory`, `LruCache`, 3-thread pool) — WHY: a launcher stays resident in 1.8 GB of RAM, and OkHttp/Glide/Gson would add size and memory; the APK grew from ~61 KB to ~84 KB.
- Credentials go through `BuildConfig` from the gitignored `tmdb.properties` — WHY: keeps the key out of git and lets the build succeed (rows hidden) when the file is absent.
- The read-only TMDB key ends up inside the APK — acceptable for a private personal build; do not publish or share the APK.
- JSON is cached in `getCacheDir()` and rows are refreshed at most every 6 hours (not on every resume), with a 5-minute retry gap when offline — WHY: avoids hammering TMDB and shows rows instantly on start.
- No credentials, no network, HTTP error or timeout (8 s) hides the affected row silently; clock, greeting and app row are untouched — WHY: spec S3.
- Parsing, URL building, credentials decision, refresh policy and image sampling are pure Java and were written test-first (RED → GREEN).

### 2026-10-25 — Reference-based home redesign (T3c)

- Replaced the dark greeting/clock layout with the reference's light header/content/dock composition — WHY: the user's “estilo apple tv” clarification and supplied image supersede the earlier interpretation.
- Connected the existing HomeTab, parser and metadata work to one visible content row per tab. The first card uses a backdrop (poster fallback), and only real available metadata is shown — WHY: match the reference without inventing providers or season counts.
- Moved system actions to accessible round header buttons, added Wi-Fi settings, and used activity/application banners with icon fallback in the app dock — WHY: keep the reference's shape while retaining access to installed apps, Settings and HDMI inputs.
- Kept app filtering, refresh/cache policies, launch-error Toasts, lifecycle-bound minute ticks and Back as a no-op. Explicit focus links and padded outlines replace card scaling — WHY: predictable remote navigation without clipped highlights.
- Preserved the previous worker's pure-Java tests/classes; no new runtime dependency. JVM tests cover data behavior; appearance, D-pad scrolling, system intent resolution and app launch checks still require the TV. Content selection still shows only a title Toast, not playback.
- Validation: 29 focused JVM tests passed; the full 53-test suite and debug assembly passed both without `tmdb.properties` and after restoring it (checksum equality verified). The final local APK includes the restored credentials; do not publish it.
- Device installation, HOME recovery scripts and RAM measurements are outside this visual unit.

## Device access

```bash
adb connect <tv-ip>:5555
```

## Recovery

If the custom launcher fails and the TV has no home screen:

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
```

The full install and rollback script will be added with the install step.
