import unittest
from verify_apk import verify


class ApkMetadataTest(unittest.TestCase):
    def fixture(self, mode='preview'):
        package = 'com.flashlink.app.preview' if mode == 'preview' else 'com.flashlink.app'
        version = '0.1.0-preview' if mode == 'preview' else '0.1.0'
        return '\n'.join([
            f"package: name='{package}' versionCode='2' versionName='{version}' platformBuildVersionName=''",
            "application-label:'FlashLink'", "sdkVersion:'24'", "targetSdkVersion:'36'",
            "uses-permission: name='android.permission.INTERNET'",
            "uses-permission: name='android.permission.FOREGROUND_SERVICE'",
            "uses-permission: name='android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE'",
            "uses-permission: name='android.permission.WAKE_LOCK'",
        ] + (['application-debuggable'] if mode == 'debug' else []))

    def test_explicit_test_preview_and_normal_modes(self):
        for mode in ['debug', 'preview', 'release']:
            verify(self.fixture(mode), mode, '0.1.0', '2')

    def test_production_namespace_cannot_be_uploaded_as_test_preview(self):
        with self.assertRaises(ValueError):
            verify(self.fixture('release'), 'preview', '0.1.0', '2')

    def test_release_preview_cannot_be_debuggable(self):
        with self.assertRaises(ValueError):
            verify(self.fixture() + '\napplication-debuggable', 'preview', '0.1.0', '2')

    def test_unrelated_permissions_are_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.fixture() + "\nuses-permission: name='android.permission.MANAGE_EXTERNAL_STORAGE'", 'preview', '0.1.0', '2')

    def test_stale_version_and_sdk_are_rejected(self):
        for badging in [self.fixture().replace("versionCode='2'", "versionCode='1'"),
                        self.fixture().replace("targetSdkVersion:'36'", "targetSdkVersion:'33'")]:
            with self.assertRaises(ValueError):
                verify(badging, 'preview', '0.1.0', '2')

    def test_foreground_service_permission_is_required(self):
        with self.assertRaises(ValueError):
            verify(self.fixture().replace("uses-permission: name='android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE'", ''), 'preview', '0.1.0', '2')


if __name__ == '__main__':
    unittest.main()
