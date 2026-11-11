"""Record source and existing APK identities without changing build outputs."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--variants', nargs='+', choices=('release', 'benchmark'), default=('release', 'benchmark'))
p.add_argument('--output', default='evidence/final-build-identity.json')
args = p.parse_args()
paths = sorted(list((root / 'app/src').rglob('*')) + [root / p for p in (
    'app/build.gradle.kts', 'app/proguard-rules.pro', 'build.gradle.kts',
    'settings.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties')])
files = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
         for p in paths if p.is_file()}
tests = [ET.parse(path).getroot().attrib for path in sorted((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))]
totals = {key: sum(int(suite.get(key, 0)) for suite in tests) for key in ('tests', 'failures', 'errors', 'skipped')}
totals['suites'] = tests
lint = (root / 'app/build/reports/lint-results-release.txt').read_text(encoding='utf-8')
lint_summary = re.findall(r'\d+ errors?, \d+ warnings?', lint)[-1]
algorithm = int(re.search(r'ALGORITHM_VERSION\s*=\s*(\d+)', (root / 'app/src/main/java/io/github/alita23333/wattscope/Model.kt').read_text(encoding='utf-8')).group(1))
report = {'source_files': files,
          'source_hash': hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
          'unit_tests': totals, 'lint': lint_summary,
          'algorithmVersion': algorithm, 'apks': {}}
for variant in args.variants:
    p = root / f'app/build/outputs/apk/{variant}/app-{variant}.apk'
    with zipfile.ZipFile(p) as z:
        report['apks'][variant] = {
            'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
            'collector_sha256': hashlib.sha256(z.read('lib/arm64-v8a/libcollector.so')).hexdigest()}
(root / args.output).write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in report.items() if k != 'source_files'}, indent=2))
