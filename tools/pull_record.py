"""Stream one named app record to a new local file; no phone writes."""
import argparse
from pathlib import Path
import re
import subprocess

from power_benchmark import PKG

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True); p.add_argument('--serial', required=True)
p.add_argument('--id', required=True); p.add_argument('--output', required=True)
a = p.parse_args()
if not re.fullmatch(r'[0-9a-fA-F-]{36}', a.id): raise ValueError('Invalid record ID')
folder = Path(a.output); folder.mkdir(parents=True, exist_ok=True)
for extension in ('json', 'wsn'):
    destination = folder / f'{a.id}.{extension}'
    if destination.exists():
        print('Existing copy preserved:', destination.name)
        continue
    partial = destination.with_suffix(destination.suffix+'.part')
    with partial.open('wb') as stream:
        subprocess.run([a.adb, '-s', a.serial, 'exec-out',
                        f"su -c 'cat /data/user/0/{PKG}/files/records/{a.id}.{extension}'"],
                       stdout=stream, check=True, timeout=120)
    partial.replace(destination)
    print(destination.name, destination.stat().st_size, 'bytes')
