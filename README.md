# Chest Logger CSV

A Fabric mod with separate builds for **Minecraft 26.2 and 26.3** that records player container placements,
inventory activity, destruction, and automatic inventory changes in one CSV file per world:

```text
<world folder>/ChestLog/chestlog.csv
```

All dimensions and containers append to this same file, including after server
restarts. There are no per-chest text files or daily rotations.

## Install

1. Use Java **25 or newer**, Fabric Loader **0.19.3 or newer**, and the
   Fabric API build for your Minecraft version:

   | Minecraft | Minimum Fabric API (numeric version) | Build pin | Installable JAR suffix |
   | --- | --- | --- | --- |
   | 26.2 | 0.154.2 | 0.154.2+26.2 | `+mc26.2.jar` |
   | 26.3 (development) | 0.164.0 | 0.165.0+26.3 | `+mc26.3.jar` |

2. The published [v1.0.1 release](https://github.com/evan6509/Chestlogger/releases/tag/v1.0.1)
   contains only `chestlogger-csv-1.0.1+mc26.2.jar` (plus `CHANGELOG.md` and
   `SHA256SUMS`). Minecraft 26.3 is not yet a release asset. For 26.3, build
   locally with `-Pminecraft_target=26.3` as described below, or use the
   `chestlogger-csv-26.3` artifact from a successful workflow run of this
   development branch. Put the matching JAR in the server's `mods` folder.
   For singleplayer, put it in your Minecraft instance's `mods` folder.
3. Make sure no other container logger is installed to prevent overlapping logs,
   then restart the server or open your singleplayer world.

Players joining a dedicated server do not need this mod on their clients.
Install exactly one matching game-version JAR. Use the regular JAR, rather than the `-sources.jar`. No configuration is needed.
The CSV begins recording new activity when this mod starts.

## Command

Run `/chestlogger info` to display the installed mod's name and version, such as
`Chest Logger CSV version 1.0.0+mc26.2`. All players can use it without operator
permissions. It also works in the server console as `chestlogger info`.

## What gets logged

| Actions | Meaning |
| --- | --- |
| `PLACE`, `PLACE_CONTENTS` | Successful player placement of a storage block or storage vehicle, plus any initial contents. |
| `OPEN`, `CLOSE` | Opening and closing a supported storage menu. |
| `ADD`, `REMOVE` | Player inventory actions, recipe placement, lectern buttons, and direct interactions such as taking a book from a bookshelf or items from a shelf. |
| `HOPPER_REMOVE`, `HOPPER_ADD` | Successful hopper or hopper-minecart transfer, including loose-item collection. Both endpoints share an `event_id` and identify each other with related coordinates and entity UUIDs. |
| `HOPPER_PLACED_BELOW` | Who placed a hopper directly below storage; shares the hopper's `PLACE` event. |
| `BREAK`, `EXPLOSION`, `DESTROY`, `COMMAND` | Storage removed by a block break, explosion, entity destruction, or command. |
| `BREAK_CONTENTS`, `EXPLOSION_CONTENTS`, `DESTROY_CONTENTS`, `COMMAND_CONTENTS` | Every item variant present when that storage was removed, with a negative quantity delta. Shares the removal event. |
| `COMMAND_ADD`, `COMMAND_REMOVE` | Inventory changes during a command, identifying the command source player when available. Console sources have blank player fields. |
| `STORAGE_ADD`, `STORAGE_REMOVE` | Other observed inventory changes, including processing, dropper/dispenser activity, mob automation, or code changing an inventory without a player action. Player fields are blank. |
| `PICKUP_REMOVE` | A player collected items from a ground item entity; records the collector, item entity UUID, and actual collected quantity. |

Supported block inventories include chests (including copper variants), trapped
chests, barrels, every shulker color, hoppers, dispensers, droppers, furnaces,
blast furnaces, smokers, brewing stands, crafters, decorated pots, chiseled
bookshelves, shelves, jukeboxes, lecterns, and campfires. The shared adapter
supports block entities implementing Minecraft's `Container` interface, with
specific adapters for lecterns and campfires. Other mods using that interface
can be observed, but opaque custom inventory systems need their own adapters.

Storage minecarts and chest boats use stable entity UUIDs as well as their current
coordinates. Horse-family and nautilus inventories have adapters for their menu
containers. Ender chest item activity identifies the private inventory owner in
`storage_owner_uuid`; breaking an ender chest block does not remove those private
items. Initial loaded contents are a baseline, not a new deposit. Unopened loot
is generated only when vanilla normally generates it, including on destruction.

Clicks, shift-clicks, hotbar swaps, drops, drag placement, menu buttons, and recipe
placement are compared before and after the action. Identical stacks are totaled
within each physical inventory, so rearranging slots does not create false item
movement. Names, enchantments, durability, and other components remain distinct.
For double chests, each changed item uses the physical half's coordinates;
open/close records both halves with a shared event ID.

Breaking a filled shulker logs its contents leaving that block's location even
when they remain inside the dropped box. Likewise, destruction rows describe
contents removed with storage, not a claim that every item was deleted. Ground
pickup rows identify who collected an item; they do not automatically establish
which destroyed container originally held it.

Automation is never blamed on a player merely because they have a menu open.
A placed hopper's builder is historical evidence, not proof that they caused
every later transfer. A known explosion or damage source identifies its player;
unknown sources stay blank. Furnace fuel consumption and smelting output are
inventory changes too, so `STORAGE_REMOVE` alone does not establish theft.

Known hopper transfers are balanced and linked. Full destinations, rejected
insertions, and powered hoppers do not produce transfer rows. Other automatic
changes use independently observed inventory deltas; their event IDs do not
prove that two inventories exchanged items. A stack traveling chest → hopper →
shulker normally creates one linked event for each item at each hop, so active
farms can grow the CSV quickly.

See [the storage coverage audit](docs/storage-coverage.md) for the implementation,
verification, and remaining boundaries.
See [command audit performance](docs/command-audit-performance.md) for the measured
inventory/command envelope and the reproducible benchmark.

## CSV columns

| Column | Meaning |
| --- | --- |
| `event_id` | Shared UUID for rows belonging to the same observed action, removal, or hopper transfer. |
| `timestamp` | ISO date and time with UTC offset, to whole seconds. |
| `date`, `time` | Separate date and clock time (`HH:mm:ss`), easy to filter in Excel. |
| `timezone` | Server computer's time zone, such as `America/Chicago`. |
| `dimension` | Dimension ID, such as `minecraft:overworld`; custom IDs are retained. |
| `container` | Block or entity ID, such as `minecraft:chest`, `minecraft:hopper_minecart`, or `minecraft:item`. |
| `x`, `y`, `z` | Separate numeric coordinates; moving storage uses its current block position. |
| `player` | Known actor's account name; blank for automatic or unknown activity. |
| `player_uuid` | Stable actor identity even if the name changes; blank when no player is known. |
| `action` | Observed action from the table above. |
| `item_id` | Item ID, such as `minecraft:diamond`; blank for non-item events. |
| `item_name` | Display name, including custom names. |
| `quantity` | Positive number of items added or removed. |
| `quantity_delta` | Positive for additions, negative for removals. |
| `item_data` | Item JSON with count normalized to 1, preserving item components. |
| `related_x`, `related_y`, `related_z` | Other endpoint's coordinates for a hopper transfer; hopper coordinates for `HOPPER_PLACED_BELOW`; otherwise blank. |
| `entity_uuid` | Stable UUID of a storage vehicle, animal, or ground item; blank for blocks. |
| `related_entity_uuid` | Other endpoint's UUID in a hopper transfer when it is an entity. |
| `storage_owner_uuid` | Private ender inventory owner; blank for shared inventories. |

Timestamps use the **server computer's time zone**, not Minecraft's day/night
clock. A UTC offset is always included so times remain unambiguous during
daylight saving changes. Rows are written in action order and flushed after
each action. The file uses UTF-8 with an Excel encoding marker, commas, and CRLF
record endings. Quotes, commas, and line breaks in text are escaped correctly.
Text beginning with spreadsheet formula characters receives a leading
apostrophe; signed coordinates and quantities remain numeric.

## Use in Excel

Open `chestlog.csv`, or choose **Data → From Text/CSV** and select UTF-8 and comma
as the delimiter. Create a table to enable column filters.

If Excel displays the `time` column as minutes, seconds, and fractions, select
the column and use **Format Cells → Custom → `hh:mm:ss`** to show hours, minutes,
and seconds. CSV files store values, not Excel display formats. New rows use
whole seconds; existing rows retain their original precision.

For example, filter `action` to `REMOVE`, `item_id` to `minecraft:diamond`, and
`player` to the name you want. Filter `dimension`, `x`, `y`, and `z` together to
select a specific chest. Sum `quantity` to count withdrawals, or sum
`quantity_delta` to find the net recorded inventory change over the selected
period. Include all item actions when accounting for a particular inventory. Select
its dimension and coordinates, or its entity/private-owner UUID, to avoid
counting transfers and later ground pickups as withdrawals from the same store.
Filtering by a player excludes activity with no known actor. Inventory balances
still require a known starting inventory and no changes outside logging scope.

To trace hopper movement, filter to `HOPPER_REMOVE` and `HOPPER_ADD`, then filter
an `event_id` to see both ends of one transfer. Match a hopper or destination's
dimension and coordinates to earlier `PLACE` rows to see who placed it. If a
block was broken and replaced, use the placement preceding the transfer, not an
older placement at the same coordinates. `HOPPER_PLACED_BELOW` also links the
player directly to the affected container when a hopper is placed beneath it.
This records who built the setup; it does not prove that the placer caused every
later transfer. Blocks placed before logging started have no placement history.
The CSV now appends three identity columns. A valid previous 21-column log is
upgraded automatically at startup, preserving its existing fields and leaving an
exact backup named `chestlog.csv.schema21-<UUID>.bak`. Old rows receive blank
identity fields; subsequent rows use the new 24-column header. Other schemas or
malformed files are left untouched.

Copy the CSV before editing it in Excel. Keep the active log's header and rows
intact; saving spreadsheet changes over it while the server is running can
disrupt logging. Logging failures appear in the server console. If an existing
CSV has incompatible columns, malformed quoting, or an unfinished final row,
the mod leaves it untouched and disables logging until the file is repaired and
the server is restarted. Existing records are checked once at startup; valid
quoted fields containing line breaks remain supported.

The [sample CSV](examples/chestlog.csv) contains 76 fictional records covering
every current action type. Its [scenario guide](examples/README.md) explains
the rows, including the hopper route, private storage, automation, destruction,
commands, and ground pickups.

## Build and verify

With Java 25 installed, the default target is 26.2. `targets.json` pins the
Minecraft/Loader/API matrix; direct dependency overrides are rejected. Loom
1.17.14 and Gradle 9.6.1 are shared by both targets.

Run each target from a **separate checkout or copied source workspace**, with
separate Gradle homes and project caches (Loom also stores project-local state).
Run the following inside each workspace, replacing `26.2` with `26.3` for the
second copy:

```sh
GRADLE_USER_HOME="$PWD/.gradle/user-26.2" ./gradlew --project-cache-dir .gradle/project-26.2 --no-daemon --max-workers=1 -Pminecraft_target=26.2 clean build runGametest
CHESTLOGGER_TARGET=26.2 python3 scripts/verify_mod_jar.py build/26.2/libs
```

Do not run the two Gradle builds concurrently. Installable artifacts are
`build/26.2/libs/chestlogger-csv-<version>+mc26.2.jar` and
`build/26.3/libs/chestlogger-csv-<version>+mc26.3.jar`. Each declares exactly its
Minecraft version. The API minimum uses the numeric API version because Fabric
Loader ignores the `+26.x` suffix during dependency comparison.

The 26.3 build pins Fabric API `0.165.0+26.3` while retaining the backward-
compatible numeric minimum `0.164.0`. The initial port was tested on 0.164;
an independent clean rebuild on 2026-10-24 also passed with the refreshed 0.165
pin. Both 26.2 and 26.3 passed their GameTests and package audits; details are
in the dated follow-up in the validation record.

`build` runs a standalone CSV verification
suite covering inventory deltas, escaping, Unicode, formula protection, flush,
append after restart, schema validation, and concurrent writes. `runGametest`
runs a disposable Minecraft test server to verify player menu actions, component
preservation, simultaneous players, physical double-chest halves, storage
placement and cancellation, hopper chains and minecarts, failed insertions,
processing inventories, direct bookshelf/shelf/jukebox withdrawals, lectern
buttons, private ender storage, chest boats and donkey storage, filled-container
destruction, naturally generated loot on destruction, commands, explosions,
ground pickups, and silent inventory mutation. The CSV suite also verifies
legacy schema migration and its exact backup. Test classes and the test mod are
not included in the installable JAR.

The tests use simulated server players. Manual testing with connected clients
and a production modpack remains separate from these checks.

To verify shared CSV compatibility after compiling both isolated workspaces:

```sh
python3 scripts/verify_csv_continuity.py /path/to/26.2/build/26.2 /path/to/26.3/build/26.3
```

This runs separate JVMs in 26.2 → 26.3 → 26.2 order against one temporary CSV,
checking unchanged historical bytes, a single schema/header/BOM, cross-dimension
records, balanced event groups, and malformed-file rejection. It does not open
Minecraft worlds or establish that world downgrades are safe. CI requires both
builds and this continuity check before the release job can run. To verify the
combined download folder, use `python3 scripts/verify_mod_jar.py --pair release`.
See [dual-target validation](docs/dual-target-validation.md) for dependency evidence
and the implementation verification record.

## Automated releases

The existing published `v1.0.1` contains only the 26.2 JAR. The paired-release
workflow on this development branch describes future releases; it does not
change the assets or history of that release.

After this workflow is merged, every successful push to `main` (including a PR
merge) builds both targets, runs their CSV and Minecraft server tests,
verifies CSV continuity across versions, and publishes a
[GitHub release](https://github.com/evan6509/Chestlogger/releases). Each future paired release
contains both installable JARs, `CHANGELOG.md`, and `SHA256SUMS`. The release page
shows the same changelog, generated from commit messages and merged PRs since the
previous release, plus installation requirements and the source commit.

Release versioning starts at `v1.0.0`; each new release increments the patch
version from the existing release history. Both future paired JARs use the same
release version in their embedded version and filename, with their respective
Minecraft suffixes. Rerunning a commit reuses its release instead of creating a duplicate.
Failed uploads remain drafts; publication waits until the uploaded files have
been downloaded and verified against the build. Main pushes are queued so
version selection and publication do not race. Retrying an older draft leaves a
newer published stable version marked as **Latest**.

PR and manual workflow runs build and test without publishing. Development work
stays on `codex/development` and reaches `main` through a reviewed PR. No extra
GitHub secret is required; the workflow uses the repository's `GITHUB_TOKEN`.
Releases have the same private visibility as the repository.

Local builds keep using `mod_version` from `gradle.properties`. To check a
specific release version locally, use:

```sh
CHESTLOGGER_VERSION=1.0.1 ./gradlew --max-workers=1 -Pminecraft_target=26.2 build
python3 -m unittest discover -s scripts -p 'test_*.py'
CHESTLOGGER_VERSION=1.0.1 python3 scripts/verify_mod_jar.py
```

## Credits

See `NOTICE` and `LICENSE` for attribution and the MIT license.
