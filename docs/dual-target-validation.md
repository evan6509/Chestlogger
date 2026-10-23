# Minecraft 26.2 / 26.3 implementation validation

Verified 2026-10-23 from clean `codex/development`, starting at
`fd6ef9be3b958c328a6aa04e228c7d80c1dba2c3`. No open PR was attached to that
branch before edits. No production logging implementation or schema was changed.

## Changed files

- Build: `build.gradle`, `gradle.properties`, `targets.json`,
  `src/main/resources/fabric.mod.json`.
- CI/release: `.github/workflows/release.yml`, `scripts/publish_release.py`,
  `scripts/verify_mod_jar.py`, `scripts/test_release.py`.
- Verification: `scripts/test_targets.py`, `scripts/test_workflow.py`,
  `scripts/test_continuity.py`, `scripts/verify_csv_continuity.py`,
  `src/test/java/com/chestlogger/csv/CsvTests.java`,
  `src/test/java/com/chestlogger/csv/LoaderVersionTests.java`,
  `src/test/java/com/chestlogger/csv/CsvContinuityWriter.java`.
- Documentation: `README.md`, `docs/dual-target-validation.md`.

## Pinned dependencies and evidence

| Target | Loader | Fabric API | Minecraft metadata | API metadata minimum |
| --- | --- | --- | --- | --- |
| 26.2 | 0.19.3 | 0.154.2+26.2 | `26.2` | `>=0.154.2` |
| 26.3 | 0.19.3 | 0.164.0+26.3 | `26.3` | `>=0.164.0` |

Both use stable Loom **1.17.14**, Gradle **9.6.1**, and Java **25**. The original
26.2 dependency baseline is unchanged. Both builds succeeded with Loader 0.19.3;
upgrading to the Develop recommendation of 0.19.5 was unnecessary.

Official sources checked during implementation:

- [Fabric API Releases](https://github.com/FabricMC/fabric-api/releases): the
  earlier search index/planning evidence showed 0.161.0+26.3. The live release
  listing now includes 0.164.0+26.3. This discrepancy is recorded rather than
  treating cached search results as authoritative.
- [Fabric Maven directory](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.164.0%2B26.3/)
  and [POM](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.164.0%2B26.3/fabric-api-0.164.0%2B26.3.pom):
  direct HTTPS requests returned HTTP 200 for the POM and binary JAR, published
  October 19. Fresh isolated Gradle resolution retrieved the same JAR.
- [Develop](https://fabricmc.net/develop/) obtains API versions from
  [Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml),
  whose final 26.3 coordinate was 0.164.0+26.3. Its JavaScript recommends Loom
  1.18-SNAPSHOT. We retained the tested stable Loom instead.
- [Loader metadata for 26.3](https://meta.fabricmc.net/v2/versions/loader/26.3)
  includes 0.19.3; the downloaded API's own `fabric.mod.json` requires
  `fabricloader >=0.19.3`, `java >=25`, and `minecraft ~26.3-`.
- [Fabric's 26.3 announcement](https://www.fabricmc.net/2026/09/15/263.html)
  recommends Loom 1.17 / Gradle 9.6 at launch; the actual builds demonstrate this
  repository's stable patch versions work on both games.

Downloaded and Gradle-resolved 26.3 API JAR SHA-256:
`5a75198715a50a86787658617b03b97da9936d32df04896748c12135ba709de3`.
POM SHA-256:
`f39bdf72241b13310117214672ef6afd49fe89c928710aca77b658c41242385c`.

`LoaderVersionTests` exercises Fabric Loader 0.19.3's actual `Version` and
`VersionPredicate` implementations: build suffixes compare equal, exact Minecraft
predicates reject the other game, and the numeric API minima accept/reject the
expected versions. No dependency constraint relies on an API build suffix.

## Red / green record

Tests were added and run before implementation:

- `python3 -m unittest discover -s scripts -p test_targets.py -v`: red because
  unsupported targets were accepted, target resource output was absent, and
  `verify_pair` was missing. Green after implementing selection, metadata, and
  paired packaging. Later expanded for wrong-target/missing/unexpected artifacts.
- `python3 -m unittest discover -s scripts -p test_release.py -v`: paired-release
  fixtures failed against the single-JAR publisher (one failure, eight errors).
  Green after requiring both JARs in changelog, checksums, upload, downloaded-byte
  verification, and published-rerun checks. Final release suite: **24 passed**.
- `./gradlew --no-daemon --max-workers=1 -Pminecraft_target=26.2 csvTest`:
  red compilation for missing `LoaderVersionTests`; green in both full builds
  after adding the Loader semantics checks.
- `python3 -m unittest discover -s scripts -p test_continuity.py -v`: red for
  missing continuity harness, then green after implementation.
- `python3 -m unittest discover -s scripts -p test_workflow.py -v`: red for the
  missing matrix/release gate, then green after updating CI.

## Exact validation commands and results

Commands ran from the task worktree. Sources were copied without `.git`, caches,
outputs, or Python bytecode into `../chestlogger-validation/26.2` and `26.3`.
Each has a separate Gradle user home and project cache. Builds ran sequentially
with one worker, using Temurin **25.0.4.1+1-LTS**.

```sh
GRADLE_USER_HOME="$PWD/../chestlogger-validation/home-26.2" ../chestlogger-validation/26.2/gradlew -p ../chestlogger-validation/26.2 --project-cache-dir ../chestlogger-validation/cache-26.2 --no-daemon --max-workers=1 -Pminecraft_target=26.2 clean build runGametest
GRADLE_USER_HOME="$PWD/../chestlogger-validation/home-26.3" ../chestlogger-validation/26.3/gradlew -p ../chestlogger-validation/26.3 --project-cache-dir ../chestlogger-validation/cache-26.3 --no-daemon --max-workers=1 -Pminecraft_target=26.3 clean build runGametest
```

| Target | Result | Gradle tasks | GameTests |
| --- | --- | --- | --- |
| 26.2 | BUILD SUCCESSFUL, 1m 6s | 13 executed | All 45 required tests passed |
| 26.3 | BUILD SUCCESSFUL, 1m 12s | 13 executed | All 45 required tests passed |

Both also printed successful CSV initialization, Loader version predicate, and
CSV suite results (deltas, escaping, Unicode, formulas, flush, restart, malformed
records, large fields, concurrent appends). Expected failure-injection tests log
write errors while verifying logging disables and state clears.

```sh
CHESTLOGGER_TARGET=26.2 python3 scripts/verify_mod_jar.py ../chestlogger-validation/26.2/build/26.2/libs
CHESTLOGGER_TARGET=26.3 python3 scripts/verify_mod_jar.py ../chestlogger-validation/26.3/build/26.3/libs
python3 scripts/verify_mod_jar.py --pair ../chestlogger-validation/release
python3 scripts/verify_csv_continuity.py ../chestlogger-validation/26.2/build/26.2 ../chestlogger-validation/26.3/build/26.3
CHESTLOGGER_TEST_WORKSPACES="$PWD/../chestlogger-validation" python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

All verifiers passed. Continuity ran three separate JVMs, producing six rows,
one BOM/header and 24 columns, unchanged previous bytes (including `item_data`),
paired UUIDs with balanced quantities, and cross-dimension records. Each process
also rejected malformed files without modifying them. The Python suite uses the
same isolated workspaces for real Gradle selector/resource tests. Final result: **29 tests passed** (28.740s). All release
publication calls in tests are mocked.

## Artifact identity

- `build/26.2/libs/chestlogger-csv-1.0.0+mc26.2.jar`:
  `id=chestlogger_csv`, `version=1.0.0+mc26.2`, exact `minecraft=26.2`.
- `build/26.3/libs/chestlogger-csv-1.0.0+mc26.3.jar`:
  `id=chestlogger_csv`, `version=1.0.0+mc26.3`, exact `minecraft=26.3`.

Both declare Java `>=25`, Loader `>=0.19.3`, and the API minimum in the table.
The verified JARs were also copied from isolated builds to the task worktree's
ignored `build/<target>/libs` folders. Test classes/resources are excluded;
required mixin classes, LICENSE, and NOTICE are present. Release versions retain
one shared `CHESTLOGGER_VERSION` and add the game suffix to each JAR independently.

## Hook and data audit

`javap -p -s` against both freshly resolved Minecraft merged JARs confirmed
unchanged descriptors for `AbstractContainerMenu.clicked` with `ContainerInput`,
`ServerPlayer.openMenu`/`doCloseContainer`, hopper `ejectItems`,
`tryTakeInItemFromSlot`, both intercepted `addItem` overloads, `BlockItem.place`,
`CompoundContainer.container1`/`container2`, `ItemStack.CODEC`,
`hashItemAndComponents`, and `isSameItemSameComponents`. Required mixins remain
required with `defaultRequire=1`; both GameTest runs loaded and exercised them.
The pull wrapper retains the source-slot argument rather than guessing a chest
half. No reflection or optional injection fallback was added.

Reviewed `ChestLoggerCsv`, `Containers`, `InventorySnapshot`, `HopperTransfer`,
and the mixins: world-root CSV lookup, server start/stop closure, component-aware
count-normalized keys, registry-aware ItemStack encoding, physical inventory
halves, successful placement checks, and linked event IDs remain unchanged.
Hopper accounting logs only positive successful movement whose observed additions
sum to the moved amount; failed insertions and suspended automation do not become
player actions. Existing hopper chain, double chest, components, failed insertion,
private storage, lifecycle, destruction, and command tests passed on both games.

## Remaining limitations

No manual dedicated/integrated client smoke, existing-world upgrade/downgrade,
production server, modpack test, or performance benchmark was run. Only the
required disposable GameTest servers were launched; no existing server or world
was opened or modified. CSV continuity is a file compatibility check, not evidence
that Minecraft worlds can be downgraded. GitHub Actions itself was not run here;
workflow YAML was parsed and the matrix/release gates checked locally. Gradle
reports existing deprecations for Gradle 10; this task retains Gradle 9.6.1.
No PR, merge, tag, or release is part of this implementation.
