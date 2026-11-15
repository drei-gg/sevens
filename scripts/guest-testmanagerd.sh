#!/bin/bash
# Runs IN THE VM after a device test run (guest-device.sh calls it). An XCUITest run that ends
# badly can leave the iPhone's testmanagerd spinning a whole core for hours: on 13 Nov 2026 it
# held the phone at thermal state Serious, drawing ~4 W over USB, with nothing running. This
# samples the phone's CPU for a few seconds and kills testmanagerd if it is still busy. launchd
# starts it again for the next test, so the kill is safe.
# usage: bash scripts/guest-testmanagerd.sh <udid>
# env: TESTMANAGERD_MAX_CPU (default 30, percent of one core)
set -uo pipefail
udid=${1:?udid}; max=${TESTMANAGERD_MAX_CPU:-30}
# Another xcodebuild still talking to the phone owns testmanagerd; leave it alone.
if pgrep -x xcodebuild >/dev/null; then echo "testmanagerd: xcodebuild still running, not checked"; exit 0; fi
sleep 10   # give a healthy testmanagerd time to settle after the run
trace=$(mktemp -d /tmp/testmanagerd.XXXXXX)/s.trace
xml=${trace%.trace}.xml
trap 'rm -rf "$(dirname "$trace")"' EXIT
xcrun xctrace record --template "Activity Monitor" --device "$udid" --all-processes \
  --time-limit 4s --output "$trace" >/dev/null 2>&1 || { echo "testmanagerd: sample failed, not checked"; exit 0; }
xcrun xctrace export --input "$trace" \
  --xpath '/trace-toc/run[@number="1"]/data/table[@schema="activity-monitor-process-live"]' >"$xml" 2>/dev/null
read -r pid cpu < <(python3 -I - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit()
ids = {e.get('id'): e for e in root.iter() if e.get('id')}
v = lambda e: ids[e.get('ref')] if e.get('ref') else e
cols = [c.findtext('mnemonic') for c in root.iter('col')]
samples = {}
for row in root.iter('row'):
    d = dict(zip(cols, (v(c) for c in row)))
    name = d['process'].get('fmt', '')
    if name.startswith('testmanagerd ('):
        try: samples.setdefault(name[14:-1], []).append(float(d['cpu-percent'].text))
        except (TypeError, ValueError): pass
for pid, xs in samples.items():
    print(pid, round(sum(xs) / len(xs)))
PY
)
if [ -z "${pid:-}" ]; then echo "testmanagerd: not running"; exit 0; fi
if [ "$cpu" -lt "$max" ]; then echo "testmanagerd: pid $pid at ${cpu}% CPU, fine"; exit 0; fi
echo "testmanagerd: pid $pid stuck at ${cpu}% CPU after the run; killing it"
xcrun devicectl device process signal --device "$udid" --pid "$pid" --signal SIGKILL 2>&1 | tail -1
