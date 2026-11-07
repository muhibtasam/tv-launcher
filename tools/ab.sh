#!/usr/bin/env bash
# Dev helper: tools/ab.sh OLD.apk NEW.apk [rounds] — installs the two builds in turn and runs the same scenarios on
# each (tools/perf.sh), for a fair before/after: single runs vary a lot on the TV. Build OLD from a git worktree with
# your config.properties and freemytv.keystore copied in. Each install is compiled ahead (tools/tv_install.sh).
cd "$(dirname "$0")/.."
A="adb -s ${TV:-192.168.1.69:5555}"; S=tools/tv_shot.sh; P=tools/perf.sh
go() { bash $S "$1" x >/dev/null; }
run() {
  go "3 s s s 20";          bash $P "22 22 22 22 21 21 21 20 22 22 21 19 22 22 19" "$1 home-cards"
  go "3 s s s";             bash $P "22 22 22 20 22 22 21 21 20 22 22 19 19" "$1 apps-search"
  go "3 s s 20 20 20 20 s"; bash $P "22 22 21 21 19 20" "$1 home-drawer"
  go "3 s s 20 19 22 s s 20 21 s s 20 s"; bash $P "22 22 22 22 20 22 22 22 21 21 20 22 22" "$1 movies"
}
for r in $(seq 1 "${3:-2}"); do
  for v in old new; do
    apk=$1; [ $v = new ] && apk=$2
    st=$(bash tools/tv_install.sh "$apk") || exit 1; echo "$v: $st"   # compiled, as the owner's install is
    $A shell input keyevent 3 && sleep 10
    run "$v#$r"
  done
done
