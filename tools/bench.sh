#!/usr/bin/env bash
# Dev helper: tools/bench.sh "skip masks" — what each layer costs the GPU when the whole screen is redrawn (as in a
# background crossfade), measured at rest on Home so runs are comparable (no key timing). For each mask (bits: 1 sky,
# 2 hero, 4 status, 8 platter, 16 tiles; 0 = everything drawn), frames for 4 s with the background redrawn every frame.
TV=${TV:-192.168.1.69:5555}; A="adb -s $TV"
bash "$(dirname "$0")/compiled.sh" || exit 1   # an interpreted install measures the wrong build
for mask in ${1:-0 1 2 4 8 16}; do
  $A shell settings put global freemytv_bench_force 1; $A shell settings put global freemytv_bench_skip "$mask"
  $A shell am start -n com.android.tv.settings/.MainSettings >/dev/null; sleep 2; $A shell input keyevent 3; sleep 3
  PID=$($A shell pidof io.freemytv:home | tr -d '\r')
  $A shell dumpsys gfxinfo "$PID" reset >/dev/null; sleep 4
  $A shell dumpsys gfxinfo "$PID" framestats | python3 -c '
import sys
hdr=None; rows=[]
for line in sys.stdin:
    p=line.strip().rstrip(",").split(",")
    if p[0]=="Flags": hdr=p; continue
    if hdr and len(p)==len(hdr) and p[0].lstrip("-").isdigit():
        r=dict(zip(hdr,map(int,p)))
        if r["Flags"]==0: rows.append(r)
rows.sort(key=lambda r: r["IntendedVsync"])
gap=sorted((b["IntendedVsync"]-a["IntendedVsync"])/1e6 for a,b in zip(rows,rows[1:]))
gpu=sorted((r["GpuCompleted"]-r["IssueDrawCommandsStart"])/1e6 for r in rows if r.get("GpuCompleted",0)>0)
med=lambda a: a[len(a)//2] if a else 0
print(f"skip={sys.argv[1]:>2}: frames {len(rows)}  fps {1000/med(gap) if gap else 0:.0f} (gap p50 {med(gap):.1f} ms)  gpu p50 {med(gpu):.1f} ms p90 {gpu[int(len(gpu)*.9)] if gpu else 0:.1f}")' "$mask"
done
$A shell settings delete global freemytv_bench_force >/dev/null; $A shell settings delete global freemytv_bench_skip >/dev/null
$A shell am start -n com.android.tv.settings/.MainSettings >/dev/null; sleep 1; $A shell input keyevent 3
