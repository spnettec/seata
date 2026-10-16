#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Stage checksum-locked fork dependencies; never invokes Maven or deploy."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

PRIVATE = 'https://mesmaven.yofc.com/repository/yofc-public/'
GITHUB_RELEASES = 'https://raw.githubusercontent.com/spnettec/maven-repository/master/releases/'
NETWORK_ERRORS = {5, 6, 7, 28, 52, 55, 56}


class Unreachable(Exception):
    pass


def download(source, path):
    if source == 'private':
        url = PRIVATE + path
        user, token = os.getenv('YOFC_MAVEN_USERNAME', ''), os.getenv('YOFC_MAVEN_PASSWORD', '')
    else:
        url = GITHUB_RELEASES + path
        # Public static repository: never forward credentials to raw.githubusercontent.com.
        user, token = '', ''
    config = ''
    if user or token:
        auth = base64.b64encode((user + ':' + token).encode()).decode()
        config = 'header = "Authorization: Basic ' + auth + '"\n'
    # No redirects, retries, shell, credential arguments, or credential files.
    with tempfile.TemporaryFile() as body:
        result = subprocess.run(['curl', '--disable', '--silent', '--connect-timeout', '5',
                                 '--max-time', '30', '--proto', '=https',
                                 '--config', '-', '--output', '/dev/fd/' + str(body.fileno()),
                                 '--write-out', '%{http_code}', url],
                                input=config.encode(), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, pass_fds=(body.fileno(),))
        if result.returncode in NETWORK_ERRORS:
            raise Unreachable(source + ' network unavailable (curl ' + str(result.returncode) + ')')
        if result.returncode:
            raise RuntimeError(source + ' TLS/protocol/local failure (curl ' + str(result.returncode) + ')')
        status = result.stdout.decode().strip()
        if status != '200':
            raise RuntimeError(source + ' returned HTTP ' + status + ' for ' + path +
                               '; auth errors, missing artifacts and server errors do not trigger fallback')
        body.seek(0)
        return body.read()


def stage(lock, fetch=download):
    if not lock:
        raise RuntimeError('Artifact lock must not be empty')
    for source in ['private', 'github']:
        staged = {}
        print('Checking dependency source: ' + source, flush=True)
        try:
            for path, digest in lock.items():
                if not path.startswith('com/github/heyoulin/') or '..' in path.split('/'):
                    raise RuntimeError('Invalid artifact lock path')
                data = fetch(source, path)
                if hashlib.sha256(data).hexdigest() != digest:
                    raise RuntimeError('SHA-256 mismatch: ' + path + '; same-version content changed; review lock explicitly')
                staged[path] = data
            return source, staged
        except Unreachable as exc:
            print(str(exc), flush=True)
            if source == 'github':
                raise RuntimeError('Both dependency sources unavailable') from None
            print('Discarding partial private download; trying GitHub', flush=True)
    raise AssertionError('unreachable')


def write_settings(directory, staged):
    repository = directory / 'locked-forks'
    for path, data in staged.items():
        target = repository / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.with_name(target.name + '.sha1').write_text(hashlib.sha1(data).hexdigest())
    root = ET.Element('settings', xmlns='http://maven.apache.org/SETTINGS/1.2.0')
    ET.SubElement(root, 'localRepository').text = str(directory / 'm2')
    mirror = ET.SubElement(ET.SubElement(root, 'mirrors'), 'mirror')
    ET.SubElement(mirror, 'id').text = 'ci-locked-forks'
    ET.SubElement(mirror, 'mirrorOf').text = 'yofc-maven-public'
    ET.SubElement(mirror, 'url').text = repository.as_uri()
    settings = directory / 'settings.xml'
    ET.ElementTree(root).write(settings, encoding='unicode')
    return settings


def main():
    lock = json.loads((Path(__file__).resolve().parents[1] / 'fork-artifacts.json').read_text())
    source, staged = stage(lock)
    directory = Path(tempfile.mkdtemp(prefix='maven-source-', dir=os.getenv('RUNNER_TEMP'))).resolve()
    settings = write_settings(directory, staged)
    print('Selected ' + source + '; verified ' + str(len(staged)) + ' files; fresh Maven cache: ' + str(directory / 'm2'))
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('settings=' + str(settings) + '\nsource=' + source + '\n')
    else:
        print('Use Maven -s ' + str(settings))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError) as error:
        print('Dependency selection failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
