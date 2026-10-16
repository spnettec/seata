# Licensed under the Apache License, Version 2.0.
# See https://www.apache.org/licenses/LICENSE-2.0
import hashlib
import json
import sys
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import select_maven_source as selector
import refresh_artifact_lock as refresher

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



class RefreshTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.lock = Path(self.tmp.name) / 'lock.json'
        self.output = Path(self.tmp.name) / 'candidate.json'
        self.lock.write_text(json.dumps(LOCK))
        self.original = self.lock.read_bytes()

    def test_same_version_refresh_is_candidate_only(self):
        calls = []
        def fetch(source, path):
            calls.append((source, path))
            return b'new release bytes'
        candidate = refresher.refresh(self.lock, self.output, fetch)
        self.assertEqual(list(candidate), list(LOCK))
        self.assertEqual(candidate[PATH], hashlib.sha256(b'new release bytes').hexdigest())
        self.assertEqual(calls, [('private', PATH), ('github', PATH)])
        self.assertEqual(self.lock.read_bytes(), self.original)
        self.assertEqual(json.loads(self.output.read_text()), candidate)
        # CI continues rejecting changed bytes until the candidate is explicitly adopted.
        with self.assertRaisesRegex(RuntimeError, 'SHA-256 mismatch'):
            selector.stage(LOCK, fetch)
        self.assertEqual(selector.stage(candidate, fetch)[0], 'private')

    def test_unchanged_sources_are_idempotent(self):
        self.assertEqual(refresher.refresh(self.lock, self.output, lambda *_: DATA), LOCK)

    def test_mismatch_does_not_write_candidate_or_active_lock(self):
        with self.assertRaisesRegex(RuntimeError, 'Sources disagree'):
            refresher.refresh(self.lock, self.output, lambda source, _: DATA if source == 'private' else b'different')
        self.assertFalse(self.output.exists())
        self.assertEqual(self.lock.read_bytes(), self.original)

    def test_network_auth_missing_and_tls_fail_closed(self):
        for failed_source in ['private', 'github']:
            for error in [selector.Unreachable('DNS'), selector.Unreachable('timeout'),
                          RuntimeError('401'), RuntimeError('403'), RuntimeError('404'),
                          RuntimeError('500'), RuntimeError('TLS'), RuntimeError('missing credentials')]:
                calls = []
                def fetch(source, path):
                    calls.append(source)
                    if source == failed_source:
                        raise error
                    return DATA
                with self.assertRaises(type(error)):
                    refresher.refresh(self.lock, self.output, fetch)
                self.assertFalse(self.output.exists())
                self.assertEqual(self.lock.read_bytes(), self.original)
                self.assertEqual(calls, ['private'] if failed_source == 'private' else ['private', 'github'])

    def test_refuse_active_or_existing_output_before_network(self):
        self.output.write_text('preserve')
        with patch.object(refresher, 'download') as fetch:
            for output in [self.lock, self.output]:
                with self.assertRaisesRegex(RuntimeError, 'new candidate'):
                    refresher.refresh(self.lock, output, fetch)
            fetch.assert_not_called()
        self.assertEqual(self.output.read_text(), 'preserve')

    def test_invalid_lock_is_rejected_before_network(self):
        for invalid in [{}, {PATH: 'bad-hash'}, {'com/github/heyoulin/../escape.jar': LOCK[PATH]}]:
            self.lock.write_text(json.dumps(invalid))
            with patch.object(refresher, 'download') as fetch:
                with self.assertRaises(RuntimeError):
                    refresher.refresh(self.lock, self.output, fetch)
                fetch.assert_not_called()
            self.assertFalse(self.output.exists())

    def test_concurrent_lock_edit_is_preserved(self):
        def fetch(source, path):
            self.lock.write_text('concurrent edit')
            return DATA
        with self.assertRaisesRegex(RuntimeError, 'changed during refresh'):
            refresher.refresh(self.lock, self.output, fetch)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.lock.read_text(), 'concurrent edit')

    def test_late_failure_does_not_write_partial_candidate(self):
        second = PATH.replace('.pom', '.jar')
        self.lock.write_text(json.dumps({PATH: LOCK[PATH], second: LOCK[PATH]}))
        original = self.lock.read_bytes()
        calls = []
        def fetch(source, path):
            calls.append((source, path))
            if len(calls) == 4:
                raise RuntimeError('404')
            return DATA
        with self.assertRaisesRegex(RuntimeError, '404'):
            refresher.refresh(self.lock, self.output, fetch)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.lock.read_bytes(), original)

    def test_cli_requires_explicit_output(self):
        result = subprocess.run([sys.executable, str(Path(refresher.__file__))], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b'--output', result.stderr)


if __name__ == '__main__':
    unittest.main()
