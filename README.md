# Chest Logger CSV

A Fabric mod for **Minecraft 26.2** that records player container activity and
automatic hopper transfers in one CSV file per world:

```text
<world folder>/ChestLog/chestlog.csv
```

All dimensions and containers append to this same file, including after server
restarts. There are no per-chest text files or daily rotations.

## Install

1. Use Minecraft **26.2**, Java **25 or newer**, Fabric Loader **0.19.3 or newer**,
   and Fabric API **0.154.2+26.2 or newer for Minecraft 26.2**.
2. Download the latest JAR from
   [GitHub Releases](https://github.com/evan6509/Chestlogger/releases/latest)
   and put it in the server's `mods` folder.
   For singleplayer, put it in your Minecraft instance's `mods` folder.
3. Make sure no other container logger is installed to prevent overlapping logs,
   then restart the server or open your singleplayer world.

Players joining a dedicated server do not need this mod on their clients.
Use the regular JAR, rather than the `-sources.jar`. No configuration is needed.
The CSV begins recording new activity when this mod starts.

## Command

Run `/chestlogger info` to display the installed mod's name and version, such as
`Chest Logger CSV version 1.0.0+mc26.2`. All players can use it without operator
permissions. It also works in the server console as `chestlogger info`.

## What gets logged

- `OPEN` and `CLOSE`: a player successfully opens or closes a container.
- `ADD`: items enter a container because of a player inventory action.
- `REMOVE`: items leave a container because of a player inventory action.
- `HOPPER_REMOVE` and `HOPPER_ADD`: a successful automatic transfer between
  supported block containers. The source removal and destination addition share
  one `event_id`; both rows have blank player fields. Each row's
  `related_x`, `related_y`, `related_z` identifies the other container.
- `BREAK`: a player successfully breaks a container.
- `HOPPER_PLACED_BELOW`: a player successfully places a hopper directly below
  a container; the hopper position appears in `related_x`, `related_y`, `related_z`.

Item changes are recorded as they happen, including ordinary clicks,
shift-clicks, hotbar swaps, drops, and drag placement. Each item variant changed
by one action gets its own row. Identical stacks are totaled within the physical
container, so rearranging items within a single container does not log a false
addition or removal. Different names, enchantments, durability, and other item
components remain distinct.

Chests, trapped chests, barrels, shulker boxes, hoppers, dispensers, and droppers
are supported. Modded containers using Minecraft's
`RandomizableContainerBlockEntity` and exposing it through their menu slots may
also work. Hopper pulls and pushes between these block containers are logged,
including chest-to-hopper, hopper-to-chest, and hopper-to-hopper transfers.
Ender chests, entity inventories (including hopper minecarts), furnaces,
loose-item pickup, explosions, commands, and other automatic transfers are
outside this logger's scope. Automation is not attributed to whichever player
happens to have a chest open. Full destinations, rejected insertions, and powered
hoppers do not create transfer rows.

For a top chest feeding a hopper that feeds a lower chest, each item produces
two transfer events: top chest to hopper, then hopper to lower chest. Each event
has two balanced rows. Vanilla hoppers normally transfer one item at a time, so
a full stack moving through both hops produces 256 transfer rows. These are
written immediately; automated systems can grow the CSV quickly.

For double chests, each item change uses the coordinates of the physical half
that changed. Item rows are not duplicated for both halves. An open/close action
has a row for each half with the same `event_id`. Moving a stack between halves
records a removal from one half and an addition to the other.

## CSV columns

| Column | Meaning |
| --- | --- |
| `event_id` | Shared UUID for rows belonging to the same player action or hopper transfer. |
| `timestamp` | ISO date and time with UTC offset, to whole seconds. |
| `date`, `time` | Separate date and clock time (`HH:mm:ss`), easy to filter in Excel. |
| `timezone` | Server computer's time zone, such as `America/Chicago`. |
| `dimension` | Dimension ID, such as `minecraft:overworld`; custom IDs are retained. |
| `container` | Block ID, such as `minecraft:chest` or `minecraft:barrel`. |
| `x`, `y`, `z` | Separate numeric coordinates of the container. |
| `player` | Player's account name; blank for automatic transfers. |
| `player_uuid` | Stable player identity even if the name changes; blank for automatic transfers. |
| `action` | `OPEN`, `CLOSE`, `ADD`, `REMOVE`, `HOPPER_ADD`, `HOPPER_REMOVE`, `BREAK`, or `HOPPER_PLACED_BELOW`. The `HOPPER_` item actions identify the transfer cause. |
| `item_id` | Item ID, such as `minecraft:diamond`; blank for non-item events. |
| `item_name` | Display name, including custom names. |
| `quantity` | Positive number of items added or removed. |
| `quantity_delta` | Positive for additions, negative for removals. |
| `item_data` | Item JSON with count normalized to 1, preserving item components. |
| `related_x`, `related_y`, `related_z` | Other container's coordinates for a hopper transfer; hopper coordinates for placement events; otherwise blank. |

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
period. Include `HOPPER_ADD` and `HOPPER_REMOVE` when accounting for automation;
filtering by a player excludes automated transfers. Inventory balances still
require a known starting inventory and no changes outside the logging scope.

To trace hopper movement, filter to `HOPPER_REMOVE` and `HOPPER_ADD`, then filter
an `event_id` to see both ends of one transfer. The CSV columns have not changed,
so existing valid log files continue accepting new rows after upgrading.

Copy the CSV before editing it in Excel. Keep the active log's header and rows
intact; saving spreadsheet changes over it while the server is running can
disrupt logging. Logging failures appear in the server console. If an existing
CSV has incompatible columns, malformed quoting, or an unfinished final row,
the mod leaves it untouched and disables logging until the file is repaired and
the server is restarted. Existing records are checked once at startup; valid
quoted fields containing line breaks remain supported.

An illustrative file is provided at `examples/chestlog.csv`.

## Build and verify

With Java 25 installed:

```sh
./gradlew build
./gradlew runGametest
```

The installable JAR is in `build/libs/`. `build` runs a standalone CSV verification
suite covering inventory deltas, escaping, Unicode, formula protection, flush,
append after restart, schema validation, and concurrent writes. `runGametest`
runs a disposable Minecraft test server to verify the actual logging hooks,
including item clicks, custom item names, two simultaneous players, double chest
coordinates, hopper placement, a full stack moving through two hops with a menu
open, blocked and powered hoppers, physical double-chest transfer endpoints,
custom item transfer data, successful breaks, and canceled breaks. Test
classes and the test mod are not included in the installable JAR.

The tests use simulated server players. Manual testing with connected clients
and a production modpack remains separate from these checks.

## Automated releases

After this workflow is merged, every successful push to `main` (including a PR
merge) builds the mod, runs the CSV and Minecraft server tests, and publishes a
[GitHub release](https://github.com/evan6509/Chestlogger/releases). Each release
contains the installable JAR, `CHANGELOG.md`, and `SHA256SUMS`. The release page
shows the same changelog, generated from commit messages and merged PRs since the
previous release, plus installation requirements and the source commit.

The first release starts at `v1.0.0`; each new release increments the patch
version, such as `v1.0.1`. The JAR's embedded version and filename use this same
version. Rerunning a commit reuses its release instead of creating a duplicate.
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
CHESTLOGGER_VERSION=1.0.1 ./gradlew build
python3 -m unittest discover -s scripts -p 'test_*.py'
CHESTLOGGER_VERSION=1.0.1 python3 scripts/verify_mod_jar.py
```

## Credits

See `NOTICE` and `LICENSE` for attribution and the MIT license.
