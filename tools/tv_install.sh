#!/usr/bin/env bash
# tools/tv_install.sh [APK] — installs a test build on the TV and compiles it ahead, so measurements are of the
# compiled app (tools/compiled.sh). tools/tv_install.sh --compile: only the compile step (install-on-tv.sh uses it).
#  - Not incremental: build.sh signs v4 (.idsig), so `adb install` streams the APK and Android skips compiling it.
#  - This TV (pm.dexopt.cmdline=verify) turns `compile -m speed` into speed-profile, which compiles only what the app's
#    usage profile lists: right after an install the profile is empty and the result is "verify" (nothing compiled).
#    So the home screen runs first (start, the tabs, back) until ART has saved a profile, then it's compiled.
cd "$(dirname "$0")/.." || exit 1
A="adb -s ${TV:-192.168.1.69:5555}"
home() { if [ "${AS_APP:-0}" = 1 ]; then $A shell am start -n io.vyro.tv/.HomeActivity >/dev/null; else $A shell input keyevent 3; fi; }
if [ "$1" != --compile ]; then
  apk=${1:-VYRO.apk}
  $A install -r --no-incremental "$apk" | grep -q Success || { echo "install failed: $apk" >&2; exit 1; }
fi
for try in 1 2 3; do
  $A shell am force-stop io.vyro.tv; home; sleep 12
  # Home's tabs once each, so their code is in the profile too (the benchmarks visit them).
  for k in 19 19 19 20 19 22 s 22 s 22 s 21 21 21 3; do
    if [ "$k" = s ]; then sleep 3; elif [ "$k" = 3 ]; then home; sleep 0.4; else $A shell input keyevent "$k"; sleep 0.4; fi
  done
  sleep $((20 + 10 * try))   # ART saves the profile some 40 s after the start
  $A shell cmd package compile -m speed-profile -f io.vyro.tv | grep -q Success || { echo "compile failed" >&2; exit 1; }
  bash tools/compiled.sh 2>/dev/null && break
  [ $try = 3 ] && { bash tools/compiled.sh; exit 1; }
done
$A shell am force-stop io.vyro.tv; home   # the compiled code from the next start
