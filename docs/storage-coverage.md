# Storage logging coverage audit

This audit covers the logging code, inventory adapters, mixins, CSV writer,
test suites, build configuration, and release verification helpers. The mod
records evidence; it does not lock inventories or decide whether access is theft.

## Gaps addressed

| Path | Gap in the earlier implementation | Current behavior and verification |
| --- | --- | --- |
| Furnace, blast furnace, smoker, brewing stand, crafter menus | Only `RandomizableContainerBlockEntity` inventories were resolved. | All block entities implementing `Container` are resolved. Native menu withdrawal tests identify the player for all five processing inventories. |
| Bookshelf, shelf, jukebox | Taking items without a menu bypassed menu snapshots. | The player block interaction captures physical inventory changes. Native book, stack, and disc removal tests assert quantities and actor UUIDs. |
| Lectern | A separate menu container and take-book button bypassed the old resolver/click hook. | An inventory alias and server menu-button hook record the actual book withdrawal. |
| Ender chest | Inventory belongs to a player, rather than the clicked block entity. | The private inventory is resolved and identified by `storage_owner_uuid`. Breaking its block does not log removal of private contents. |
| Chest boat, storage minecart, hopper minecart | Entity inventories were excluded. | Menu changes use stable entity UUIDs; a native hopper-cart pull produces exactly one balanced pair. Destruction includes contents and known attacker. |
| Horse-family and nautilus storage | Their menus expose a separate `SimpleContainer`. | Adapters resolve the owning entity; donkey menu withdrawal is tested. Replacing/resizing the inventory retains its old total, with a native chest-removal test. Animal death is checked through its later entity removal for duplicate logs. |
| Storage vehicle placement | Block placement hooks cannot see vehicles created by item use. | Successful minecart and boat item spawns record the actual player and entity UUID. Automatic/dispenser spawns do not invent a placer. |
| Filled storage broken by a player | A `BREAK` marker did not describe the items taken with the block. | `BREAK_CONTENTS` records every component-sensitive item total. Native chest, shulker, furnace, decorated-pot, and campfire breaks are tested. |
| Filled shulker placement | The initial carried contents were absent from the placement event. | `PLACE_CONTENTS` shares the placement event ID and preserves the initial contents. |
| Explosions and other block replacement | The player-break callback missed other removal causes. | Physical block-entity replacement captures removal and contents; explosion scopes retain the known source when available. Native explosion destruction is tested. |
| Unopened loot storage | Inspecting contents can generate loot early, or destruction can miss loot generated during removal. | Pending loot is never opened by the auditor. Its natural fill updates the removal snapshot. Tests retain pending loot and compare generated destruction contents with actual drops. |
| Ground items collected by hoppers | There was no block-container source endpoint. | Ground item UUID and hopper destination share a balanced transfer event. Native loose-item collection is tested. |
| Ground items collected by players | The collector after a container break was unrecorded. | `PICKUP_REMOVE` records actual collected quantity and player/item UUIDs for all ground pickups. It does not prove the item's original container. |
| Command changes/removals | Commands could bypass menu and break hooks. | Command scopes capture loaded storage changes and removals. A native `/item replace block` test checks player attribution. Console sources remain blank. |
| Dropper/dispenser, processing, mob or mod automation | Other automatic changes bypassed hopper/menu hooks. | Change notifications and an end-of-tick inventory comparison emit `STORAGE_ADD`/`STORAGE_REMOVE`. Native dropper transfer and furnace smelting tests check the deltas; no open-menu player is blamed. |
| Silent stack mutation | `ItemStack.shrink` can bypass `setChanged`. | Loaded inventories are compared at the end of each server tick; a silent-shrink test verifies the resulting removal. |
| CSV identity | Coordinates alone cannot follow moving storage or distinguish private ender inventories. | Three appended columns identify the entity, related entity, and private owner. Valid old 21-column logs migrate to 24 columns with an exact original backup. |

Existing behavior remains covered: double-chest physical halves, item components,
ordinary/shift/hotbar/drag actions, simultaneous players, placement and break
cancellation, hopper chains, full/powered hoppers, failed insertion rollback,
and a hopper-to-shulker route correlated with the builder's placement history.

## How attribution works

A direct player action records that player. A command records its source player,
when present. Destruction uses the available damage/explosion source. Automated
hopper movement has blank player fields and links the two actual endpoints.
Generic inventory observation has blank player fields.

Match hopper block coordinates or a vehicle UUID to its preceding `PLACE` record
to find the builder. This is evidence about who constructed the route, not proof
of who enabled every later transfer. A lever press, redstone timing, mob action,
or scheduled operation is not retrospectively assigned to a nearby player.

`BREAK_CONTENTS` and other removal contents describe items leaving that storage
location. A shulker may retain those items inside its dropped item. A separate
ground pickup identifies the collector, but drops can merge, move, or come from
other sources; there is no automatic origin chain from a destroyed container to
every resulting item entity.

## Remaining boundaries

- Logging starts when the mod is installed and the CSV opens successfully. It
  cannot reconstruct older placements, withdrawals, or inventory contents.
- Only loaded server inventories are observed. Offline world/player-data edits
  and direct file tampering bypass runtime logging.
- Custom mod storage that exposes neither a supported owner nor a `Container`
  needs an adapter. A mod replacing vanilla behavior can also bypass a specific
  hook; compatibility with an arbitrary modpack is not established by these
  vanilla tests.
- Fallback observation records net changes between observations. Opposing
  mutations that bypass notifications and occur before the next observation can
  cancel out. Ordinary hooked player/hopper actions are recorded separately.
- Opaque mutations observed by the fallback have no reliable player identity or
  causal transfer link. These fields stay blank instead of accusing a player.
- Player main inventories, transient workstation grids, item-frame/armor-stand
  displays, and general mob equipment are not storage-block inventories. Ground
  collection is logged, but this is not a complete audit of all personal item
  use, dropping, trading, or equipment movement.
- Inventory transformations also create deltas. Smelting, brewing, fuel use,
  crafting, and dispensing must be distinguished from theft when reading rows.
- A CSV write failure disables logging and reports the failure in the server
  console. Startup rejects malformed files rather than modifying them. The
  CSV has no tamper-evident signing or access-control protection.

The fallback compares all loaded supported inventories each tick. It avoids
initializing pending loot and serializes item data only for changed rows, but
large loaded storage systems and busy hopper networks still need production
performance testing. No connected-client or production-modpack gameplay test is
implied by the automated server tests.

## Implementation and validation

`Storage` defines physical inventory owners and aliases. `StorageAudit` holds
component-sensitive baselines, records scoped operations, observes notifications,
and forgets inventories when unloaded. Explicit player/hopper hooks acknowledge
their resulting totals so the fallback does not duplicate them. Block removal
and vehicle/living-entity destruction preserve the contents before vanilla
clears or drops them. Loot generation is observed only after the native fill.

Local validation passed: 29 Minecraft GameTests, the standalone CSV suite,
21 release-helper tests, installable JAR inspection, and `git diff --check`.

Validation commands:

```sh
./gradlew --offline --no-daemon build runGametest
python3 scripts/verify_mod_jar.py
python3 -m unittest discover -s scripts -p 'test_*.py'
git diff --check
```

`build` runs the standalone CSV checks, including legacy migration, exact backups,
malformed legacy rejection, quoting, components/deltas, concurrent complete
records, and append/restart behavior. `runGametest` exercises the mixins in a
Minecraft server using simulated players. The installable JAR excludes test
classes. The release helper tests and JAR inspection verify packaging logic;
they do not publish or verify a live release.
