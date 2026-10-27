"""Build a fresh source copy twice; simulate IDE corruption in separate output directories."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from benchmark import Cluster, request


def inspect_build(checkout):
    classes = checkout / 'target/standalone/classes'
    with ZipFile(checkout / 'target/reverse-proxy-demo.jar') as jar:
        entries = {n: jar.read(n) for n in jar.namelist() if n.endswith('.class')}
    actual = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')}
    if not entries or actual != entries:
        raise AssertionError('JAR must exactly match the isolated class directory')
    for name, data in entries.items():
        if data[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(data[6:8], 'big') != 65:
            raise AssertionError('Expected Java 21 bytecode: ' + name)
        if b'Unresolved compilation problem' in data:
            raise AssertionError('Compiler problem stub: ' + name)
    return {n: hashlib.sha256(data).hexdigest() for n, data in entries.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--output', type=Path, default=ROOT / 'target/clean-build-check')
    args = parser.parse_args()
    if not args.java:
        parser.error('Set JAVA_HOME/PATH to JDK 21 or pass --java')
    java = Path(args.java).resolve()
    version = subprocess.check_output([str(java), '--version'], text=True)
    if not version.splitlines()[0].split()[1].startswith('21.'):
        parser.error('JDK 21 is required')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    checkout = output / 'source checkout'
    for directory in ('src', 'scripts', 'config'):
        shutil.copytree(ROOT / directory, checkout / directory, ignore=shutil.ignore_patterns('__pycache__'))
    env = os.environ.copy()
    env['JAVA_HOME'] = str(java.parent.parent)
    env['PATH'] = str(java.parent) + os.pathsep + env['PATH']
    command = (['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts/build.ps1']
               if os.name == 'nt' else ['bash', 'scripts/build.sh'])
    canaries = [checkout / 'target/classes/IDE.class', checkout / 'target/test-classes/IDE.class']
    for path in canaries:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'IDE owns this output: Unresolved compilation problem')
    snapshots = []
    for iteration in (1, 2):
        if iteration == 2:
            (checkout / 'target/standalone/classes/obsolete.class').write_bytes(b'obsolete build output')
            for path in canaries:
                path.write_bytes(b'IDE wrote another problem stub during the rehearsal')
        expected = [path.read_bytes() for path in canaries]
        run = subprocess.run(command, cwd=checkout, env=env, capture_output=True, text=True)
        (output / f'build-{iteration}.log').write_text(run.stdout + run.stderr, encoding='utf-8')
        run.check_returncode()
        if [path.read_bytes() for path in canaries] != expected:
            raise AssertionError('Standalone build touched IDE-owned output')
        snapshots.append(inspect_build(checkout))
    if snapshots[0] != snapshots[1]:
        raise AssertionError('Two clean builds differ in class bytes')
    config = (checkout / 'config/application.properties').read_text(encoding='utf-8')
    with Cluster(output / 'runtime', java=str(java), classes=checkout / 'target/reverse-proxy-demo.jar',
                 config_text=config, overrides={'adaptive.decision-trace.enabled': 'true', 'adaptive.warmup.ms': '0'}) as cluster:
        status, headers, body = request(cluster.port, '/work?cost=2')
        decision = json.loads(request(cluster.port, '/__proxy/decision')[2])
        if status != 200 or headers['x-proxy-backend'] != decision['lastDecision']['selectedBackend']:
            raise AssertionError('Fresh JAR forwarding/decision mismatch')
        evidence = dict(status=status, headers=headers, body=body.decode(), decision=decision)
    (output / 'verification.json').write_text(json.dumps(dict(
        java=version, clean_builds=2, class_sha256=snapshots[1], ide_canaries_untouched=True,
        obsolete_class_removed=True, runtime=evidence), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('PASS: two clean source builds; IDE output untouched; obsolete classes removed; Java 21 JAR forwarding/trace verified')


if __name__ == '__main__':
    main()
