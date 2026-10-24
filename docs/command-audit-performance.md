# Command audit performance

The command wrapper audits every loaded, tracked inventory, including commands
implemented by other mods. This document records the supported measured envelope;
it does not infer the storage count or command rate of a live server.

## Reproduce

Use Java 25 on a desktop with no competing builds or game server:

```sh
./gradlew --no-daemon --max-workers=1 runCommandBenchmark \
  -PbenchmarkOutput=build/command-benchmark-local
python3 scripts/check_command_benchmark.py build/command-benchmark-local
```

This starts a disposable Fabric GameTest server under `build/command-benchmark-server`.
It does not connect to a live world. The benchmark is opt-in; ordinary `build`
and `runGametest` have no wall-clock assertions. Test and benchmark classes stay
outside the installable JAR. The checker deliberately uses **gross command service
time**, including command dispatch, as a conservative bound on incremental audit
time. It checks that the full matrix is present before evaluating the budgets.

## Work and method

`StorageCommandMixin` wraps `Commands.performCommand`. For each command,
`prepareCommand` visits all tracked storage. `Scope.prepare` first calls `observe`
to settle pending changes as `STORAGE_*`, then captures a before snapshot. On exit,
`Scope.close` captures an after snapshot and records `COMMAND_ADD`/`COMMAND_REMOVE`
with the source player, or blank actor fields for a console source. A no-op command
originally read **3 × N × 27 slots**. The optimized preparation reuses the
settling snapshot as its before state, giving **2 × N × 27 slots**. The separate
end-of-tick fallback reads
**N × 27 slots**. Neither scan loads chunks or generates unopened loot. Mutation
notifications during a command can cause further observations. Reentrant
`performCommand` calls also prepare scopes and scan again. Minecraft queues their
command bodies in the outer execution context; those bodies can execute after
the reentrant wrapper has returned but before the outer wrapper closes. Vanilla
`execute run` also executes within that enclosing audit. Explicit nested audit
scopes settle parent deltas at their boundary and preserve their own actors.

Before optimization, every snapshot allocated a map and a count-normalized stack
key for every populated slot, even when nothing changed. Each of two command
diffs built a union of item keys, hashing item components repeatedly. The regular
tick paid for one snapshot and one diff. In this fixture's unchanged,
distinct-variant case, each populated slot caused three count-normalized key/stack
copies and **11 top-level item/component hashes per command**: three snapshot
merges, plus four hashes per diff (insert both key sets and look up both totals).
A regular tick caused one copy and five hashes per populated slot. That is 21,000
normalized copies / 77,000 component hashes per command at 1,000 × 7 populated
slots, versus 7,000 / 35,000 per regular tick. Each hash visits the stack's component map and uses each component's hash implementation; vanilla
`ItemContainerContents` caches its nested-content hash. CSV JSON encoding and flushing occur only for actual deltas, so
they are absent from the ordinary-command measurements.

The benchmark invokes the **real mixed-in `Commands.performCommand`** on the
server thread with a pre-parsed, benign `chestlogger info` command and suppressed
output. The empty tracked-set control uses the same invocation. Parsing and
network input are outside the timed interval. No handwritten substitute for the
audit method or disabled logger is used.

Each fixture consists of 100, 1,000, or 5,000 vanilla `ChestBlockEntity` objects,
27 slots each, attached to the disposable server level and registered through
`StorageAudit.acknowledge`. They are detached from chunk terrain and block ticking:
this isolates the actual storage adapter, snapshot, diff, and command wrapper
from world simulation. Each inventory and slot owns its stack; rich components
are created independently across inventories.

* Representative: 7/27 slots (25.9%) populated. Four ordinary stacks of 32
  (diamond, cobblestone, iron ingot, oak log); three diamond stacks of 16 with
  distinct custom names, four lore lines, and two custom-data string fields.
* Stress: 27/27 populated, all rich stacks, eight custom-data string fields and
  three separately named nested emerald stacks per slot, in addition to name/lore.
  This is a bounded full-inventory component stress case, not a maximum-size NBT
  or arbitrary modpack stress guarantee.

Every command and tick case warms for at least 100 operations and two seconds,
then records three consecutive rounds of 200 individually timed operations
(600 samples). Percentiles use nearest rank; GC pauses and scheduling outliers
are retained. ThreadMXBean allocated bytes and process-wide GC count/time are
recorded in every raw sample. Allocation counters include small measurement
bookkeeping costs; they exclude allocations on other threads. GC collection time
is JVM-wide evidence, not necessarily a pause wholly inside the timed command.

The sustained case lasts 30 seconds: 300 commands at scheduled 100 ms intervals,
with the production end-of-tick scan invoked at 50 ms intervals. Pacing and tick
scan service are outside each command timer. The checker sums each ten-command
window and enforces 50 ms/second on the worst window, as well as reporting the
mean. This describes **incremental command overhead**; regular tick costs are
reported separately. General world ticking, clients, terrain loading, other mods,
and mutation logging/CSV I/O are outside this workload.

## Measurements

Measured 2026-10-21 on `evan-desktop`, Intel Core i5-9400F (6 physical cores, 2.90 GHz base,
4.10 GHz maximum), Ubuntu 26.04.1 LTS x86_64, kernel 7.0.0-38-generic,
Temurin OpenJDK 25.0.4.1+1, G1 GC, `-Xms1G -Xmx2G`; Minecraft 26.2,
Fabric Loader 0.19.3, Fabric API 0.154.2+26.2. No competing build/test process
was run during measurement. CPU frequency and OS scheduling were not pinned.

Baseline production revision: `a49012d1879db331e174ac35dbe7bb32db32cd96`.

Raw samples, summaries, JVM/host metadata, logs, and production patches are in
[`docs/benchmarks/command-audit`](benchmarks/command-audit/). `baseline` uses
the revision above; `after-1` through `after-3` retain failed optimization
attempts. `after-4` and `after-5` are two fresh JVM runs of the final production
code, both passing. The harness was identical in every run (SHA-256
`2e97baa5f8fd97f755cc1f69541a34c6ca1c315d0b97871e0e853a513471dde8`).
Per-file production hashes and patches against the baseline identify each stage.

Command latency below is **p50 / p95 / p99 in milliseconds**. The final column
uses `after-5`, the slower final fork for all six command-case p99s.

| Workload | Inventories | Baseline | Optimized |
| --- | ---: | ---: | ---: |
| representative | 100 | 2.848 / 2.902 / 2.972 | 0.029 / 0.032 / 0.042 |
| representative | 1,000 | 30.683 / 32.719 / 35.279 | 0.350 / 0.375 / 0.403 |
| representative | 5,000 | 159.960 / 172.911 / 178.167 | 3.083 / 3.593 / 3.673 |
| stress | 100 | 24.725 / 26.095 / 33.956 | 0.059 / 0.064 / 0.089 |
| stress | 1,000 | 235.072 / 265.728 / 274.346 | 0.827 / 0.889 / 0.937 |
| stress | 5,000 | 1209.243 / 1337.370 / 1392.166 | 7.059 / 7.326 / 8.338 |

Budgets use unrounded gross service times, including dispatch. The checker also
enforces the 2.5 ms p99 limit on the paced case, where idle intervals make
service slower than warm bursts. Both final forks pass every limit.

| Acceptance metric | Limit | Baseline | Final fork 1 | Final fork 2 |
| --- | ---: | ---: | ---: | ---: |
| 1,000 representative p99 (ms) | 2.5 | 35.279 | 0.375 | 0.403 |
| 1,000 paced p99 (ms) | 2.5 | 34.485 | 1.979 | 1.889 |
| 5,000 stress p99 (ms) | 10 | 1392.166 | 6.813 | 8.338 |
| Worst ten-command second (ms/s) | 50 | 322.891 | 17.147 | 18.255 |

Mean command service at 10 commands/s was 315.757 / 16.254 / 17.613 ms/s
(baseline / final fork 1 / final fork 2). Regular tick work is **additional**;
the command timers do not include it. Key tick-scan comparisons (p50/p95/p99,
ms) are below; all six tick cases are retained in every raw summary.

| Regular tick workload | Baseline | Optimized fork 2 |
| --- | ---: | ---: |
| 1,000 representative | 13.326 / 14.272 / 14.980 | 0.138 / 0.156 / 0.169 |
| 5,000 stress | 543.402 / 565.499 / 569.362 | 3.258 / 3.401 / 3.538 |

Allocation/GC evidence for **600 measured commands**, excluding warmup:

| Case | Mean bytes/command before → after | Collections / GC time before → after |
| --- | ---: | ---: |
| 1,000 representative | 47,756,808 → 124,840 | 45 / 116 ms → 0 / 0 ms |
| 5,000 stress | 1,816,143,200 → 543,208 | 2270 / 18192 ms → 2 / 12 ms |

The empty-set command control p99 was 0.001334 ms before and 0.001058 /
0.001413 ms in the final forks; no percentile subtraction is needed to pass.
Unchanged snapshots now create no normalized item keys or component hashes.

The first cache-only attempt missed stress p99 (15.024753 ms). Reusing the
settling read still missed stress (12.472720 ms) and narrowly missed paced
p99 (2.500656 ms). Compact copied component state still missed stress
(12.744649 ms). The final copy-on-write patch tokens passed in both forks.
All attempts retain their complete samples; no slow samples were removed.

Archived final results can be checked without Minecraft:

```sh
python3 scripts/check_command_benchmark.py docs/benchmarks/command-audit/after-4
python3 scripts/check_command_benchmark.py docs/benchmarks/command-audit/after-5
```

To remeasure the baseline in a disposable copy of this commit, reverse only
the archived production patch, leaving the exact benchmark harness in place:

```sh
gzip -dc docs/benchmarks/command-audit/after-5/production.patch.gz | git apply --reverse
# Then run the benchmark command above with a distinct benchmarkOutput directory.
```


## Optimization and correctness boundary

Each `Storage` owns a snapshot cache. Every call reads the entire inventory and
compares stack identity, separately saved counts, and the component patch identity.
Building totals still makes private count-normalized `ItemKey` copies. Minecraft
26.2's `PatchedDataComponentMap.copy()` marks the source patch copy-on-write;
`ensureMapOwnership()` replaces that backing map before set/remove/apply/restore/
clear operations. Read-only accessors expose the source component map and patch
identity without allocating or modifying them. With the same stack, its item and
component prototype are final; a changed stack reference always rebuilds. Thus a
patch identity is an exact change token, not a component hash. Silent count and
component edits remain detectable. Even a logically equal replacement can rebuild;
the exact item/component diff still prevents false movement rows.

Equal states reuse immutable totals and `InventoryDiff` returns immediately when
both snapshots are the same map. Scope preparation reuses its settling snapshot
as the before state; suspended or unavailable observations retain explicit capture.
Command selection, mutation hooks, source attribution, and removal/loot behavior
are unchanged. There is no command-name filter or skipped modded command path.

The cache retains stack references, saved counts, and immutable patch identities
in arrays alongside the existing normalized keys. It belongs to `Storage` and is
reclaimable with it after unloading/forgetting; it adds no static owner map.
Changed inventories still incur snapshot construction, exact component comparison,
diffing, serialization, and CSV flush costs. This benchmark bounds ordinary
commands with stable contents, not mutation-heavy command throughput or arbitrary
component payloads. Deferred work outside a command's enclosing scope remains
outside its synchronous attribution boundary.

The regression suite exercises full slot reads, immutable snapshot reuse,
silent count/component edits, slot rearrangement and clearing, pending automation,
explicit nested scopes with distinct actors, Minecraft's queued nested commands,
`execute run`, vanilla `item`/`data` mutations, console actors, component JSON,
and `fill`/`clone` inventory removal. These complement the existing storage,
private inventory, resizing, hopper, destruction, loot, and failure-path tests.

## Supported envelope and validation

On this desktop/JDK/heap, stable vanilla 27-slot storage is measured within the
budgets at 1,000 inventories with the specified 7-slot mixture and 10 ordinary
commands/s, and within the per-command 10 ms budget at 5,000 full rich inventories.
The 50 ms/second throughput budget was tested at 1,000 representative inventories;
it is not a claim about that rate at 5,000 stress inventories. Storage beyond
these counts, larger/different component payloads, different CPUs/heaps/collectors,
custom containers returning fresh stack objects, and active mutation/CSV throughput
require their own measurement. The actual server's storage count and modpack were
not established; no live world/server was accessed. This closes the concern only
within the measured envelope. Work still scales with tracked slot count.

The component token relies on Minecraft 26.2's copy-on-write/immutable-component
contract, which the original normalized `ItemKey` snapshots also rely on. Direct
native set/remove/apply/restore/clear/set-all mutations are regression-tested.
Opaque custom inventories or code bypassing that contract are outside this claim.

Before production changes, the deterministic unchanged-snapshot test failed;
command attribution tests passed. The two-read regression then failed against
the cache-only implementation before preparation was optimized. Both now pass.
The final focused suite passes all eight tests, including same-count item/component
changes and bulk component APIs. Full validation passed on Java 25:

```sh
./gradlew --no-daemon --max-workers=1 runGametest \
  '-PgameTestFilter=chestlogger_csv_tests:command_audit_game_tests_*'
./gradlew --no-daemon --max-workers=1 build runGametest
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/verify_mod_jar.py
```

The full GameTest suite passed 45/45, Python passed 21/21, and the standalone CSV
suite and installable JAR verification passed. Timing checks remain opt-in.
