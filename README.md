# TCL TV Launcher

Custom lightweight home screen for a TCL Google TV (`G10_4K_GB_NF`, Android 12), replacing the stock Google TV launcher to remove ads and reduce RAM usage.

## Status

- [x] Debloat and optimization notes — see [`docs/tv-optimization-notes.md`](docs/tv-optimization-notes.md)
- [ ] Launcher app (design pending)

## Device access

```bash
adb connect <tv-ip>:5555
```

## Recovery

If the custom launcher fails and the TV has no home screen:

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
```
