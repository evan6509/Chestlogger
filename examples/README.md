# Sample storage log

[chestlog.csv](chestlog.csv) contains 76 fictional records across 23 scenarios,
covering all 22 current action types. It uses the real 24-column schema, UTF-8
encoding marker, and CRLF record endings. Player names, UUIDs, and events are
invented. Times use October 9, 2026, in America/Chicago. These are selected
representative events, rather than an exhaustive world history.

Some containers and their contents existed before this sample starts. Those
loaded inventories establish a baseline and do not create initial ADD rows.

Open the CSV in Excel and widen the columns to fit. The row numbers below are
Excel row numbers, including the header in row 1.

| Time | CSV rows | Scenario | What the rows show |
| --- | --- | --- | --- |
| 09:00 | 2-7 | Normal player use | Steve places a chest, deposits 16 diamonds and 2 named diamonds, withdraws 4 ordinary diamonds, and closes it. Named items stay separate. |
| 09:01 | 8-10 | Hopper setup | Alex places a hopper below Steve's chest and a shulker beside the hopper. The hopper placement and affected-chest marker share an event ID. |
| 09:02 | 11-17 | Chest to hopper to shulker | One diamond moves through two linked transfers. Alex then takes it from the shulker. Automation has blank player fields; the final withdrawal names Alex. |
| 09:03 | 18-24 | Physical double-chest halves | Steve opens a double chest, adds 8 iron ingots to its right half, and moves them to its left half. Opening/closing records both halves; the move is one paired player action. |
| 09:04 | 25-25 | Direct withdrawal without a menu | Alex takes 8 emeralds directly from a shelf. Bookshelf withdrawals and jukebox ejection also use player item deltas. |
| 09:05 | 26-28 | Menu button withdrawal | Alex uses a lectern's take-book button. Menu buttons and recipe placement produce ordinary player ADD/REMOVE rows. |
| 09:06 | 29-32 | Filled shulker placed and broken | Steve places a shulker carrying 8 diamonds, then Alex breaks it. Contents rows describe inventory entering/leaving this block location; the dropped box can retain the diamonds. |
| 09:07 | 33-41 | Moving hopper minecart | Alex places a hopper minecart, which pulls one diamond from an existing chest. The cart moves, Alex withdraws the diamond and deposits gold, then Steve destroys it. Its UUID stays the same across coordinates. |
| 09:08 | 42-44 | Animal inventory | Alex opens an existing donkey's storage and withdraws 3 carrots. The entity UUID identifies the animal. Chest boats use the same identity pattern. |
| 09:09 | 45-48 | Private ender inventory | Alex deposits 5 emeralds and takes 2 back. Every row identifies Alex as the private inventory owner. |
| 09:10 | 49-49 | Ender chest block broken | Steve breaks that ender chest block. Alex's remaining private emeralds stay in the private inventory, so there are no BREAK_CONTENTS rows. |
| 09:11 | 50-54 | Processing with a player watching | A loaded furnace consumes coal and smelts one raw iron into an ingot while Steve has its menu open. The automatic changes are not attributed to Steve. |
| 09:12 | 55-56 | Other automatic transfer | A dropper transfers one arrow into a barrel. Each inventory delta has its own event ID. Dispensers and other observed automation also use STORAGE deltas. |
| 09:13 | 57-57 | Silent or unknown inventory change | Two diamonds disappear from an existing chest without a recognized action. The fallback records the net loss and leaves the player blank. |
| 09:14 | 58-59 | Player command changes contents | Admin replaces a slot holding 4 diamonds with 4 stone. The removal and addition share one command event. |
| 09:15 | 60-60 | Console command | A console command puts 3 gold ingots into another barrel. No player is available, so player fields stay blank. |
| 09:16 | 61-62 | Command removes storage | Admin removes the console-filled barrel. The structural COMMAND row and its contents share an event ID. |
| 09:17 | 63-64 | Explosion with known player source | An explosion with Alex as its recorded source removes a chest holding 6 diamonds. |
| 09:18 | 65-66 | Explosion without a player source | An explosion with no known player removes a barrel holding 2 gold ingots. Its player fields are blank. |
| 09:19 | 67-68 | Other storage destruction | A campfire holding 3 raw beef is removed without a known player or recognized break/explosion/command scope. DESTROY records the location and contents. |
| 09:20 | 69-70 | Unopened loot storage and another dimension | Steve breaks an unopened loot chest in the Nether. Vanilla generates its loot during removal; the resulting 3 iron ingots are logged without an earlier ADD. |
| 09:21 | 71-75 | Filled chest broken and ground items collected | Alex breaks the original chest, which still holds 11 ordinary diamonds and 2 named diamonds, then collects the two resulting item stacks. Pickup event IDs and entity UUIDs are separate from the break event. |
| 09:22 | 76-77 | Hopper collects a dropped stack | A hopper collects 3 gold ingots from one ground item entity. The balanced pair links the ground item UUID to the hopper; the collector is automatic. |

## Reading the relationships

- Matching `event_id` values group one action's rows. Each hopper transfer has
  balanced removal/addition rows that identify the other endpoint.
- `entity_uuid` follows moving storage or a ground item.
  `related_entity_uuid` identifies the other transfer endpoint when it is an entity.
- `storage_owner_uuid` identifies the private ender inventory owner. Shared
  storage does not gain an owner merely because a player placed it.
- Blank player fields mean automatic or unknown activity. A hopper's placement
  identifies its builder, while its later transfers retain blank actor fields.
- `quantity` is always positive. `quantity_delta` gives the direction. Item JSON
  uses count 1 and retains components; different named variants have separate rows.
- Ground pickup is a separate event. The illustrative sequence describes the
  collector, but the CSV does not automatically link a dropped item to its
  original container. Filter a particular storage before totaling withdrawals.

Failed/canceled actions, full or powered hopper attempts, and rearrangements
within one physical inventory create no item-change rows. They are intentionally
absent from the sample. New variants of supported storage use the same actions;
this file does not repeat every block, color, animal, click, or recipe variation.
