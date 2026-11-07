#!/usr/bin/env bash
# Dev helper: tools/tv_shot.sh "keys" name — press adb keycodes on the TV ("s" = 1 s pause), then save a 960-wide
# screenshot to /tmp/<name>.png. TV address: $TV (default 192.168.1.69:5555).
A="adb -s ${TV:-192.168.1.69:5555}"
# AS_APP=1: Free My TV isn't the TV's home screen (installed alongside): key 3 opens it as an app instead of Home.
for k in $1; do
  if [ "$k" = s ]; then sleep 1
  elif [ "$k" = 3 ] && [ "${AS_APP:-0}" = 1 ]; then $A shell am start -n io.freemytv/.HomeActivity >/dev/null; sleep 0.4
  else $A shell input keyevent "$k"; sleep 0.4; fi
done
sleep 1.5
$A exec-out screencap -p > /tmp/tv-full.png && sips -Z 960 /tmp/tv-full.png --out "/tmp/$2.png" >/dev/null && echo shot
