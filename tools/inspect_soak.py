"""Inspect real observation timestamps, identity, memory and write-volume deltas."""
import argparse
import json
from pathlib import Path
import statistics


def inspect(report):
    rows = report['observations']
    stable = [r for r in rows if r['elapsed_seconds'] >= report['warmup_seconds'] and r['combined_pss_kib'] is not None]
    n = min(5, len(stable)//2)
    first = rows[0]; last = rows[-1]
    result = {
        'completed': report['completed'], 'observations': len(rows),
        'first_observed_utc': first['observed_utc'], 'last_observed_utc': last['observed_utc'],
        'elapsed_seconds': last['elapsed_seconds'],
        'all_states_valid': all(r['session_valid'] for r in rows),
        'same_app_pid': len({r['app']['pid'] for r in rows}) == 1,
        'same_collector_pid': len({r['collector']['pid'] for r in rows}) == 1,
        'unique_timestamps': len({r['observed_utc'] for r in rows}) == len(rows),
        'monotonic_time_and_duration': all(b['host_monotonic'] > a['host_monotonic'] and b['state']['effectiveNs'] > a['state']['effectiveNs'] for a, b in zip(rows, rows[1:])),
        'both_windows_hidden': all(not r['state']['small'] and not r['state']['osd'] for r in rows),
        'latest_combined_pss_mib': last['combined_pss_kib']/1024,
    }
    if n:
        initial = statistics.median(r['combined_pss_kib'] for r in stable[:n])
        final = statistics.median(r['combined_pss_kib'] for r in stable[-n:])
        result['memory'] = {'first_median_mib': initial/1024, 'last_median_mib': final/1024,
                            'growth_mib': (final-initial)/1024, 'window_samples': n,
                            'within_20_mib': final-initial <= 20480}
    elapsed = last['host_monotonic']-first['host_monotonic']
    for process in ('app', 'collector'):
        row = {}
        for key in ('write_bytes', 'wchar'):
            delta = last[process][key]-first[process][key]
            row[key+'_delta'] = delta
            row[key+'_kib_per_second'] = delta/1024/elapsed if elapsed else None
        result[process+'_writes'] = row
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('report'); p.add_argument('--output')
    a = p.parse_args()
    result = inspect(json.loads(Path(a.report).read_text(encoding='utf-8')))
    if a.output: Path(a.output).write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
