# Licensed under the Apache License, Version 2.0.
# See https://www.apache.org/licenses/LICENSE-2.0
import hashlib
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import select_maven_source as selector

PATH = 'com/github/heyoulin/nacos/nacos-client/3.3.0.B/nacos-client-3.3.0.B.pom'
DATA = b'locked artifact'
LOCK = {PATH: hashlib.sha256(DATA).hexdigest()}


class SourceTests(unittest.TestCase):
    def test_curl_status_classification_and_github_url(self):
        for code, status, error in [(6, '', selector.Unreachable), (28, '', selector.Unreachable),
                                    (60, '', RuntimeError), (0, '401', RuntimeError),
                                    (0, '404', RuntimeError), (0, '503', RuntimeError)]:
            with patch.object(selector.subprocess, 'run', return_value=subprocess.CompletedProcess([], code, status.encode(), b'')):
                with self.assertRaises(error):
                    selector.download('private', PATH)
        def curl(args, **kwargs):
            self.assertEqual(args[-1], 'https://maven.pkg.github.com/spnettec/nacos/' + PATH)
            self.assertNotIn('secret-for-test', ' '.join(args))
            self.assertIn('--max-time', args)
            os.write(kwargs['pass_fds'][0], DATA)
            return subprocess.CompletedProcess(args, 0, b'200', b'')
        with patch.dict('os.environ', {'PACKAGES_USER': 'tester', 'PACKAGES_TOKEN': 'secret-for-test'}):
            with patch.object(selector.subprocess, 'run', side_effect=curl):
                self.assertEqual(selector.download('github', PATH), DATA)

    def test_private_success_never_uses_github(self):
        calls = []
        def fetch(source, path):
            calls.append(source)
            return DATA
        self.assertEqual(selector.stage(LOCK, fetch)[0], 'private')
        self.assertEqual(calls, ['private'])

    def test_dns_and_timeout_fallback(self):
        for reason in ['DNS', 'timeout']:
            def fetch(source, path):
                if source == 'private':
                    raise selector.Unreachable(reason)
                return DATA
            self.assertEqual(selector.stage(LOCK, fetch)[0], 'github')

    def test_both_unreachable_fail(self):
        def fetch(source, path):
            raise selector.Unreachable('network')
        with self.assertRaisesRegex(RuntimeError, 'Both'):
            selector.stage(LOCK, fetch)

    def test_auth_missing_server_and_tls_fail_without_fallback(self):
        for error in ['401', '403', '404', '500', 'TLS']:
            calls = []
            def fetch(source, path):
                calls.append(source)
                raise RuntimeError(error)
            with self.assertRaises(RuntimeError):
                selector.stage(LOCK, fetch)
            self.assertEqual(calls, ['private'])

    def test_github_missing_package_fails(self):
        def fetch(source, path):
            if source == 'private':
                raise selector.Unreachable('DNS')
            raise RuntimeError('404')
        with self.assertRaisesRegex(RuntimeError, '404'):
            selector.stage(LOCK, fetch)

    def test_missing_credentials_fail(self):
        with patch.dict('os.environ', {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, 'requires PACKAGES'):
                selector.download('github', PATH)

    def test_mismatched_bytes_fail_for_both_sources(self):
        for selected in ['private', 'github']:
            def fetch(source, path):
                if source != selected:
                    raise selector.Unreachable('DNS')
                return b'different'
            with self.assertRaisesRegex(RuntimeError, 'SHA-256 mismatch'):
                selector.stage(LOCK, fetch)

    def test_settings_isolated_without_publish_profile_or_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            settings = selector.write_settings(directory, {PATH: DATA}).read_text()
            self.assertIn(str(directory / 'm2'), settings)
            self.assertIn('yofc-maven-public', settings)
            self.assertNotIn('<servers>', settings)
            self.assertNotIn('distributionManagement', settings)
            self.assertNotIn('activeProfiles', settings)
            self.assertEqual((directory / 'locked-forks' / PATH).read_bytes(), DATA)

    def test_partial_private_batch_is_discarded(self):
        other = PATH.replace('.pom', '.jar')
        def fetch(source, path):
            if source == 'private' and path == other:
                raise selector.Unreachable('timeout')
            return DATA
        source, staged = selector.stage({**LOCK, other: LOCK[PATH]}, fetch)
        self.assertEqual(source, 'github')
        self.assertEqual(len(staged), 2)


if __name__ == '__main__':
    unittest.main()
