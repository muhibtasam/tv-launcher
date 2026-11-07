#!/usr/bin/env bash
# Dev helper: tools/trace.sh "keys" out.pftrace — a 10 s Perfetto trace (gfx, view, hwui, scheduling) of the home
# screen while the keys are pressed (adb keycodes, "s" = 1 s pause). Open it at ui.perfetto.dev.
TV=${TV:-192.168.1.69:5555}; A="adb -s $TV"
cfg='buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config { atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "hwui" atrace_apps: "io.vyro.tv:home" ftrace_events: "sched/sched_switch" } } }
data_sources { config { name: "linux.process_stats" } }
duration_ms: 10000'
$A shell rm -f /data/misc/perfetto-traces/vyro.pftrace
echo "$cfg" | $A shell perfetto --txt -c - -o /data/misc/perfetto-traces/vyro.pftrace --background >/dev/null
sleep 1.5
for k in $1; do if [ "$k" = s ]; then sleep 1; else $A shell input keyevent "$k"; sleep 0.25; fi; done
sleep 9
$A pull /data/misc/perfetto-traces/vyro.pftrace "${2:-vyro.pftrace}" >/dev/null && echo "trace: ${2:-vyro.pftrace}"
