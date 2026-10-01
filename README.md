# Chest Logger CSV

A Fabric mod for **Minecraft 26.2** that records player container activity in one
CSV file per world:

```text
<world folder>/ChestLog/chestlog.csv
```

All dimensions and containers append to this same file, including after server
restarts. There are no per-chest text files or daily rotations. This is an
independent replacement based on the behavior of
[ChestSee - Chestlogger](https://modrinth.com/mod/chestsee-chestlogger).

## Install

1. Use Minecraft **26.2**, Java **25 or newer**, Fabric Loader **0.19.3 or newer**,
   and Fabric API **0.154.2+26.2 or newer for Minecraft 26.2**.
2. Put `chestlogger-csv-1.0.0+mc26.2.jar` in the server's `mods` folder.
   For singleplayer, put it in your Minecraft instance's `mods` folder.
3. Remove the original ChestSee JAR when replacing it, then restart the server
   or open your singleplayer world.

Players joining a dedicated server do not need this mod on their clients.
Use the regular JAR, rather than the `-sources.jar`. No configuration is needed.
Existing ChestSee `.txt` logs are left in place; the CSV begins recording new
activity when this mod starts.

## What gets logged

- `OPEN` and `CLOSE`: a player successfully opens or closes a container.
- `ADD`: items enter a container because of a player inventory action.
- `REMOVE`: items leave a container because of a player inventory action.
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
also work. Ender chests, entity inventories, furnaces, explosions, commands, and
automatic hopper transfers are outside this logger's player-action scope.
Automation is not attributed to whichever player happens to have a chest open.

For double chests, each item change uses the coordinates of the physical half
that changed. Item rows are not duplicated for both halves. An open/close action
has a row for each half with the same `event_id`. Moving a stack between halves
records a removal from one half and an addition to the other.

## CSV columns

| Column | Meaning |
| --- | --- |
| `event_id` | Shared UUID for rows belonging to the same player action. |
| `timestamp` | ISO date and time with UTC offset, including fractional seconds. |
| `date`, `time` | Separate date and clock time, easy to filter in Excel. |
| `timezone` | Server computer's time zone, such as `America/Chicago`. |
| `dimension` | Dimension ID, such as `minecraft:overworld`; custom IDs are retained. |
| `container` | Block ID, such as `minecraft:chest` or `minecraft:barrel`. |
| `x`, `y`, `z` | Separate numeric coordinates of the container. |
| `player` | Player's account name. |
| `player_uuid` | Stable player identity even if the name changes. |
| `action` | `OPEN`, `CLOSE`, `ADD`, `REMOVE`, `BREAK`, or `HOPPER_PLACED_BELOW`. |
| `item_id` | Item ID, such as `minecraft:diamond`; blank for non-item events. |
| `item_name` | Display name, including custom names. |
| `quantity` | Positive number of items added or removed. |
| `quantity_delta` | Positive for additions, negative for removals. |
| `item_data` | Item JSON with count normalized to 1, preserving item components. |
| `related_x`, `related_y`, `related_z` | Hopper coordinates for placement events; otherwise blank. |

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

For example, filter `action` to `REMOVE`, `item_id` to `minecraft:diamond`, and
`player` to the name you want. Filter `dimension`, `x`, `y`, and `z` together to
select a specific chest. Sum `quantity` to count withdrawals, or sum
`quantity_delta` to find the net inventory change over the selected period.

Copy the CSV before editing it in Excel. Keep the active log's header and rows
intact; saving spreadsheet changes over it while the server is running can
disrupt logging. Logging failures appear in the server console. If an existing
CSV has incompatible columns or an unfinished final row, the mod leaves it
untouched and disables logging until the file is repaired and the server is
restarted.

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
coordinates, hopper placement, successful breaks, and canceled breaks. Test
classes and the test mod are not included in the installable JAR.

The tests use simulated server players. Manual testing with connected clients
and a production modpack remains separate from these checks.

## Credits

ChestSee 26.2 by MafuuuX supplied the reference behavior. It was inspired by
[Aguga2201's ChestLogs](https://github.com/Aguga2201/ChestLogs). See `NOTICE` and
`LICENSE` for attribution and the MIT license.
