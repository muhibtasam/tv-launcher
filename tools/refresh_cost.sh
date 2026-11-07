#!/usr/bin/env bash
# Dev helper: tools/refresh_cost.sh label n — CPU the home screen's background threads spend after returning to Home
# from another app (65 s later, so the app list is due), and the dock's log line. Needs the Perfetto Python package
# (python3 -m pip install perfetto) for the trace's numbers.
A="adb -s ${TV:-192.168.1.69:5555}"
bash "$(dirname "$0")/compiled.sh" || exit 1   # an interpreted install measures the wrong build
cfg='buffers { size_kb: 16384 }
data_sources { config { name: "linux.ftrace" ftrace_config { ftrace_events: "sched/sched_switch" } } }
data_sources { config { name: "linux.process_stats" } }
duration_ms: 8000'
for i in $(seq 1 ${2:-3}); do
  $A shell am start -n com.android.tv.settings/.MainSettings >/dev/null; sleep 65; $A logcat -c
  $A shell rm -f /data/misc/perfetto-traces/rc.pftrace
  echo "$cfg" | $A shell perfetto --txt -c - -o /data/misc/perfetto-traces/rc.pftrace --background >/dev/null; sleep 1
  $A shell input keyevent 3; sleep 9
  $A pull /data/misc/perfetto-traces/rc.pftrace /tmp/rc.pftrace >/dev/null
  cpu=$(${PY:-python3} - <<'PY'
from perfetto.trace_processor import TraceProcessor
tp=TraceProcessor(trace="/tmp/rc.pftrace")
r=list(tp.query("""select round(sum(ts.dur)/1e6) ms from thread_state ts join thread t using(utid) join process p using(upid)
 where p.name like 'io.vyro.tv:home%' and t.name='vyro-bg' and ts.state='Running'"""))
print(r[0].ms)
PY
)
  echo "$1 #$i background CPU ${cpu} ms  $($A logcat -d -s VYRO | grep -m1 -oE 'app list [0-9]+ ms[^,]*')"
done
