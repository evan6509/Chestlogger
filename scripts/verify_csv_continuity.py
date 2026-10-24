#!/usr/bin/env python3
"""Run independently compiled 26.2 → 26.3 → 26.2 writers without a game world."""
import csv
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def run_writers(build262, build263, folder):
    previous = b''
    for index, (build, target) in enumerate([(build262, '26.2'), (build263, '26.3'), (build262, '26.2')], 1):
        classpath = os.pathsep.join(str(build / 'classes/java' / kind) for kind in ('main', 'test'))
        subprocess.run(['java', '-cp', classpath, 'com.chestlogger.csv.CsvContinuityWriter',
                        str(folder), target], check=True)
        previous = validate_step(folder / 'ChestLog/chestlog.csv', previous, index)

    return previous


def validate_step(path, previous, index):
    current = path.read_bytes()
    if not current.startswith(previous):
        raise ValueError('Historical CSV bytes changed')
    if current.count(b'\xef\xbb\xbf') != 1:
        raise ValueError('CSV must contain one BOM')
    reader = csv.DictReader(io.StringIO(current.decode('utf-8-sig'), newline=''))
    if len(reader.fieldnames) != 24 or len(set(reader.fieldnames)) != 24:
        raise ValueError('CSV header/schema changed')
    rows = list(reader)
    if len(rows) != index * 2 or any(None in row or None in row.values() for row in rows):
        raise ValueError('CSV record count or schema changed')
    for offset in range(0, len(rows), 2):
        pair = rows[offset:offset + 2]
        if pair[0]['event_id'] != pair[1]['event_id'] or sum(int(r['quantity_delta']) for r in pair) != 0:
            raise ValueError('Unbalanced or ungrouped transfer')
    return current


def main():
    with tempfile.TemporaryDirectory(prefix='chestlogger-continuity-') as folder:
        result = run_writers(Path(sys.argv[1]), Path(sys.argv[2]), Path(folder))
        if not result:
            raise ValueError('Writers did not produce CSV output')
    print('26.2 → 26.3 → 26.2 continuity passed: six records, unchanged prefixes, one schema/BOM, balanced groups')


if __name__ == '__main__':
    main()
