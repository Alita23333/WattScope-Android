"""Package existing verified outputs, portable source and inspectable hashes. Does not build/install."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for b in iter(lambda: stream.read(65536), b''):
            h.update(b)
    return h.hexdigest()


def source_paths():
    sources = []
    excluded = {'build', '__pycache__', '.cxx', '.venv', 'venv',
                'node_modules', '.git', '.gradle'}
    for folder in ('app/src', 'gradle', 'docs', 'tools'):
        for path in (ROOT / folder).rglob('*'):
            rel = path.relative_to(ROOT)
            if not path.is_file() or excluded.intersection(rel.parts):
                continue
            # WattScope tooling is kept at tools/; sibling tool projects and
            # their environments are independent workspace content.
            if folder == 'tools' and path.parent != ROOT / 'tools':
                continue
            sources.append(path)
    for name in ('README.md', 'build.ps1', 'build.gradle.kts', 'settings.gradle.kts',
                 'gradle.properties', 'gradlew', 'gradlew.bat', '.gitignore',
                 'app/build.gradle.kts', 'app/proguard-rules.pro'):
        sources.append(ROOT / name)
    return sorted(set(sources))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True)
    p.add_argument('--variants', nargs='+', choices=('release', 'benchmark'), default=('release', 'benchmark'))
    a = p.parse_args()
    out = Path(a.output).resolve()
    if out.exists():
        raise RuntimeError('Use a new output folder; existing deliverables are never overwritten')
    out.mkdir(parents=True)
    copied = []
    for variant in a.variants:
        src = ROOT / f'app/build/outputs/apk/{variant}/app-{variant}.apk'
        dest = out / f'WattScope-0.1.0-{variant}.apk'
        shutil.copy2(src, dest)
        copied.append(dest)
    sources = source_paths()
    source_manifest = {str(x.relative_to(ROOT)).replace('\\', '/'): digest(x) for x in sources}
    source_zip = out / 'WattScope-0.1.0-source.zip'
    with zipfile.ZipFile(source_zip, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for path in sources:
            z.write(path, 'WattScope/' + str(path.relative_to(ROOT)).replace('\\', '/'))
        z.writestr('WattScope/source-manifest.json', json.dumps(source_manifest, indent=2))
    copied.append(source_zip)
    for name in ('README.md', 'docs/acceptance.md', 'docs/power-overhead-method.md', 'docs/metrics.md'):
        src = ROOT / name
        if src.exists():
            dest = out / src.name
            shutil.copy2(src, dest)
            copied.append(dest)
    manifest = {'format': 1, 'version': '0.1.0', 'package': 'io.github.alita23333.wattscope',
                'source_files': len(sources),
                'artifacts': {x.name: {'bytes': x.stat().st_size, 'sha256': digest(x)} for x in copied}}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    (out / 'SHA256SUMS.txt').write_text(''.join(f"{v['sha256']}  {k}\n" for k, v in manifest['artifacts'].items()), encoding='utf-8')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
