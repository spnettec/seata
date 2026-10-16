#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Regression checks for missing, corrupt and failed coverage inputs."""
import tempfile
import unittest
from pathlib import Path

from coverage_reports import validate, percent


class CoverageReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for module in ('common', 'core', 'server'):
            target = self.root / module / 'target'
            for folder in ('classes', 'site/jacoco', 'site/jacoco-aggregate', 'surefire-reports'):
                (target / folder).mkdir(parents=True)
            (target / 'site/jacoco-aggregate/index.html').write_text('<html>aggregate</html>')
            (target / 'site/jacoco-aggregate/jacoco.xml').write_text('<report/>')
            (target / 'classes/Example.class').write_bytes(b'class')
            (target / 'jacoco.exec').write_bytes(b'execution-data')
            (target / 'site/jacoco/index.html').write_text('<html>report</html>')
            (target / 'site/jacoco/jacoco.xml').write_text(
                '<report><counter type="LINE" covered="1" missed="9"/></report>')
            (target / 'surefire-reports/TEST-example.xml').write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        self.raft = self.root / 'server/target/surefire-reports/TEST-example.raft.Test.xml'
        self.raft.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')

    def test_valid_baseline_without_invented_threshold(self):
        self.assertEqual(3, len(validate(self.root)))
        self.assertEqual('10.00%', percent({'LINE': (1, 9)}, 'LINE'))
        self.assertEqual('N/A', percent({}, 'BRANCH'))

    def test_missing_required_module(self):
        (self.root / 'core/target/site/jacoco/jacoco.xml').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing required core'):
            validate(self.root)

    def test_missing_html_or_execution_data(self):
        for name in ('site/jacoco/index.html', 'jacoco.exec'):
            with self.subTest(name=name):
                file = self.root / 'common/target' / name
                contents = file.read_bytes()
                file.unlink()
                with self.assertRaises(ValueError):
                    validate(self.root)
                file.write_bytes(contents)

    def test_failed_tests(self):
        self.raft.write_text('<testsuite tests="1" failures="1" errors="0"/>')
        with self.assertRaisesRegex(ValueError, 'Failed tests'):
            validate(self.root)

    def test_skipped_raft_is_not_success(self):
        self.raft.write_text('<testsuite tests="1" skipped="1"/>')
        with self.assertRaisesRegex(ValueError, 'Missing executed Server Raft'):
            validate(self.root)

    def test_invalid_xml(self):
        (self.root / 'common/target/site/jacoco/jacoco.xml').write_text('<invalid/>')
        with self.assertRaisesRegex(ValueError, 'Invalid JaCoCo XML'):
            validate(self.root)


if __name__ == '__main__':
    unittest.main()
