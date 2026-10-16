#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Validate the full CI coverage output and index module/dependency reports."""

import html
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(root):
    reports = sorted(root.glob('**/target/site/jacoco/jacoco.xml'))
    for module in ('common', 'core', 'server'):
        if root / module / 'target/site/jacoco/jacoco.xml' not in reports:
            raise ValueError(f'Missing required {module} coverage report')
    raft = list((root / 'server/target/surefire-reports').glob('TEST-*raft*.xml'))
    if not any(int(ET.parse(p).getroot().get('tests', '0')) >
               int(ET.parse(p).getroot().get('skipped', '0')) for p in raft):
        raise ValueError('Missing executed Server Raft tests')
    rows = []
    for xml in reports:
        report = ET.parse(xml).getroot()
        if report.tag != 'report':
            raise ValueError(f'Invalid JaCoCo XML: {xml}')
        page = xml.with_name('index.html')
        if not page.is_file() or page.stat().st_size == 0:
            raise ValueError(f'Missing HTML report: {page}')
        aggregate = xml.parent.parent / 'jacoco-aggregate'
        if not (aggregate / 'index.html').is_file():
            raise ValueError(f'Missing dependency aggregate: {aggregate}')
        ET.parse(aggregate / 'jacoco.xml')
        module = xml.parent.parent.parent.parent.relative_to(root).as_posix()
        counters = {c.get('type'): (int(c.get('covered')), int(c.get('missed')))
                    for c in report.findall('counter')}
        rows.append((module, page.relative_to(root).as_posix(), counters))
    for tests in root.glob('**/target/surefire-reports/TEST-*.xml'):
        suite = ET.parse(tests).getroot()
        if int(suite.get('failures', '0')) or int(suite.get('errors', '0')):
            raise ValueError(f'Failed tests: {tests}')
        target = tests.parent.parent
        if any((target / 'classes').glob('**/*.class')):
            for file in (target / 'jacoco.exec', target / 'site/jacoco/jacoco.xml'):
                if not file.is_file() or file.stat().st_size == 0:
                    raise ValueError(f'Missing coverage for tested module: {file}')
    return rows


def percent(counters, kind):
    covered, missed = counters.get(kind, (0, 0))
    return f'{100 * covered / (covered + missed):.2f}%' if covered + missed else 'N/A'


def main():
    root = Path.cwd().resolve()
    rows = validate(root)
    summary = ['# JaCoCo coverage', '',
               'No numerical coverage threshold is configured. These are module baselines.',
               'Dependency aggregates overlap; do not sum their counters.', '',
               '| Module | Line | Branch |', '|---|---:|---:|']
    links = []
    for module, page, counters in rows:
        line, branch = percent(counters, 'LINE'), percent(counters, 'BRANCH')
        summary.append(f'| {module} | {line} | {branch} |')
        links.append(f'<li><a href="../{html.escape(page)}">{html.escape(module)}</a>'
                     f' — line {line}, branch {branch}</li>')
    aggregates = []
    for page in sorted(root.glob('**/target/site/jacoco-aggregate/index.html')):
        xml = page.with_name('jacoco.xml')
        ET.parse(xml)  # malformed/missing aggregate reports must fail
        path = page.relative_to(root).as_posix()
        aggregates.append(f'<li><a href="../{html.escape(path)}">{html.escape(path)}</a></li>')
    (root / 'target').mkdir(exist_ok=True)
    (root / 'target/coverage-summary.md').write_text('\n'.join(summary) + '\n')
    (root / 'target/coverage-index.html').write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><title>Seata JaCoCo</title>'
        '<h1>Seata JaCoCo reports</h1><p>Module baselines; no numerical threshold configured.</p>'
        '<h2>Modules</h2><ul>' + ''.join(links) + '</ul>'
        '<h2>Dependency aggregates</h2><p>These reports overlap and include each project plus '
        'its reactor dependencies; they are not disjoint whole-repository totals.</p><ul>'
        + ''.join(aggregates) + '</ul></html>')
    print(f'Validated {len(rows)} module reports and {len(aggregates)} dependency aggregates.')
    print('Extract the CI artifact and open target/coverage-index.html.')


if __name__ == '__main__':
    main()
