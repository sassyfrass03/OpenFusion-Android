#!/usr/bin/env python3
"""Build the standalone Android launcher using SDK tools and Eclipse ECJ."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--android-jar', type=Path, required=True)
parser.add_argument('--build-tools', type=Path, required=True)
parser.add_argument('--ecj', type=Path, help='Optional Eclipse ECJ compiler; javac is used when omitted')
parser.add_argument('--keystore', type=Path, help='Existing Android debug keystore, alias androiddebugkey/password android')
parser.add_argument('--output', type=Path, default=ROOT / 'build' / 'OpenFusion-Android-0.4.11-stable-runtime-detection.apk')
args = parser.parse_args()
android_jar, sdk = (p.resolve() for p in (args.android_jar, args.build_tools))
ecj = args.ecj.resolve() if args.ecj else None
output = args.output.resolve()
stage = ROOT / 'build' / 'staging'
if stage.exists():
    shutil.rmtree(stage)
for directory in (stage / 'gen', stage / 'classes', stage / 'dex', output.parent):
    directory.mkdir(parents=True, exist_ok=True)
app = ROOT / 'app' / 'src' / 'main'

# Never package the stale runner by accident: 0.4.11 relies on the 1.9.0
# keyboard/mouse controller adapter and top-level Unity browser renderer. The build string is
# embedded in the compiled EXE and gives us a toolchain-independent guard.
runner = app / 'assets' / 'runner' / 'ffrunner-android.exe'
expected_runner_build = b'1.9.0-stable-auth-logdiag'
if not runner.exists() or expected_runner_build not in runner.read_bytes():
    raise RuntimeError(
        'FFRunner 1.9.0 is not staged. Build runner-source/ first, then copy '
        'ffrunner.exe to app/src/main/assets/runner/ffrunner-android.exe. '
        'Refusing to package the stale runner.'
    )

def run(*command):
    subprocess.run([str(arg) for arg in command], check=True)

run(sdk / 'aapt2', 'compile', '--dir', app / 'res', '-o', stage / 'res.zip')
# AssetFileDescriptor needs stored (uncompressed) MP3 entries for native audio.
run(sdk / 'aapt2', 'link', '-o', stage / 'base.apk', '-I', android_jar,
    '--manifest', app / 'AndroidManifest.xml', '--java', stage / 'gen',
    '-A', app / 'assets', '-0', 'mp3', stage / 'res.zip')
sources = sorted((app / 'java').rglob('*.java')) + sorted((stage / 'gen').rglob('*.java'))
if ecj:
    run('java', '-jar', ecj, '-8', '-proc:none', '-bootclasspath',
        os.pathsep.join((str(android_jar), str(sdk / 'core-lambda-stubs.jar'))),
        '-d', stage / 'classes', *sources)
else:
    # Compile Android references from android.jar while retaining the host JDK's
    # Java 8 lambda bootstrap classes. D8 performs Android desugaring afterward.
    # Using android.jar as javac's bootclasspath hides LambdaMetafactory on modern
    # SDKs and breaks otherwise valid Java 8 lambdas.
    run('javac', '-source', '8', '-target', '8', '-proc:none', '-classpath',
        str(android_jar), '-d', stage / 'classes', *sources)
with zipfile.ZipFile(stage / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in sorted((stage / 'classes').rglob('*.class')):
        archive.write(file, file.relative_to(stage / 'classes').as_posix())
run('java', '-cp', sdk / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--min-api', '26',
    '--lib', android_jar, '--output', stage / 'dex', stage / 'classes.jar')
with zipfile.ZipFile(stage / 'base.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    for file in sorted((stage / 'dex').glob('*.dex')):
        archive.write(file, file.name)
run(sdk / 'zipalign', '-f', '4', stage / 'base.apk', stage / 'aligned.apk')
keystore = args.keystore.resolve() if args.keystore else ROOT / 'build' / 'debug.keystore'
if not keystore.exists():
    if args.keystore:
        raise FileNotFoundError(keystore)
    run('keytool', '-genkeypair', '-keystore', keystore, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-dname', 'CN=Android Debug,O=Android,C=US')
run(sdk / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', 'androiddebugkey',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, stage / 'aligned.apk')
run(sdk / 'apksigner', 'verify', '--verbose', '--print-certs', output)
run(sdk / 'zipalign', '-c', '4', output)
with zipfile.ZipFile(output) as archive:
    for name in ('assets/audio/background.mp3', 'assets/audio/tap.mp3'):
        assert archive.getinfo(name).compress_type == zipfile.ZIP_STORED, name
print(f'Built {output} ({output.stat().st_size:,} bytes)')
