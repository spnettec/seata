#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Explicitly propose new hashes after both repositories contain identical artifacts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from select_maven_source import download, Unreachable


DEFAULT_LOCK = Path(__file__).resolve().parents[1] / 'fork-artifacts.json'


def refresh(lock_path, output, fetch=download):
    lock_path, output = Path(lock_path), Path(output)
    if output.resolve() == lock_path.resolve() or output.exists():
        raise RuntimeError('Output must be a new candidate file, never the active lock')
    original = lock_path.read_bytes()
    lock = json.loads(original)
    if not isinstance(lock, dict) or not lock:
        raise RuntimeError('Artifact lock must be a nonempty object')
    for path, digest in lock.items():
        if (not re.fullmatch(r'com/github/heyoulin/(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\.(?:pom|jar)', path)
                or any(part in {'.', '..'} for part in path.split('/'))
                or not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest)):
            raise RuntimeError('Invalid artifact lock entry: ' + path)
    candidate = {}
    for path, old_digest in sorted(lock.items()):
        # Deliberately no fallback: updating trust requires agreement from both sources.
        private = hashlib.sha256(fetch('private', path)).hexdigest()
        github = hashlib.sha256(fetch('github', path)).hexdigest()
        if private != github:
            raise RuntimeError('Sources disagree: ' + path + '; synchronize publication before refreshing')
        candidate[path] = private
        if private != old_digest:
            print(path + '\n  old: ' + old_digest + '\n  new: ' + private)
    if lock_path.read_bytes() != original:
        raise RuntimeError('Active lock changed during refresh; reread and retry')
    # Exclusive creation also prevents a concurrent invocation from replacing a candidate.
    with output.open('x') as target:
        target.write(json.dumps(candidate, indent=2, sort_keys=True) + '\n')
    print('Verified both sources for ' + str(len(candidate)) + ' files; candidate: ' + str(output))
    print('Active lock unchanged. Review the diff before manually replacing it.')
    return candidate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=DEFAULT_LOCK, help='Existing artifact paths and hashes; coordinates are preserved')
    parser.add_argument('--output', type=Path, required=True, help='New candidate JSON path; cannot overwrite the active lock or another file')
    args = parser.parse_args()
    try:
        refresh(args.lock, args.output)
    except (RuntimeError, Unreachable, OSError, ValueError) as error:
        print('Lock refresh failed: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
