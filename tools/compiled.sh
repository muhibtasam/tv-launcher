#!/usr/bin/env bash
# tools/compiled.sh — is the launcher on the TV compiled ahead (ART)? Prints its dexopt status; exit 1 if it isn't.
# A plain `adb install -r` streams the APK (incremental: build.sh makes an .idsig) and Android leaves it interpreted ("run-from-apk"): cold start measured 440 ms instead of ~300 ms
# (2026-11-01), so a benchmark of such an install measures the wrong build. Fix: tools/tv_install.sh, or
# adb shell cmd package compile -m speed -f io.vyro.tv
A="adb -s ${TV:-192.168.1.69:5555}"
s=$($A shell dumpsys package dexopt | grep -F -A3 '[io.vyro.tv]' | grep -m1 -oE 'status=[^]]*' | cut -d= -f2)
case "$s" in
  speed|speed-profile|everything|everything-profile) echo "compiled: $s"; exit 0 ;;
  *) echo "NOT compiled: ${s:-unknown} (run tools/tv_install.sh APK, or: adb shell cmd package compile -m speed -f io.vyro.tv)" >&2; exit 1 ;;
esac
