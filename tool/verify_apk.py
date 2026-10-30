#!/usr/bin/env python3
"""Verify actual APK metadata after apksigner has verified its signature."""
import pathlib
import re
import subprocess
import sys
import zipfile


def verify(badging, mode, version, code):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    expected_id = 'com.flashlink.app.preview' if mode == 'preview' else 'com.flashlink.app'
    expected_version = version + ('-preview' if mode == 'preview' else '')
    if not package or package.groups() != (expected_id, code, expected_version):
        raise ValueError('Unexpected application ID or version metadata')
    if "application-label:'FlashLink'" not in badging:
        raise ValueError('Application label must be FlashLink')
    if "sdkVersion:'24'" not in badging or "targetSdkVersion:'36'" not in badging:
        raise ValueError('Unexpected minimum/target Android SDK; review compatibility before changing this gate')
    debuggable = 'application-debuggable' in badging
    if debuggable != (mode == 'debug'):
        raise ValueError('APK debuggable state does not match its requested build mode')
    permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", badging))
    required = {'android.permission.INTERNET', 'android.permission.FOREGROUND_SERVICE',
                'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE', 'android.permission.WAKE_LOCK'}
    allowed = required | {'android.permission.ACCESS_WIFI_STATE', 'android.permission.CHANGE_WIFI_STATE',
                          'android.permission.ACCESS_NETWORK_STATE', 'android.permission.NEARBY_WIFI_DEVICES',
                          'android.permission.ACCESS_COARSE_LOCATION', 'android.permission.ACCESS_FINE_LOCATION',
                          'android.permission.POST_NOTIFICATIONS', expected_id + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
    if not required <= permissions or permissions - allowed:
        raise ValueError('Unexpected APK permissions; inspect the merged manifest')


if __name__ == '__main__':
    apk, mode, aapt = sys.argv[1:]
    if mode not in {'debug', 'preview', 'release'}:
        raise SystemExit('Unknown APK mode')
    text = pathlib.Path('pubspec.yaml').read_text()
    version, code = re.search(r'^version:\s*([^+\s]+)\+(\d+)\s*$', text, re.M).groups()
    badging = subprocess.check_output([aapt, 'dump', 'badging', apk], text=True)
    verify(badging, mode, version, code)
    if mode != 'debug':
        with zipfile.ZipFile(apk) as archive:
            entries = archive.namelist()
            if not any(name.startswith('lib/') and name.endswith('/libapp.so') for name in entries) or 'assets/flutter_assets/kernel_blob.bin' in entries:
                raise SystemExit('Release APK must contain compiled AOT code without the debug kernel asset')
    print(f'APK metadata verified: FlashLink {version}+{code}, mode={mode}, minSdk=24, targetSdk=36')
