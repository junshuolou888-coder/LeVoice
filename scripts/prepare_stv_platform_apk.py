#!/usr/bin/env python3
"""Create a locally V1+V2-signed intermediate APK for the vendor signing platform."""
import argparse
import os
from pathlib import Path
import secrets
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    output_dir = root / 'app/build/outputs/vendor-signing'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=output_dir / 'LeVoice-STV-2023-armv7-target29-unsigned.apk')
    parser.add_argument('--output', type=Path, default=output_dir / 'LeVoice-STV-2023-armv7-target29-for-platform.apk')
    parser.add_argument('--build-tools', type=Path, default=Path.home() / 'Library/Android/sdk/build-tools/36.0.0')
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error('Input and output must differ; preserve the unsigned source.')
    if not args.input.is_file():
        parser.error('Unsigned source APK not found.')
    env = os.environ.copy()
    java = Path(env.get('JAVA_HOME', str(root / '.tools/jdk/Contents/Home')))
    env['JAVA_HOME'] = str(java)

    def run(command):
        return subprocess.run(list(map(str, command)), env=env, check=True,
                              text=True, capture_output=True, timeout=120).stdout

    tools = args.build_tools
    manifest = run([tools / 'aapt2', 'dump', 'xmltree', args.input, '--file', 'AndroidManifest.xml'])
    if 'com.stv.voice.WindowService' not in manifest or 'android.uid.system' not in manifest:
        parser.error('Input is not the STV compatibility build.')
    run([tools / 'zipalign', '-c', '-p', '4', args.input])

    # Upload-only identity; this is NOT the TV platform key. Keep it out of Git.
    private = root / '.tools/signing/stv-platform-upload'
    private.mkdir(parents=True, exist_ok=True, mode=0o700)
    private.chmod(0o700)
    key = private / 'upload.p12'
    password = private / 'password.txt'
    if key.exists() != password.exists():
        parser.error('Incomplete existing upload key; refusing to overwrite it.')
    if not key.exists():
        with open(password, 'x', opener=lambda p, f: os.open(p, f, 0o600)) as file:
            file.write(secrets.token_urlsafe(32))
        env['STV_UPLOAD_STORE_PASS'] = password.read_text()
        run([java / 'bin/keytool', '-genkeypair', '-keystore', key,
             '-storetype', 'PKCS12', '-alias', 'platform-upload',
             '-storepass:env', 'STV_UPLOAD_STORE_PASS',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10950',
             '-dname', 'CN=LeVoice Upload Prep,OU=Development,O=LocalVoiceTV,C=CN'])
    key.chmod(0o600)
    password.chmod(0o600)
    env['STV_UPLOAD_STORE_PASS'] = password.read_text()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run([tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'platform-upload',
         '--ks-pass', 'env:STV_UPLOAD_STORE_PASS',
         '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
         '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
         '--out', args.output, args.input])
    report = run([tools / 'apksigner', 'verify', '--verbose', '--print-certs', args.output])
    # With minSdk >= 24 the default verifier selects V2 and does not exercise V1.
    # Check JAR signing separately; this does not change the APK's minSdk.
    jar_report = run([tools / 'apksigner', 'verify', '--min-sdk-version', '23',
                      '--max-sdk-version', '23', '--verbose', args.output])
    if ('Verified using v1 scheme (JAR signing): true' not in jar_report or
            'Verified using v2 scheme (APK Signature Scheme v2): true' not in report):
        raise ValueError('Expected both V1 and V2 signatures.')
    run([tools / 'zipalign', '-c', '-p', '4', args.output])
    args.output.with_suffix('.verification.txt').write_text(report + '\nSeparate V1 verification:\n' + jar_report)
    print(f'Created upload intermediate: {args.output}')
    print('V1 + V2 and alignment verified. Send to the vendor platform; do not install this intermediate.')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr or f'Tool failed with code {error.returncode}')
