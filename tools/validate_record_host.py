"""Run the app's private end-to-end record checks and stream artifacts to the host."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

from power_benchmark import Device, PKG, SERVICE, write_json

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True); p.add_argument('--serial', required=True)
p.add_argument('--id', required=True); p.add_argument('--output', required=True)
a = p.parse_args()
if not re.fullmatch(r'[0-9a-fA-F-]{36}', a.id): raise ValueError('Invalid record ID')
out = Path(a.output); out.mkdir(parents=True, exist_ok=False)
d = Device(a.adb, a.serial)
if d.state().get('recording', 'idle') != 'idle': raise RuntimeError('An active recording exists')
d.adb('shell', 'am', 'start', '-n', PKG+'/.MainActivity')
time.sleep(1)
remote = f'/data/user/0/{PKG}/files/validation-record-{a.id}.json'
old = d.root('if [ -f '+shlex.quote(remote)+' ]; then cat '+shlex.quote(remote)+'; else printf "{}"; fi')
old_time = json.loads(old).get('startedWallMs', 0)
d.root(f'am start-foreground-service -n {SERVICE} -a validate_record --es record_id {a.id}')
memory = []
result = None
deadline = time.monotonic()+180
while time.monotonic() < deadline:
    time.sleep(1)
    text = d.root('if [ -f '+shlex.quote(remote)+' ]; then cat '+shlex.quote(remote)+'; else printf "{}"; fi')
    report = json.loads(text)
    if report.get('startedWallMs', 0) > old_time and 'finishedWallMs' in report:
        result = report
        break
    pid = d.adb('shell', 'pidof', PKG).strip().split()
    if not pid: raise RuntimeError('App exited during validation')
    rollup = d.root(f'cat /proc/{int(pid[0])}/smaps_rollup')
    match = re.search(r'^Pss:\s*(\d+)', rollup, re.M)
    memory.append({'observed_utc': datetime.now(timezone.utc).isoformat(), 'pss_kib': int(match[1]) if match else None})
write_json(out/'memory-observations.json', memory)
if result is None: raise RuntimeError('Validation did not finish within 180 seconds')
write_json(out/'report.json', result)
if not result.get('passed'): raise RuntimeError(result.get('error', 'Record validation failed'))
for key in ('summaryImage', 'csv', 'comparisonCsv', 'backup'):
    remote_file = result.get(key)
    if not remote_file: continue
    prefix = f'/data/user/0/{PKG}/cache/exports/'
    if not remote_file.startswith(prefix) or not re.fullmatch(r'[A-Za-z0-9._-]+', remote_file[len(prefix):]):
        raise RuntimeError('Unexpected export path')
    with (out/Path(remote_file).name).open('wb') as stream:
        subprocess.run(d.command+['exec-out', 'su -c '+shlex.quote('cat '+shlex.quote(remote_file))], stdout=stream, check=True, timeout=120)
d.exit()
print(json.dumps({'record': a.id, 'passed': True, 'seconds': result['fullSummary']['seconds'],
                  'frameCount': result['fullSummary']['frameCount'],
                  'peak_observed_pss_kib': max((r['pss_kib'] for r in memory if r['pss_kib'] is not None), default=None)}, indent=2))
