#!/usr/bin/env python3
"""Copy a local weather credential into an installed debug app's private directory."""
import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='ADB device serial, e.g. TV_IP:5555')
    parser.add_argument('--config', type=Path, default=Path(__file__).resolve().parents[1] / 'weather.local.json')
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    host = config.get('host', '')
    key = config.get('apiKey', '')
    if not re.fullmatch(r'[a-z0-9-]+(?:\.[a-z0-9-]+)*\.qweatherapi\.com', host):
        parser.error('Invalid QWeather host')
    if not isinstance(key, str) or not key or any(c.isspace() for c in key):
        parser.error('Missing or invalid API KEY')
    payload = json.dumps({ 'host': host, 'apiKey': key, 'defaultCity': config.get('defaultCity', '') }, ensure_ascii=False).encode()
    adb = [args.adb, '-s', args.serial, 'exec-in']
    # The credential travels through stdin, never through command-line arguments or shared storage.
    write = f"run-as com.localvoicetv sh -c 'umask 077 && mkdir -p no_backup && dd bs=1 count={len(payload)} of=no_backup/weather.json.tmp && mv no_backup/weather.json.tmp no_backup/weather.json'"
    result = subprocess.run(adb + [write], input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    if result.returncode:
        raise SystemExit('Configuration failed. Check that the device is connected and the debug APK is installed.')
    read = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'run-as com.localvoicetv cat no_backup/weather.json'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    try:
        verified = read.returncode == 0 and json.loads(read.stdout) == json.loads(payload)
    except (ValueError, UnicodeDecodeError):
        verified = False
    if not verified:
        raise SystemExit('Could not verify the private weather configuration.')
    print('Weather configuration verified in app-private storage. Restart the app to apply it.')


if __name__ == '__main__':
    try:
        main()
    except subprocess.TimeoutExpired:
        raise SystemExit('ADB transfer timed out. Check the device connection and retry.')
