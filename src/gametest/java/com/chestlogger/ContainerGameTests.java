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
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
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

    private static int count(List<Map<String, String>> rows, String action, String item) {
        return rows.stream().filter(r -> r.get("action").equals(action) && r.get("item_id").equals(item))
                .mapToInt(r -> Integer.parseInt(r.get("quantity"))).sum();
    }

    private static List<Map<String, String>> records(GameTestHelper helper, ServerPlayer player) throws IOException {
        var path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("ChestLog/chestlog.csv");
        var csv = CsvTests.parse(path);
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < csv.size(); i++) {
            helper.assertTrue(csv.get(i).size() == CsvFile.COLUMN_COUNT, "CSV schema intact");
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < csv.get(0).size(); c++) row.put(csv.get(0).get(c), csv.get(i).get(c));
            if (row.get("player_uuid").equals(player.getUUID().toString())) {
                helper.assertTrue(row.get("player").equals(player.getGameProfile().name()), "Actual player name");
                helper.assertTrue(row.get("dimension").equals(helper.getLevel().dimension().identifier().toString()), "Dimension ID");
                java.time.OffsetDateTime.parse(row.get("timestamp"));
                result.add(row);
            }
        }
        return result;
    }
}
