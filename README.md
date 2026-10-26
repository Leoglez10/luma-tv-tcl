# Luma TV — TCL edition

A calm, ad-free home screen for a TCL Google TV (`G10_4K_GB_NF`, Android 12), built on a fork of
[Luma](https://github.com/0xProbabillity/luma-tv) and adapted to this household: all in Spanish, the house lights,
the remote's app buttons and a few layout changes. No ads, no sponsored rows, no tracking.

The app lives in [`luma/`](luma). The plan, decisions and verification notes are in
[`docs/luma-fork-plan.md`](docs/luma-fork-plan.md).

## What it does

Home has three pages of widgets above a row of favourite apps:

| Page | Widgets |
|---|---|
| 1 | **A continuación** (one card per series, at most two per app, ViX left out; **+** opens up to 12) · **Clima** (OK switches Zapopan ↔ Guadalajara, hold OK for hours ↔ days) |
| 2 | **Luces** (Entrada, Sala, Todas, Colores) · **Reproduciendo** (Spotify and other music apps, with a full-screen player) |
| 3 | **Entradas** (HDMI and live TV; **Más** opens every input) · **Control** (network, picture and sound, accessories, settings) |

- **Spanish everywhere:** interface, dates, city names and `luma.sh` messages.
- **Lights:** the household's WiZ and CozyLife bulbs over the local network, with no vendor app or cloud. Rooms switch
  on and off, hold OK steps brightness, and the **Colores** panel has whites (2700–6500 K), eight colours,
  brightness and profiles (Película, Lectura, Cena, Relajado, Fiesta, Apagar).
- **Full-screen player:** the expand button in Reproduciendo shows the song over its blurred artwork, with a big cover,
  live progress and large controls.
- **Remote app buttons:** YouTube, Prime Video, Netflix and ViX open their apps from anywhere; the Apple TV button opens
  Spotify; TCL Channel works again.
- **Quiet:** no navigation sounds; only a soft sound when an app opens (Ajustes → *Sonido al abrir apps*).

Upstream Luma features that remain: living wallpapers, light by day and dark at night, hold OK on an app to move or
hide it, and the Apps tab with everything installed.

## Install

Requirements: the TV with network debugging on, a Mac with `adb`, a JDK 17 or newer and the Android SDK build-tools
`36.0.0` (Luma builds without Gradle).

```bash
adb connect 192.168.x.x:5555          # the TV; its address can change
cd luma
ANDROID_SDK_ROOT=<sdk> LUMA_JDK=<jdk> bash build.sh   # → dist/luma-tv.apk
./luma.sh -s 192.168.x.x:5555 install
./luma.sh -s 192.168.x.x:5555 home     # make Luma the home screen
./luma.sh -s 192.168.x.x:5555 keys     # the remote's app buttons
./luma.sh -s 192.168.x.x:5555 widgets  # Up Next and Now Playing permissions
```

`build.sh` expects `$SDK/platforms/android-34`; any newer `android.jar` linked there works.

**This TV in particular:**
- The previous launcher `ar.gentleman.tvlauncher` is disabled; while it was enabled, every Luma update made the TV fall
  back to it.
- The remote's app buttons need TCL's `APP_AUTO_START` app-op, which `./luma.sh keys` grants.
- TCL Channel needs `com.tcl.waterfall.overseas` enabled.

## Going back

```bash
adb shell pm enable ar.gentleman.tvlauncher   # only if you want the previous launcher back
cd luma && ./luma.sh restore                    # returns HOME and removes the button service
```

## Development

Plain Java, no AndroidX and no runtime dependencies: a launcher stays in memory all the time. The logic that needs
no Android runs as plain JVM checks from `luma/`:

```bash
javac -d build/test src/dev/luma/tv/Devices.java tools/DevicesTest.java && java -cp build/test dev.luma.tv.DevicesTest
javac -cp json.jar -d build/test src/dev/luma/tv/Wiz.java tools/WizTest.java && java -cp build/test:json.jar dev.luma.tv.WizTest
javac -cp json.jar -d build/test src/dev/luma/tv/Wiz.java src/dev/luma/tv/Cozy.java tools/CozyTest.java && java -cp build/test:json.jar dev.luma.tv.CozyTest
javac -d build/test src/dev/luma/tv/WatchNext.java tools/WatchNextTest.java && java -cp build/test dev.luma.tv.WatchNextTest
```

`json.jar` is `org.json` (Android's own copy is stubbed on a desktop JVM).

## Upstream

`luma/` is a `git subtree` of [0xProbabillity/luma-tv](https://github.com/0xProbabillity/luma-tv) (MIT, see
[`luma/LICENSE`](luma/LICENSE)). To bring in upstream changes:

```bash
git subtree pull --prefix=luma https://github.com/0xProbabillity/luma-tv.git main
```

Merge pull requests with a merge commit, not a squash, so the subtree history stays intact.

## Repository

| Path | What |
|---|---|
| [`luma/`](luma) | The home screen running on the TV |
| [`docs/`](docs) | Plan, TV optimization notes, measurements |
| [`launcher/`](launcher) | **Legacy:** the first custom launcher, kept for reference ([README](launcher/README.md)) |
| [`scripts/`](scripts) | Install and HOME-recovery scripts for the legacy launcher |
