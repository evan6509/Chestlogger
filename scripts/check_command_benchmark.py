#!/usr/bin/env python3
"""Check archived/raw opt-in command benchmark samples, outside normal CI."""
import argparse
import csv
import gzip
import math
from collections import defaultdict
from pathlib import Path


def percentile(values, quantile):
    return sorted(values)[math.ceil(len(values) * quantile) - 1] / 1_000_000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    path = args.directory / 'samples.csv'
    if path.exists():
        stream = path.open(newline='')
    else:
        stream = gzip.open(path.with_suffix('.csv.gz'), 'rt', newline='')
    groups = defaultdict(list)
    windows = defaultdict(int)
    with stream:
        for row in csv.DictReader(stream):
            key = row['phase'], row['workload'], int(row['inventories'])
            groups[key].append(int(row['elapsed_ns']))
            if row['phase'] == 'sustained':
                windows[int(row['round'])] += int(row['elapsed_ns'])
    for key, values in groups.items():
        print(f'{key}: n={len(values)} p50={percentile(values, .5):.3f} '
              f'p95={percentile(values, .95):.3f} p99={percentile(values, .99):.3f} ms')
    failures = []
    if len(groups['control', 'empty', 0]) != 600:
        failures.append('empty tracked-set control requires 600 samples')
    for phase in ('command', 'tick'):
        for workload in ('representative', 'stress'):
            for count in (100, 1000, 5000):
                if len(groups[phase, workload, count]) != 600:
                    failures.append(f'incomplete {phase}/{workload}/{count}')
    for workload, count, budget in (('representative', 1000, 2.5), ('stress', 5000, 10)):
        values = groups['command', workload, count]
        if not values or percentile(values, .99) > budget:
            failures.append(f'{workload}/{count}: p99 exceeds {budget} ms')
    if len(groups['sustained', 'representative', 1000]) != 300 or set(windows) != set(range(30)):
        failures.append('sustained test requires 300 commands across 30 one-second windows')
    sustained = groups['sustained', 'representative', 1000]
    if sustained and percentile(sustained, .99) > 2.5:
        failures.append('paced representative/1000: p99 exceeds 2.5 ms')
    if windows:
        maximum = max(windows.values()) / 1_000_000
        mean = sum(windows.values()) / len(windows) / 1_000_000
        print(f'10 commands/s: mean={mean:.3f}, worst second={maximum:.3f} ms of command service/s')
        if maximum > 50:
            failures.append('sustained command service exceeds 50 ms in a one-second window')
    if failures:
        for failure in failures:
            print(f'FAIL: {failure}')
        return 1
    print('PASS: all required workloads and performance budgets (using gross command service time).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
