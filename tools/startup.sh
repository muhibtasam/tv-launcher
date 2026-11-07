#!/usr/bin/env bash
# startup.sh label n: cold = home process forked → first frame (the app's startup timeline, one clock);
# warm = am start -W of HOME back from Settings.
A="adb -s ${TV:-192.168.1.69:5555}"
# Refuses an interpreted install: its cold start is ~45% slower than the compiled app the owner runs.
bash "$(dirname "$0")/compiled.sh" || exit 1
H="-W -a android.intent.action.MAIN -c android.intent.category.HOME"
[ "${AS_APP:-0}" = 1 ] && H="-W -n io.vyro.tv/.HomeActivity"   # installed alongside: opened as an app
for i in $(seq 1 ${2:-3}); do
  $A logcat -c; $A shell am force-stop io.vyro.tv
  if [ "${AS_APP:-0}" = 1 ]; then $A shell am start -n io.vyro.tv/.HomeActivity >/dev/null; else $A shell input keyevent 3; fi; sleep 7
  L=$($A logcat -d -s VYRO)
  p=$(echo "$L" | grep -m1 'home process started' | sed -E 's/.* ([0-9.]+) s after power-on.*/\1/')
  f=$(echo "$L" | grep -m1 'first frame (home screen)' | sed -E 's/.* ([0-9.]+) s after power-on.*/\1/')
  pss=$($A shell dumpsys meminfo io.vyro.tv:home | grep 'TOTAL PSS' | awk '{print $3}')
  $A shell am start -n com.android.tv.settings/.MainSettings >/dev/null; sleep 4
  w=$($A shell am start $H | grep -E 'TotalTime' | awk '{print $2}'); sleep 3
  echo "$1 #$i cold $(echo "($f - $p)*1000/1" | bc)ms warm ${w}ms pss=${pss}KB"
done
