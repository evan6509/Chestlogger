package com.chestlogger;

import com.chestlogger.csv.CsvFile;
import com.chestlogger.csv.CsvTests;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContainerGameTests {
    @GameTest
    public void clicksAndCustomItems(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        var player = helper.makeMockServerPlayerInLevel();
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        helper.useBlock(pos, player);
        helper.assertTrue(player.containerMenu.slots.size() == 63, "Chest menu opened");
        player.containerMenu.clicked(0, 0, ContainerInput.PICKUP, player);
        player.containerMenu.clicked(1, 0, ContainerInput.PICKUP, player);
        player.containerMenu.clicked(1, 0, ContainerInput.QUICK_MOVE, player);
        ItemStack special = new ItemStack(Items.DIAMOND, 3);
        special.set(DataComponents.CUSTOM_NAME, Component.literal("=Rare, \"diamond\" 日本語"));
        player.getInventory().setItem(0, special);
        int inventorySlot = player.containerMenu.findSlot(player.getInventory(), 0).orElseThrow();
        player.containerMenu.clicked(inventorySlot, 0, ContainerInput.QUICK_MOVE, player);
        player.doCloseContainer();
        var records = records(helper, player);
        helper.assertTrue(count(records, "REMOVE", "minecraft:diamond") == 16, "Pickup and shift-click removals logged");
        helper.assertTrue(count(records, "ADD", "minecraft:diamond") == 11, "Deposits logged");
        helper.assertTrue(records.stream().anyMatch(r -> r.get("action").equals("OPEN")), "Open hook logged");
        helper.assertTrue(records.stream().anyMatch(r -> r.get("action").equals("CLOSE")), "Close hook logged");
        var named = records.stream().filter(r -> r.get("quantity").equals("3")).findFirst().orElseThrow();
        helper.assertTrue(named.get("item_name").equals("'=Rare, \"diamond\" 日本語"), "Spreadsheet formula protected");
        helper.assertTrue(named.get("item_data").contains("minecraft:custom_name"), "Custom item data preserved");
        helper.succeed();
    }

    @GameTest
    public void simultaneousPlayersAndExternalChanges(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.BARREL);
        var barrel = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        barrel.setItem(0, new ItemStack(Items.DIAMOND, 10));
        var first = helper.makeMockServerPlayerInLevel();
        var second = helper.makeMockServerPlayerInLevel();
        helper.useBlock(pos, first);
        helper.useBlock(pos, second);
        first.containerMenu.clicked(0, 1, ContainerInput.PICKUP, first);
        second.containerMenu.clicked(0, 1, ContainerInput.PICKUP, second);
        // Represents an automation or command change between player actions.
        barrel.removeItem(0, 1);
        first.containerMenu.clicked(1, 0, ContainerInput.PICKUP, first);
        helper.assertTrue(count(records(helper, first), "REMOVE", "minecraft:diamond") == 5, "First player attributed 5");
        helper.assertTrue(count(records(helper, second), "REMOVE", "minecraft:diamond") == 3, "Second player attributed 3");
        helper.assertTrue(count(records(helper, first), "ADD", "minecraft:diamond") == 5, "Only actual deposit attributed");
        first.doCloseContainer();
        second.doCloseContainer();
        helper.succeed();
    }

    @GameTest
    public void doubleChestDoesNotDuplicateItemTotals(GameTestHelper helper) throws IOException {
        BlockPos leftPos = new BlockPos(1, 1, 1);
        BlockPos rightPos = leftPos.east();
        var state = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        helper.setBlock(leftPos, state.setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(rightPos, state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        var left = helper.getBlockEntity(leftPos, RandomizableContainerBlockEntity.class);
        var right = helper.getBlockEntity(rightPos, RandomizableContainerBlockEntity.class);
        left.setItem(0, new ItemStack(Items.DIAMOND, 4));
        right.setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
        var player = helper.makeMockServerPlayerInLevel();
        helper.useBlock(leftPos, player);
        helper.assertTrue(player.containerMenu.slots.size() == 90, "Double chest opened");
        player.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
        player.containerMenu.clicked(27, 0, ContainerInput.QUICK_MOVE, player);
        var rows = records(helper, player);
        helper.assertTrue(count(rows, "REMOVE", "minecraft:diamond") == 4, "Diamonds logged once");
        helper.assertTrue(count(rows, "REMOVE", "minecraft:gold_ingot") == 7, "Gold logged once");
        for (var row : rows) {
            if (row.get("action").equals("REMOVE")) {
                BlockPos physicalPos = row.get("item_id").equals("minecraft:diamond") ? left.getBlockPos() : right.getBlockPos();
                helper.assertTrue(row.get("x").equals(Integer.toString(physicalPos.getX())), "Actual half coordinates");
                helper.assertTrue(row.get("z").equals(Integer.toString(physicalPos.getZ())), "Actual half coordinates");
            }
        }
        player.doCloseContainer();
        helper.succeed();
    }

    @GameTest
    public void hopperPlacementAndSuccessfulBreak(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 2, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var player = helper.makeMockServerPlayerInLevel();
        BlockPos absolute = helper.absolutePos(pos);
        ItemStack hopper = new ItemStack(Items.HOPPER, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, hopper);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.DOWN, absolute, false);
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, hopper, hit);
        var result = ((BlockItem) Items.HOPPER).place(context);
        helper.assertTrue(result.consumesAction(), "Hopper placed successfully");
        helper.assertTrue(helper.getLevel().getBlockState(absolute.below()).is(Blocks.HOPPER), "Actual hopper placed");
        var rows = records(helper, player);
        helper.assertTrue(rows.stream().filter(r -> r.get("action").equals("HOPPER_PLACED_BELOW")).count() == 1,
                "Single hopper event");
        helper.assertTrue(rows.getLast().get("related_y").equals(Integer.toString(absolute.getY() - 1)), "Hopper coordinates logged");
        helper.assertTrue(player.gameMode.destroyBlock(absolute), "Chest broken through player game mode");
        helper.assertTrue(records(helper, player).stream().filter(r -> r.get("action").equals("BREAK")).count() == 1,
                "Successful break logged once");
        helper.succeed();
    }

    @GameTest
    public void canceledBreakIsNotLogged(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.SHULKER_BOX);
        var player = helper.makeMockServerPlayerInLevel();
        // Cancel only this player's break, leaving the other tests unaffected.
        PlayerBlockBreakEvents.BEFORE.register((level, actor, blockPos, state, entity) -> !actor.getUUID().equals(player.getUUID()));
        boolean broken = player.gameMode.destroyBlock(helper.absolutePos(pos));
        helper.assertFalse(broken, "Break canceled by server protection");
        helper.assertTrue(records(helper, player).stream().noneMatch(r -> r.get("action").equals("BREAK")), "No false break record");
        helper.succeed();
    }

    @GameTest(maxTicks = 700)
    public void hopperMovesWholeStackWithoutBlamingOpenPlayer(GameTestHelper helper) throws IOException {
        int firstRow = allRecords(helper).size();
        BlockPos topPos = new BlockPos(2, 2, 2);
        BlockPos hopperPos = topPos.below();
        BlockPos bottomPos = hopperPos.east();
        helper.setBlock(topPos, Blocks.CHEST);
        helper.setBlock(bottomPos, Blocks.CHEST);
        helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        var top = helper.getBlockEntity(topPos, RandomizableContainerBlockEntity.class);
        var hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
        var bottom = helper.getBlockEntity(bottomPos, RandomizableContainerBlockEntity.class);
        var player = helper.makeMockServerPlayerInLevel();
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 64));
        helper.useBlock(topPos, player);
        int slot = player.containerMenu.findSlot(player.getInventory(), 0).orElseThrow();
        player.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, player);
        // Keep the menu open while real server ticks move the entire stack through the hopper.
        helper.succeedWhen(() -> {
            helper.assertTrue(top.isEmpty() && hopper.isEmpty() && bottom.getItem(0).getCount() == 64,
                    "Entire stack reached the lower chest");
            try {
                var rows = hopperRecords(helper, firstRow, top.getBlockPos(), hopper.getBlockPos(), bottom.getBlockPos());
                assertHopperPairs(helper, rows);
                helper.assertTrue(rows.size() == 256, "Exactly two rows per move on each of the two hops");
                helper.assertTrue(count(at(rows, top.getBlockPos()), "HOPPER_REMOVE", "minecraft:dirt") == 64,
                        "Top chest lost 64 through hopper transfers");
                helper.assertTrue(count(at(rows, hopper.getBlockPos()), "HOPPER_ADD", "minecraft:dirt") == 64
                                && count(at(rows, hopper.getBlockPos()), "HOPPER_REMOVE", "minecraft:dirt") == 64,
                        "Hopper received and forwarded 64");
                helper.assertTrue(count(at(rows, bottom.getBlockPos()), "HOPPER_ADD", "minecraft:dirt") == 64,
                        "Lower chest received 64");
                var playerRows = records(helper, player);
                helper.assertTrue(count(playerRows, "ADD", "minecraft:dirt") == 64
                                && count(playerRows, "REMOVE", "minecraft:dirt") == 0,
                        "Only the actual deposit is attributed to the player with the menu open");
                player.doCloseContainer();
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @GameTest(maxTicks = 60)
    public void fullAndPoweredHoppersDoNotLogTransfers(GameTestHelper helper) throws IOException {
        int firstRow = allRecords(helper).size();
        BlockPos topPos = new BlockPos(2, 2, 2);
        BlockPos hopperPos = topPos.below();
        helper.setBlock(topPos, Blocks.CHEST);
        helper.setBlock(hopperPos, Blocks.HOPPER);
        var top = helper.getBlockEntity(topPos, RandomizableContainerBlockEntity.class);
        var hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) hopper.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        // Force vanilla's failed insertion/restore path for both a last item and a larger stack.
        for (int quantity : List.of(1, 8)) {
            top.setItem(0, new ItemStack(Items.DIRT, quantity));
            helper.assertFalse(HopperBlockEntity.suckInItems(helper.getLevel(), hopper), "Full hopper rejects insertion");
            helper.assertTrue(top.getItem(0).getCount() == quantity, "Rejected pull restores source stack");
        }
        BlockPos poweredPos = new BlockPos(5, 1, 2);
        helper.setBlock(poweredPos.above(), Blocks.CHEST);
        helper.setBlock(poweredPos.west(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(poweredPos, Blocks.HOPPER);
        // Deliver the power-source notification explicitly before observing subsequent server ticks.
        helper.getLevel().neighborChanged(helper.absolutePos(poweredPos), Blocks.REDSTONE_BLOCK, null);
        helper.assertTrue(helper.getLevel().hasNeighborSignal(helper.absolutePos(poweredPos)), "Power source reaches hopper");
        var powered = helper.getBlockEntity(poweredPos, HopperBlockEntity.class);
        var poweredTop = helper.getBlockEntity(poweredPos.above(), RandomizableContainerBlockEntity.class);
        poweredTop.setItem(0, new ItemStack(Items.DIRT, 4));
        helper.runAtTickTime(40, () -> {
            helper.assertBlockProperty(poweredPos, HopperBlock.ENABLED, false);
            helper.assertTrue(powered.isEmpty() && poweredTop.getItem(0).getCount() == 4, "Powered hopper did not pull");
            helper.assertTrue(top.getItem(0).getCount() == 8, "Full hopper did not pull on subsequent ticks");
            try {
                helper.assertTrue(hopperRecords(helper, firstRow, top.getBlockPos(), hopper.getBlockPos(),
                        powered.getBlockPos(), poweredTop.getBlockPos()).isEmpty(), "No false transfer records");
            } catch (IOException e) { throw new UncheckedIOException(e); }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void rejectedHopperPushRestoresItemsWithoutLogging(GameTestHelper helper) throws IOException {
        int firstRow = allRecords(helper).size();
        BlockPos hopperPos = new BlockPos(2, 1, 2);
        BlockPos targetPos = hopperPos.east();
        helper.setBlock(targetPos, Blocks.CHEST);
        helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        var hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
        var target = helper.getBlockEntity(targetPos, RandomizableContainerBlockEntity.class);
        for (int slot = 0; slot < target.getContainerSize(); slot++) target.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        // This destination is not full, but its remaining space cannot accept dirt.
        target.setItem(0, new ItemStack(Items.COBBLESTONE, 63));
        hopper.setItem(0, new ItemStack(Items.DIRT, 1));
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(hopper.getItem(0).getCount() == 1, "Failed push restores the last item");
            hopper.setItem(0, new ItemStack(Items.DIRT, 8));
        });
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(hopper.getItem(0).getCount() == 8 && target.getItem(0).getCount() == 63,
                    "Rejected pushes preserve both inventories");
            try {
                helper.assertTrue(hopperRecords(helper, firstRow, hopper.getBlockPos(), target.getBlockPos()).isEmpty(),
                        "No records for rejected pushes");
            } catch (IOException e) { throw new UncheckedIOException(e); }
            helper.succeed();
        });
    }

    @GameTest
    public void hopperTransfersUsePhysicalDoubleChestHalvesAndItemComponents(GameTestHelper helper) throws IOException {
        int firstRow = allRecords(helper).size();
        BlockPos sourceLeftPos = new BlockPos(2, 2, 2);
        BlockPos sourceRightPos = sourceLeftPos.east();
        BlockPos hopperPos = sourceLeftPos.below();
        BlockPos targetLeftPos = hopperPos.east();
        BlockPos targetRightPos = targetLeftPos.east();
        var state = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        helper.setBlock(sourceLeftPos, state.setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(sourceRightPos, state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        helper.setBlock(targetLeftPos, state.setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(targetRightPos, state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        var sourceLeft = helper.getBlockEntity(sourceLeftPos, RandomizableContainerBlockEntity.class);
        var sourceRight = helper.getBlockEntity(sourceRightPos, RandomizableContainerBlockEntity.class);
        var hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity.class);
        var targetLeft = helper.getBlockEntity(targetLeftPos, RandomizableContainerBlockEntity.class);
        var targetRight = helper.getBlockEntity(targetRightPos, RandomizableContainerBlockEntity.class);
        for (var target : List.of(targetLeft, targetRight)) {
            for (int slot = 0; slot < target.getContainerSize(); slot++) target.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        targetRight.setItem(26, ItemStack.EMPTY);
        ItemStack named = new ItemStack(Items.DIRT, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("=Hopper, \"dirt\" 日本語"));
        sourceRight.setItem(0, named);
        helper.succeedWhen(() -> {
            helper.assertTrue(sourceRight.isEmpty() && hopper.isEmpty() && targetRight.getItem(26).is(Items.DIRT),
                    "Named dirt moved through both hops");
            try {
                var rows = hopperRecords(helper, firstRow, sourceLeft.getBlockPos(), sourceRight.getBlockPos(),
                        hopper.getBlockPos(), targetLeft.getBlockPos(), targetRight.getBlockPos());
                assertHopperPairs(helper, rows);
                helper.assertTrue(rows.size() == 4, "No duplicated rows for unaffected chest halves");
                helper.assertTrue(at(rows, sourceLeft.getBlockPos()).isEmpty() && at(rows, targetLeft.getBlockPos()).isEmpty(),
                        "Unchanged halves have no item rows");
                helper.assertTrue(count(at(rows, sourceRight.getBlockPos()), "HOPPER_REMOVE", "minecraft:dirt") == 1
                                && count(at(rows, targetRight.getBlockPos()), "HOPPER_ADD", "minecraft:dirt") == 1,
                        "Actual source and destination halves logged");
                helper.assertTrue(rows.stream().allMatch(r -> r.get("item_name").equals("'=Hopper, \"dirt\" 日本語")
                                && r.get("item_data").contains("minecraft:custom_name")),
                        "Item components and spreadsheet protection preserved on both sides");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private static List<Map<String, String>> at(List<Map<String, String>> rows, BlockPos pos) {
        return rows.stream().filter(r -> r.get("x").equals(Integer.toString(pos.getX()))
                && r.get("y").equals(Integer.toString(pos.getY()))
                && r.get("z").equals(Integer.toString(pos.getZ()))).toList();
    }

    private static List<Map<String, String>> hopperRecords(GameTestHelper helper, int firstRow, BlockPos... positions)
            throws IOException {
        var rows = allRecords(helper);
        return rows.subList(firstRow, rows.size()).stream()
                .filter(r -> r.get("action").equals("HOPPER_ADD") || r.get("action").equals("HOPPER_REMOVE"))
                .filter(r -> java.util.Arrays.stream(positions).anyMatch(pos -> !at(List.of(r), pos).isEmpty())).toList();
    }

    private static void assertHopperPairs(GameTestHelper helper, List<Map<String, String>> rows) {
        Map<String, List<Map<String, String>>> events = new LinkedHashMap<>();
        for (var row : rows) {
            helper.assertTrue(row.get("player").isEmpty() && row.get("player_uuid").isEmpty(), "Automation has no player");
            java.util.UUID.fromString(row.get("event_id"));
            java.time.OffsetDateTime.parse(row.get("timestamp"));
            events.computeIfAbsent(row.get("event_id"), ignored -> new ArrayList<>()).add(row);
        }
        for (var event : events.values()) {
            helper.assertTrue(event.size() == 2, "Exactly two rows share a transfer ID");
            var remove = event.get(0);
            var add = event.get(1);
            helper.assertTrue(remove.get("action").equals("HOPPER_REMOVE") && add.get("action").equals("HOPPER_ADD"),
                    "Source removal followed by destination addition");
            helper.assertTrue(remove.get("quantity").equals("1") && add.get("quantity").equals("1")
                            && remove.get("quantity_delta").equals("-1") && add.get("quantity_delta").equals("1"),
                    "Actual vanilla transfer quantity and balanced deltas");
            helper.assertTrue(remove.get("item_data").equals(add.get("item_data"))
                            && remove.get("timestamp").equals(add.get("timestamp"))
                            && remove.get("dimension").equals(add.get("dimension")), "Pair has the same item, time and dimension");
            for (String axis : List.of("x", "y", "z")) {
                helper.assertTrue(remove.get(axis).equals(add.get("related_" + axis))
                                && add.get(axis).equals(remove.get("related_" + axis)), "Both endpoints are linked");
            }
        }
    }

    private static int count(List<Map<String, String>> rows, String action, String item) {
        return rows.stream().filter(r -> r.get("action").equals(action) && r.get("item_id").equals(item))
                .mapToInt(r -> Integer.parseInt(r.get("quantity"))).sum();
    }

    private static List<Map<String, String>> records(GameTestHelper helper, ServerPlayer player) throws IOException {
        var result = allRecords(helper).stream().filter(row -> row.get("player_uuid").equals(player.getUUID().toString())).toList();
        for (var row : result) {
            helper.assertTrue(row.get("player").equals(player.getGameProfile().name()), "Actual player name");
            helper.assertTrue(row.get("dimension").equals(helper.getLevel().dimension().identifier().toString()), "Dimension ID");
            java.time.OffsetDateTime.parse(row.get("timestamp"));
        }
        return result;
    }

    private static List<Map<String, String>> allRecords(GameTestHelper helper) throws IOException {
        var path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("ChestLog/chestlog.csv");
        var csv = CsvTests.parse(path);
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < csv.size(); i++) {
            helper.assertTrue(csv.get(i).size() == CsvFile.COLUMN_COUNT, "CSV schema intact");
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < csv.get(0).size(); c++) row.put(csv.get(0).get(c), csv.get(i).get(c));
            result.add(row);
        }
        return result;
    }
}
