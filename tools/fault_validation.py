"""Bounded, reversible fault checks for WattScope's OWN trace process and QPT lease.

Requires the benchmark APK. Never kills system tracing services, changes freezer
rules, changes charging controls or launches an unspecified device/game.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

from power_benchmark import Device, PKG, SERVICE, write_json
from record_check import check


def utc():
    return datetime.now(timezone.utc).isoformat()


def running(d, game, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        s = d.state()
        if s.get('recording') == 'recording' and s.get('currentPackage') == game:
            return s
        time.sleep(.5)
    raise RuntimeError('Expected recording did not start')


def owned_collector(d, state):
    candidates = re.findall(r'kind=collector, message=uid=0 pid=(\d+)', ' '.join(state.get('events', [])))
    if not candidates:
        raise RuntimeError('Cannot identify the app collector')
    pid = int(candidates[-1])
    exe = d.root(f'readlink /proc/{pid}/exe').strip()
    if '/'+PKG+'-' not in exe and '/'+PKG+'/' not in exe:
        raise RuntimeError('Collector executable does not belong to WattScope: '+exe)
    if not exe.endswith('/libcollector.so'):
        raise RuntimeError('Unexpected collector executable')
    return pid


def owned_trace(d, parent):
    rows = d.root('ps -A -o PID,PPID,NAME').splitlines()[1:]
    matches = []
    for row in rows:
        p = row.split()
        if len(p) >= 3 and p[1].isdigit() and int(p[1]) == parent and p[2] == 'perfetto':
            child = int(p[0])
            if d.root(f'readlink /proc/{child}/exe').strip() == '/system/bin/perfetto':
                matches.append(child)
    if len(matches) != 1:
        raise RuntimeError(f'Expected exactly one app-owned Perfetto client, found {matches}')
    return matches[0]


def pull_record(d, session, out):
    for extension in ('json', 'wsn'):
        data = subprocess.check_output(d.command + ['exec-out', f"su -c 'cat /data/user/0/{PKG}/files/records/{session}.{extension}'"], timeout=30)
        (out / f'{session}.{extension}').write_bytes(data)
    metadata = json.loads((out / f'{session}.json').read_text(encoding='utf-8'))
    independent = check(out / f'{session}.wsn', metadata)
    write_json(out / 'independent-check.json', independent)
    return independent


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--game', required=True)
    p.add_argument('--output', required=True)
    a = p.parse_args()
    out = Path(a.output); out.mkdir(parents=True, exist_ok=False)
    d = Device(a.adb, a.serial)
    if d.state().get('recording', 'idle') != 'idle':
        raise RuntimeError('Finish the current recording first')
    initial = d.root('cat /sys/class/powercap/qpt/enabled').strip()
    if initial != '0':
        raise RuntimeError('QPT must initially be off for this ownership check')
    report = {'created_utc': utc(), 'serial': a.serial, 'game': a.game,
              'qpt_initial': initial, 'passed': False, 'observations': []}
    session = None
    try:
        d.setup('recording', a.game, a.game, 500)
        before = running(d, a.game); session = before['sessionId']
        report['session_id'] = session
        time.sleep(4)
        before = d.state()
        parent = owned_collector(d, before)
        trace = owned_trace(d, parent)
        report['before'] = before
        report['collector_pid'] = parent
        report['killed_client_pid'] = trace
        # Identity and parent are checked immediately before terminating this one child.
        if owned_trace(d, parent) != trace:
            raise RuntimeError('Trace identity changed before the fault')
        d.root(f'kill -TERM {trace}')
        recovered = None
        for _ in range(35):
            time.sleep(.5)
            s = d.state(); report['observations'].append({'utc': utc(), 'state': s})
            if s.get('sessionId') != session or s.get('recording') != 'recording':
                raise RuntimeError('The frame fault stopped or replaced the recording')
            events = ' '.join(s.get('events', []))
            if 'kind=frame_reconnect' in events and s.get('fps') not in (None, '—'):
                recovered = s
                break
        if recovered is None:
            raise RuntimeError('Frame data did not recover')
        report['after_reconnect'] = recovered
        report['new_client_pid'] = owned_trace(d, parent)
        if report['new_client_pid'] == trace or owned_collector(d, recovered) != parent:
            raise RuntimeError('Unexpected process identity after reconnect')
        if recovered['latestNs'] <= before['latestNs']:
            raise RuntimeError('Hardware samples did not continue')
        report['frame_reconnect_passed'] = True
        if d.root('cat /sys/class/powercap/qpt/enabled').strip() != '1':
            raise RuntimeError('App did not enable QPT')
        d.root('printf 0 > /sys/class/powercap/qpt/enabled')
        time.sleep(3)
        report['qpt_off_state'] = d.state()
        if d.root('cat /sys/class/powercap/qpt/enabled').strip() != '0':
            raise RuntimeError('App overwrote the external disable')
        d.root('printf 1 > /sys/class/powercap/qpt/enabled')
        time.sleep(4)
        report['qpt_on_state'] = d.state()
        d.exit()
        report['qpt_after_app_exit'] = d.root('cat /sys/class/powercap/qpt/enabled').strip()
        if report['qpt_after_app_exit'] != '1':
            raise RuntimeError('App overwrote externally re-enabled QPT')
        raw = pull_record(d, session, out)
        implausible = {k:v['averageW'] for k,v in raw['summary']['power'].items()
                       if k.startswith('qpt.') and v['averageW'] > 100}
        if implausible:
            raise RuntimeError('QPT re-enable produced an invalid counter jump: '+str(implausible))
        report['no_reenable_energy_spike'] = True
        if not any(e['kind'] == 'qpt_external_change' for e in raw['events']):
            raise RuntimeError('Missing external QPT event')
        if not any(k.startswith('qpt.') and k.endswith(':missing') for k in raw['metric_issues']):
            raise RuntimeError('QPT missing state was not recorded')
        report['qpt_external_state_passed'] = True
        report['raw_sha256'] = raw['rawSha256']
        report['crc_valid'] = raw['crc_valid']
        report['passed'] = True
    except BaseException as error:
        report['error'] = repr(error)
        raise
    finally:
        try:
            d.exit()
        except Exception as error:
            report['exit_error'] = repr(error)
            report['passed'] = False
        try:
            if d.root('cat /sys/class/powercap/qpt/enabled').strip() != initial:
                d.root('printf '+initial+' > /sys/class/powercap/qpt/enabled')
            report['qpt_final'] = d.root('cat /sys/class/powercap/qpt/enabled').strip()
        finally:
            report['finished_utc'] = utc()
            write_json(out / 'report.json', report)
    print(json.dumps({k: v for k, v in report.items() if k != 'observations'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
