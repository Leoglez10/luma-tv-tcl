#!/bin/bash
# Luma on your TV, from this computer: build, install, make it the home screen, and undo that.
#   ./luma.sh [-s DEVICE] build | install | home | widgets | keys | restore | status
# DEVICE is the TV's adb address (192.168.1.50:5555) or serial. Default: $LUMA_DEVICE, else the only device adb sees.
set -euo pipefail
cd "$(dirname "$0")"
DEVICE="${LUMA_DEVICE:-}"
if [ "${1:-}" = "-s" ]; then DEVICE="$2"; shift 2; fi
ACTION="${1:-status}"
LUMA="dev.luma.tv/.MainActivity"
# The only packages this script ever switches off: stock TV launchers, and Google TV's setup "recovery" home,
# which outranks any other home screen once the launcher is off.
KNOWN="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.google.android.tungsten.setupwraith"

die() { echo "luma: $*" >&2; exit 1; }
pick_device() {
    if [ -n "$DEVICE" ]; then
        case "$DEVICE" in *:*) adb connect "$DEVICE" >/dev/null 2>&1 || true ;; esac
        return
    fi
    local list; list="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
    [ -n "$list" ] || die "no se encontró ninguna TV. Activa la depuración ADB en la TV y luego: adb connect <IP de la TV>:5555"
    [ "$(echo "$list" | wc -l | tr -d ' ')" = "1" ] || die "hay varios dispositivos conectados; elige uno con -s DISPOSITIVO:
$list"
    DEVICE="$list"
}
tv() { adb -s "$DEVICE" shell "$@" | tr -d '\r'; }
home_now() { tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1; }
state_file() { mkdir -p .local; echo ".local/home-$(tv getprop ro.serialno | tr -cd 'A-Za-z0-9').txt"; }
installed() { tv pm list packages "$1" | grep -qx "package:$1"; }
show_home() { tv dumpsys power | grep -q 'mWakefulness=Awake' && tv input keyevent KEYCODE_HOME || true; } # never wakes a sleeping TV

case "$ACTION" in
build)
    bash build.sh ;;
install)
    pick_device
    [ -f dist/luma-tv.apk ] || bash build.sh
    adb -s "$DEVICE" install --no-incremental -r dist/luma-tv.apk ;;
home)
    pick_device
    installed dev.luma.tv || die "Luma aún no está instalada: ./luma.sh install"
    state="$(state_file)"
    if [ ! -s "$state" ]; then echo "original=$(home_now)" > "$state"; fi
    tv cmd package set-home-activity --user 0 "$LUMA" >/dev/null
    for _ in 1 2 3 4; do
        current="$(home_now)"
        [ "$current" = "$LUMA" ] && break
        pkg="${current%%/*}"
        case " $KNOWN " in
        *" $pkg "*)
            echo "Desactivando $pkg, que la TV pone por encima de cualquier otra pantalla de inicio."
            tv pm disable-user --user 0 "$pkg" >/dev/null
            echo "disabled=$pkg" >> "$state"
            tv cmd package set-home-activity --user 0 "$LUMA" >/dev/null ;;
        *) die "la TV sigue abriendo $current en Inicio, y luma.sh no desactiva apps desconocidas. No se cambió nada más; ./luma.sh restore lo deshace." ;;
        esac
    done
    [ "$(home_now)" = "$LUMA" ] || die "la TV no aceptó Luma como pantalla de inicio. ./luma.sh restore deshace los cambios."
    show_home
    echo "Luma es la pantalla de inicio. Para deshacerlo en cualquier momento: ./luma.sh restore" ;;
widgets)
    # The Home widgets work without this; it switches on what a TV remote makes tedious to allow by hand.
    pick_device
    installed dev.luma.tv || die "Luma aún no está instalada: ./luma.sh install"
    tv pm grant dev.luma.tv android.permission.READ_TV_LISTINGS && echo "A continuación: Seguir viendo de tus apps."
    tv cmd notification allow_listener dev.luma.tv/dev.luma.tv.MediaListener && echo "Reproduciendo: la música que suena en la TV."
    tv appops set dev.luma.tv GET_USAGE_STATS allow && echo "A continuación: apps usadas recientemente."
    echo "Puedes deshacer cualquiera en la TV: Ajustes → Apps → Acceso especial de apps, o Ajustes → Apps → Luma TV → Permisos." ;;
keys)
    # The remote's app buttons (KeyService). Keeps any other accessibility service already enabled.
    pick_device
    installed dev.luma.tv || die "Luma aún no está instalada: ./luma.sh install"
    # TCL's boot manager otherwise forbids binding the service; other TVs have no such op.
    tv appops set dev.luma.tv APP_AUTO_START allow 2>/dev/null || echo "Aviso: no se pudo permitir el arranque automático; en TVs TCL los botones pueden no responder."
    service="dev.luma.tv/dev.luma.tv.KeyService"
    current="$(tv settings get secure enabled_accessibility_services)"
    case "$current" in null|"") current="" ;; esac
    case ":$current:" in *":$service:"*) ;; *) tv settings put secure enabled_accessibility_services "${current:+$current:}$service" ;; esac
    tv settings put secure accessibility_enabled 1
    echo "Botones del control: YouTube abre SmartTube; Prime Video, Netflix y ViX abren su app; el de Apple TV abre Spotify." ;;
restore)
    pick_device
    state="$(state_file)"
    if [ -s "$state" ]; then
        disabled="$(sed -n 's/^disabled=//p' "$state")"; original="$(sed -n 's/^original=//p' "$state" | head -n 1)"
    else # set up by hand or on another computer: switch every known launcher back on
        disabled=""; original=""
        for pkg in $KNOWN; do installed "$pkg" && tv pm list packages -d "$pkg" | grep -qx "package:$pkg" && disabled="$disabled $pkg"; done
    fi
    service="dev.luma.tv/dev.luma.tv.KeyService";current="$(tv settings get secure enabled_accessibility_services)"
    case ":$current:" in *":$service:"*) rest="$(echo ":$current:" | sed "s#:$service:#:#; s#^:##; s#:\$##")"
        if [ -n "$rest" ]; then tv settings put secure enabled_accessibility_services "$rest"; else tv settings delete secure enabled_accessibility_services >/dev/null; fi
        echo "Botones del control: servicio de Luma desactivado." ;; esac
    for pkg in $disabled; do echo "Reactivando $pkg."; tv pm enable --user 0 "$pkg" >/dev/null || true; done
    if [ -n "$original" ] && [ "$original" != "$LUMA" ] && [[ "$original" =~ ^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$ ]]; then
        tv cmd package set-home-activity --user 0 "$original" >/dev/null || true
    fi
    rm -f "$state"
    show_home
    echo "Inicio ahora abre $(home_now). Luma sigue instalada (adb uninstall dev.luma.tv la elimina)." ;;
status)
    pick_device
    echo "TV:     $(tv getprop ro.product.manufacturer) $(tv getprop ro.product.model), Android $(tv getprop ro.build.version.release)"
    echo "Luma:   $(tv dumpsys package dev.luma.tv | sed -n 's/^ *versionName=//p' | head -n 1 | grep . || echo 'no instalada')"
    echo "Inicio: $(home_now)" ;;
*)
    die "comando desconocido '$ACTION'. Usa build, install, home, widgets, keys, restore o status." ;;
esac
