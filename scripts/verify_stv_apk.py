#!/usr/bin/env python3
"""Verify a vendor-signed STV compatibility APK before installing it on the new TV."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import zipfile

EXPECTED_CERT = 'cc079d3a727b1b53a7f3376eed1720988d63873517c4303578031ec54b8013c7'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', type=Path, default=Path.home() / 'Library/Android/sdk/build-tools/36.0.0')
    args = parser.parse_args()
    env = os.environ.copy()
    bundled_jdk = Path(__file__).resolve().parents[1] / '.tools/jdk/Contents/Home'
    if 'JAVA_HOME' not in env and bundled_jdk.is_dir():
        env['JAVA_HOME'] = str(bundled_jdk)

    def run(tool, *params):
        result = subprocess.run([str(args.build_tools / tool), *map(str, params)],
                                env=env, text=True, capture_output=True, timeout=60)
        if result.returncode:
            details = result.stdout + '\n' + result.stderr
            if tool == 'apksigner' and 'requires a minimum of signature scheme v2' in details:
                raise ValueError('签名方案不足：此 APK 的 targetSdk 要求 V2 或更高签名，请让厂商平台启用 V2/V3 后重新签名。')
            raise ValueError(f'{tool} 校验失败：APK 未正确签名或格式无效。')
        return result.stdout

    certificates = run('apksigner', 'verify', '--print-certs', args.apk)
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', certificates)
    if digests != [EXPECTED_CERT]:
        raise ValueError('签名证书与这台电视原厂 com.stv.voice 不一致，不能覆盖安装。')
    badging = run('aapt2', 'dump', 'badging', args.apk)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    if not package or package[1] != 'com.stv.voice' or int(package[2]) <= 33022820:
        raise ValueError('包名或 versionCode 不符合原厂包覆盖要求。')
    tree = run('aapt2', 'dump', 'xmltree', args.apk, '--file', 'AndroidManifest.xml')
    if not re.search(r'sharedUserId[^\n]*="android.uid.system"', tree):
        raise ValueError('缺少与原厂包一致的 android.uid.system。')
    if not re.search(r'name[^\n]*="com.stv.voice.WindowService"', tree):
        raise ValueError('缺少系统语音键调用的 WindowService。')
    with zipfile.ZipFile(args.apk) as archive:
        native = [x for x in archive.infolist() if x.filename.startswith('lib/armeabi-v7a/')]
        if not native:
            raise ValueError('缺少这台电视需要的 armeabi-v7a 运行库。')
        if any(x.compress_type != zipfile.ZIP_STORED for x in native):
            raise ValueError('平台重压缩了原生库，请保留 APK 内原生库的未压缩格式。')
    run('zipalign', '-c', '-p', '4', args.apk)
    print('通过：厂商签名、包名、版本号、共享 UID、语音服务、32 位运行库及对齐检查。')
    print('这不代替真机验证；尚需检查按键 DOWN/UP、有效 PCM 和最终识别结果。')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
