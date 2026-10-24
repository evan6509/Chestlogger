package com.chestlogger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.chestlogger.ContainerGameTests.*;

public final class CommandAuditGameTests {
    @GameTest
    public void ordinaryCommandsStillReadEveryTrackedSlotBeforeAndAfter(GameTestHelper helper) throws Exception {
        try (var state = new StorageAuditRegressionGameTests.AuditState()) {
            StorageAudit.clear();
            var chest = new CountingChest(helper.absolutePos(new BlockPos(2, 1, 2)));
            chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
            chest.setLevel(helper.getLevel());
            StorageAudit.acknowledge(Storage.block(chest));
            chest.reads = 0;
            command(helper, helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(), "chestlogger info");
            helper.assertTrue(chest.reads == 2 * 27, "Command reuses its settling snapshot as before, then captures after");
            chest.reads = 0;
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.invoker()
                    .onEndTick(helper.getLevel().getServer());
            helper.assertTrue(chest.reads == 27, "Regular tick scans once, separately from command overhead");
        }
        helper.succeed();
    }

    private static final class CountingChest extends net.minecraft.world.level.block.entity.ChestBlockEntity {
        private int reads;
        private CountingChest(BlockPos pos) { super(pos, Blocks.CHEST.defaultBlockState()); }
        @Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
    }

    @GameTest
    public void unchangedSnapshotsAreReusedButSilentChangesInvalidate(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        var chest = helper.getBlockEntity(new BlockPos(2, 1, 2), RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        var storage = StorageAudit.track(Storage.block(chest));
        var before = storage.contents();
        helper.assertTrue(storage.contents() == before, "Unchanged slots reuse their immutable totals without new keys/maps");
        boolean immutable = false;
        try { before.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        helper.assertTrue(immutable, "Reused totals cannot be corrupted by a caller");
        chest.getItem(0).shrink(3);
        var after = storage.contents();
        helper.assertTrue(before.values().stream().mapToInt(Integer::intValue).sum() == 8
                        && after.values().stream().mapToInt(Integer::intValue).sum() == 5,
                "Silent count changes invalidate snapshots without changing older totals");
        chest.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Changed in place"));
        var renamed = storage.contents();
        helper.assertTrue(!renamed.equals(after) && after.keySet().iterator().next().stack().getCustomName() == null,
                "Silent component edits invalidate snapshots and preserve old component keys");
        chest.setItem(1, chest.removeItemNoUpdate(0));
        helper.assertTrue(storage.contents().equals(renamed), "Slot rearrangement retains equal inventory totals");
        chest.clearContent();
        helper.assertTrue(storage.contents().isEmpty(), "Clearing an inventory invalidates every slot");
        helper.succeed();
    }

    @GameTest
    public void modCommandsKeepSameCountComponentAndItemChanges(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
        StorageAudit.acknowledge(Storage.block(chest));
        var player = helper.makeMockServerPlayerInLevel();
        var commands = helper.getLevel().getServer().getCommands();
        commands.getDispatcher().register(Commands.literal("audit_test_name").executes(context -> {
            chest.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Same count variant"));
            return 1;
        }));
        commands.getDispatcher().register(Commands.literal("audit_test_unname").executes(context -> {
            chest.getItem(0).remove(DataComponents.CUSTOM_NAME);
            return 1;
        }));
        commands.getDispatcher().register(Commands.literal("audit_test_same_count_type").executes(context -> {
            chest.setItem(0, new ItemStack(Items.EMERALD, 5));
            return 1;
        }));
        int start = allRecords(helper).size();
        for (String name : List.of("audit_test_name", "audit_test_unname", "audit_test_same_count_type")) {
            commands.performPrefixedCommand(player.createCommandSourceStack(), name);
        }
        StorageAudit.observe(Storage.block(chest));
        var rows = newRows(helper, start, chest.getBlockPos());
        helper.assertTrue(rows.size() == 6 && count(rows, "COMMAND_REMOVE", "minecraft:diamond") == 15
                && count(rows, "COMMAND_ADD", "minecraft:diamond") == 10
                && count(rows, "COMMAND_ADD", "minecraft:emerald") == 5,
                "Same-count component addition/removal and different-item replacement each retain both deltas");
        helper.assertTrue(rows.stream().filter(r -> r.get("item_data").contains("Same count variant")).count() == 2,
                "The private named variant survives subsequent in-place component removal");
        helper.assertTrue(rows.stream().allMatch(r -> r.get("player_uuid").equals(player.getUUID().toString())),
                "All same-count modded mutations retain their command actor");
        helper.succeed();
    }

    @GameTest
    public void modCommandsKeepBulkComponentPatches(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
        StorageAudit.acknowledge(Storage.block(chest));
        var player = helper.makeMockServerPlayerInLevel();
        var commands = helper.getLevel().getServer().getCommands();
        List<Runnable> changes = List.of(
                () -> chest.getItem(0).applyComponents(DataComponentPatch.builder()
                        .set(DataComponents.CUSTOM_NAME, Component.literal("Applied patch")).build()),
                () -> ((PatchedDataComponentMap) chest.getItem(0).getComponents()).restorePatch(DataComponentPatch.builder()
                        .set(DataComponents.CUSTOM_NAME, Component.literal("Restored patch")).build()),
                () -> ((PatchedDataComponentMap) chest.getItem(0).getComponents()).clearPatch(),
                () -> chest.getItem(0).applyComponents(DataComponentMap.builder()
                        .set(DataComponents.CUSTOM_NAME, Component.literal("Applied map")).build()));
        int start = allRecords(helper).size();
        for (int i = 0; i < changes.size(); i++) {
            var change = changes.get(i);
            String name = "audit_test_bulk_patch_" + i;
            commands.getDispatcher().register(Commands.literal(name).executes(context -> { change.run(); return 1; }));
            commands.performPrefixedCommand(player.createCommandSourceStack(), name);
        }
        StorageAudit.observe(Storage.block(chest));
        var rows = newRows(helper, start, chest.getBlockPos());
        helper.assertTrue(rows.size() == 8 && count(rows, "COMMAND_REMOVE", "minecraft:diamond") == 20
                && count(rows, "COMMAND_ADD", "minecraft:diamond") == 20,
                "Apply, restore, clear, and set-all each retain both component variants at the same count");
        helper.assertTrue(rows.stream().filter(r -> r.get("item_data").contains("Applied patch")).count() == 2
                && rows.stream().filter(r -> r.get("item_data").contains("Restored patch")).count() == 2,
                "Later bulk edits do not mutate previously recorded component variants");
        helper.assertTrue(rows.stream().allMatch(r -> r.get("player_uuid").equals(player.getUUID().toString())),
                "Bulk component APIs preserve their command actor");
        helper.succeed();
    }

    @GameTest
    public void nestedScopesPreserveSilentChangesAndDistinctActors(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        StorageAudit.acknowledge(Storage.block(chest));
        var outer = helper.makeMockServerPlayerInLevel();
        var inner = helper.makeMockServerPlayerInLevel();
        var commands = helper.getLevel().getServer().getCommands();
        commands.getDispatcher().register(Commands.literal("audit_test_inner_scoped").executes(context -> {
            chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
            return 1;
        }));
        commands.getDispatcher().register(Commands.literal("audit_test_outer_scoped").executes(context -> {
            chest.getItem(0).shrink(1); // No setChanged: a mod can bypass every mutation hook.
            try (var scope = StorageAudit.scope(inner, "COMMAND", Storage.block(chest))) {
                commands.getDispatcher().execute("audit_test_inner_scoped", inner.createCommandSourceStack());
            }
            chest.getItem(0).grow(3);
            return 1;
        }));
        int start = allRecords(helper).size();
        chest.getItem(0).shrink(2); // Pending automation must stay outside command attribution.
        commands.performPrefixedCommand(outer.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS), "execute run audit_test_outer_scoped");
        StorageAudit.observe(Storage.block(chest));
        var rows = newRows(helper, start, chest.getBlockPos());
        var unknown = rows.stream().filter(r -> r.get("player_uuid").isEmpty()).toList();
        var first = rows.stream().filter(r -> r.get("player_uuid").equals(outer.getUUID().toString())).toList();
        var second = rows.stream().filter(r -> r.get("player_uuid").equals(inner.getUUID().toString())).toList();
        helper.assertTrue(count(unknown, "STORAGE_REMOVE", "minecraft:diamond") == 2, "Pending mutation stays unattributed");
        helper.assertTrue(count(first, "COMMAND_REMOVE", "minecraft:diamond") == 1
                && count(first, "COMMAND_ADD", "minecraft:gold_ingot") == 3, "Outer changes retain the outer source");
        helper.assertTrue(count(second, "COMMAND_REMOVE", "minecraft:diamond") == 5
                && count(second, "COMMAND_ADD", "minecraft:gold_ingot") == 2, "Nested changes retain the inner source");
        helper.assertTrue(rows.size() == 5, "Nested commands emit each delta once and observation emits no duplicates");
        helper.succeed();
    }

    @GameTest
    public void nestedCommandQueueKeepsCommandDeltas(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        StorageAudit.acknowledge(Storage.block(chest));
        var player = helper.makeMockServerPlayerInLevel();
        var commands = helper.getLevel().getServer().getCommands();
        commands.getDispatcher().register(Commands.literal("audit_test_queued_inner").executes(context -> {
            chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
            return 1;
        }));
        commands.getDispatcher().register(Commands.literal("audit_test_queued_outer").executes(context -> {
            chest.getItem(0).shrink(1);
            commands.performPrefixedCommand(context.getSource(), "audit_test_queued_inner");
            // Minecraft queues nested performCommand calls until this callback returns.
            chest.getItem(0).grow(3);
            return 1;
        }));
        int start = allRecords(helper).size();
        chest.getItem(0).shrink(2);
        commands.performPrefixedCommand(player.createCommandSourceStack(), "audit_test_queued_outer");
        StorageAudit.observe(Storage.block(chest));
        var rows = newRows(helper, start, chest.getBlockPos());
        var known = rows.stream().filter(r -> !r.get("player_uuid").isEmpty()).toList();
        helper.assertTrue(chest.getItem(0).is(Items.GOLD_INGOT) && chest.getItem(0).getCount() == 2,
                "Nested performCommand actually ran its queued command");
        helper.assertTrue(count(rows, "STORAGE_REMOVE", "minecraft:diamond") == 2
                && count(known, "COMMAND_REMOVE", "minecraft:diamond") == 6
                && count(known, "COMMAND_ADD", "minecraft:gold_ingot") == 2,
                "Queued nested execution preserves command totals and pending automation");
        helper.assertTrue(known.stream().allMatch(r -> r.get("player_uuid").equals(player.getUUID().toString())),
                "Nested queue retains its command actor");
        helper.succeed();
    }

    @GameTest
    public void executeItemAndDataCommandsKeepComponentsAndConsoleSource(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        StorageAudit.acknowledge(Storage.block(chest));
        var player = helper.makeMockServerPlayerInLevel();
        var source = player.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS).withSuppressedOutput();
        int start = allRecords(helper).size();
        command(helper, source, "execute run item replace block " + xyz(chest.getBlockPos())
                + " container.0 with minecraft:gold_ingot[minecraft:custom_name='\"Command gold\"'] 5");
        helper.assertTrue(chest.getItem(0).is(Items.GOLD_INGOT) && chest.getItem(0).getCount() == 5, "Execute actually replaced items");
        command(helper, helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(),
                "data modify block " + xyz(chest.getBlockPos()) + " Items[0].count set value 2");
        helper.assertTrue(chest.getItem(0).getCount() == 2, "Data command actually changed stack count");
        StorageAudit.observe(Storage.block(chest));
        var rows = newRows(helper, start, chest.getBlockPos());
        helper.assertTrue(rows.size() == 3 && count(rows, "COMMAND_REMOVE", "minecraft:diamond") == 8
                && count(rows, "COMMAND_ADD", "minecraft:gold_ingot") == 5
                && count(rows, "COMMAND_REMOVE", "minecraft:gold_ingot") == 3, "Exact command deltas without duplicates");
        helper.assertTrue(rows.stream().filter(r -> r.get("item_id").equals("minecraft:gold_ingot"))
                .allMatch(r -> r.get("item_data").contains("Command gold")), "Command components survive serialization");
        helper.assertTrue(rows.stream().filter(r -> r.get("action").equals("COMMAND_REMOVE") && r.get("item_id").equals("minecraft:gold_ingot"))
                .allMatch(r -> r.get("player_uuid").isEmpty()), "Console source stays blank");
        helper.succeed();
    }

    @GameTest
    public void fillAndCloneKeepBulkRemovalContents(GameTestHelper helper) throws IOException {
        var source = helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput();
        BlockPos pos = new BlockPos(2, 1, 2);
        for (int i = 0; i < 2; i++) {
            helper.setBlock(pos.offset(i, 0, 0), Blocks.BARREL);
            var chest = helper.getBlockEntity(pos.offset(i, 0, 0), RandomizableContainerBlockEntity.class);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 4 + i));
            StorageAudit.acknowledge(Storage.block(chest));
        }
        var from = helper.absolutePos(pos);
        int start = allRecords(helper).size();
        command(helper, source, "clone " + xyz(from) + " " + xyz(from) + " " + xyz(from.east()) + " replace");
        var cloned = helper.getBlockEntity(pos.east(), RandomizableContainerBlockEntity.class);
        helper.assertTrue(cloned.getItem(0).getCount() == 4, "Clone actually copied the inventory");
        var cloneRows = newRows(helper, start, from.east());
        helper.assertTrue(count(cloneRows, "COMMAND_CONTENTS", "minecraft:diamond") == 5
                && cloneRows.stream().anyMatch(r -> r.get("action").equals("COMMAND")), "Clone records replaced storage and its contents");
        StorageAudit.acknowledge(Storage.block(cloned));
        start = allRecords(helper).size();
        command(helper, source, "fill " + xyz(from) + " " + xyz(from.east()) + " minecraft:air");
        var rows = allRecords(helper).subList(start, allRecords(helper).size());
        helper.assertTrue(count(rows, "COMMAND_CONTENTS", "minecraft:diamond") == 8
                && rows.stream().filter(r -> r.get("action").equals("COMMAND")).count() == 2,
                "Fill records both removed inventories exactly once");
        helper.succeed();
    }

    private static void command(GameTestHelper helper, CommandSourceStack source, String command) {
        helper.getLevel().getServer().getCommands().performPrefixedCommand(source, command);
    }
    private static String xyz(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static List<Map<String, String>> newRows(GameTestHelper helper, int start, BlockPos pos) throws IOException {
        var rows = allRecords(helper);
        return at(rows.subList(start, rows.size()), pos);
    }
}
