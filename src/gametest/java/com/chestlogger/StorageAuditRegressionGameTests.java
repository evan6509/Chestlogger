package com.chestlogger;

import com.chestlogger.csv.CsvFile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chestlogger.ContainerGameTests.*;

public final class StorageAuditRegressionGameTests {
    @GameTest
    public void hopperPullPreservesPendingChangesAtBothEndpoints(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 2, 2);
        helper.setBlock(pos, Blocks.CHEST);
        helper.setBlock(pos.below(), Blocks.HOPPER);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        var hopper = helper.getBlockEntity(pos.below(), net.minecraft.world.level.block.entity.HopperBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        hopper.setItem(0, new ItemStack(Items.GOLD_INGOT, 8));
        StorageAudit.acknowledge(Storage.block(chest));
        StorageAudit.acknowledge(Storage.block(hopper));
        int start = allRecords(helper).size();
        chest.getItem(0).shrink(3);
        hopper.getItem(0).shrink(2);
        helper.assertTrue(net.minecraft.world.level.block.entity.HopperBlockEntity.suckInItems(helper.getLevel(), hopper),
                "Hopper actually pulled one diamond");
        StorageAudit.observe(Storage.block(chest));
        StorageAudit.observe(Storage.block(hopper));
        var rows = allRecords(helper).subList(start, allRecords(helper).size());
        helper.assertTrue(count(at(rows, chest.getBlockPos()), "STORAGE_REMOVE", "minecraft:diamond") == 3,
                "Pending source removal survives hopper acknowledgment");
        helper.assertTrue(count(at(rows, hopper.getBlockPos()), "STORAGE_REMOVE", "minecraft:gold_ingot") == 2,
                "Pending destination removal survives hopper acknowledgment");
        assertSingleTransfer(helper, rows, "minecraft:diamond", 1);
        helper.assertTrue(rows.size() == 4, "Only the two pending changes and transfer pair are logged");
        helper.succeed();
    }

    @GameTest
    public void hopperPushPreservesPendingChangesAtBothEndpoints(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos.east(), Blocks.CHEST);
        helper.setBlock(pos, Blocks.HOPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HopperBlock.FACING, Direction.EAST));
        var hopper = helper.getBlockEntity(pos, net.minecraft.world.level.block.entity.HopperBlockEntity.class);
        var chest = helper.getBlockEntity(pos.east(), RandomizableContainerBlockEntity.class);
        hopper.setItem(0, new ItemStack(Items.DIAMOND, 8));
        chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 8));
        StorageAudit.acknowledge(Storage.block(hopper));
        StorageAudit.acknowledge(Storage.block(chest));
        int start = allRecords(helper).size();
        hopper.getItem(0).shrink(3);
        chest.getItem(0).shrink(2);
        net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick(helper.getLevel(), hopper.getBlockPos(),
                hopper.getBlockState(), hopper);
        helper.assertTrue(hopper.getItem(0).getCount() == 4, "Hopper actually pushed one diamond");
        StorageAudit.observe(Storage.block(hopper));
        StorageAudit.observe(Storage.block(chest));
        var rows = allRecords(helper).subList(start, allRecords(helper).size());
        helper.assertTrue(count(at(rows, hopper.getBlockPos()), "STORAGE_REMOVE", "minecraft:diamond") == 3,
                "Pending push source removal survives hopper acknowledgment");
        helper.assertTrue(count(at(rows, chest.getBlockPos()), "STORAGE_REMOVE", "minecraft:gold_ingot") == 2,
                "Pending push destination removal survives hopper acknowledgment");
        assertSingleTransfer(helper, rows, "minecraft:diamond", 1);
        helper.assertTrue(rows.size() == 4, "Only the two pending changes and transfer pair are logged");
        helper.succeed();
    }

    @GameTest
    public void looseHopperPickupPreservesPendingDestinationChanges(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.HOPPER);
        var hopper = helper.getBlockEntity(pos, net.minecraft.world.level.block.entity.HopperBlockEntity.class);
        hopper.setItem(0, new ItemStack(Items.DIAMOND, 8));
        StorageAudit.acknowledge(Storage.block(hopper));
        Vec3 absolute = Vec3.atCenterOf(hopper.getBlockPos());
        var item = new ItemEntity(helper.getLevel(), absolute.x, absolute.y, absolute.z, new ItemStack(Items.GOLD_INGOT, 3));
        helper.getLevel().addFreshEntity(item);
        int start = allRecords(helper).size();
        hopper.getItem(0).shrink(2);
        helper.assertTrue(net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(hopper, item),
                "Hopper actually collected the loose stack");
        StorageAudit.observe(Storage.block(hopper));
        var rows = allRecords(helper).subList(start, allRecords(helper).size());
        helper.assertTrue(count(at(rows, hopper.getBlockPos()), "STORAGE_REMOVE", "minecraft:diamond") == 2,
                "Pending destination removal survives loose-item acknowledgment");
        assertSingleTransfer(helper, rows, "minecraft:gold_ingot", 3);
        helper.assertTrue(rows.size() == 3, "Only the pending change and transfer pair are logged");
        helper.succeed();
    }

    private static void assertSingleTransfer(GameTestHelper helper, List<Map<String, String>> rows, String item, int quantity) {
        var transfers = rows.stream().filter(r -> r.get("action").startsWith("HOPPER_")).toList();
        helper.assertTrue(transfers.size() == 2 && count(transfers, "HOPPER_REMOVE", item) == quantity
                        && count(transfers, "HOPPER_ADD", item) == quantity,
                "Transfer has exactly one balanced pair");
        helper.assertTrue(transfers.getFirst().get("event_id").equals(transfers.getLast().get("event_id")),
                "Both transfer endpoints share an event");
        helper.assertTrue(rows.stream().allMatch(r -> r.get("player_uuid").isEmpty()), "Unknown changes have no player");
    }


    @GameTest
    public void commandScanProbe(GameTestHelper helper) throws Exception {
        if (!Boolean.getBoolean("chestlogger.commandScanProbe")) { helper.succeed(); return; }
        var player = helper.makeMockServerPlayerInLevel();
        var commands = helper.getLevel().getServer().getCommands();
        try (var state = new AuditState()) {
            StorageAudit.clear();
            var chests = new ArrayList<CountingChest>();
            for (int size : new int[]{0, 64, 256, 1024}) {
                while (chests.size() < size) {
                    var chest = new CountingChest(helper.absolutePos(new BlockPos(2, 1, 2)));
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.DIAMOND, 64));
                    chest.setLevel(helper.getLevel());
                    StorageAudit.acknowledge(Storage.block(chest));
                    chests.add(chest);
                }
                for (int i = 0; i < 3; i++) commands.performPrefixedCommand(player.createCommandSourceStack(), "chestlogger info");
                chests.forEach(chest -> chest.reads = 0);
                long start = System.nanoTime();
                for (int i = 0; i < 10; i++) commands.performPrefixedCommand(player.createCommandSourceStack(), "chestlogger info");
                long elapsed = System.nanoTime() - start;
                System.out.println("COMMAND_SCAN_PROBE inventories=" + size + " commands=10 reads="
                        + chests.stream().mapToLong(chest -> chest.reads).sum() + " elapsed_ns=" + elapsed);
            }
        }
        helper.succeed();
    }

    private static final class CountingChest extends net.minecraft.world.level.block.entity.ChestBlockEntity {
        private long reads;
        private CountingChest(BlockPos pos) { super(pos, Blocks.CHEST.defaultBlockState()); }
        @Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
    }

    @GameTest
    public void disabledLootGenerationDoesNotRetainUnloadedStorage(GameTestHelper helper) throws Exception {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        try (var state = new AuditState()) {
            // Failed initialization leaves no logger or tracked owners.
            state.log.set(null, null);
            StorageAudit.clear();
            chest.unpackLootTable(null);
            helper.assertTrue(chest.getLootTable() == null && !chest.isEmpty(), "Vanilla actually generated loot");
            ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(chest, helper.getLevel());
            state.assertEmpty(helper, "Disabled loot generation and unload");
        }
        helper.succeed();
    }

    @GameTest
    public void shutdownClearsAuditStateEvenWithoutLogger(GameTestHelper helper) throws Exception {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        try (var state = new AuditState()) {
            StorageAudit.acknowledge(Storage.block(chest));
            state.log.set(null, null);
            var closeLog = ChestLoggerCsv.class.getDeclaredMethod("closeLog");
            closeLog.setAccessible(true);
            closeLog.invoke(null);
            state.assertEmpty(helper, "Shutdown without a logger");
        }
        helper.succeed();
    }

    @GameTest
    public void failedWriteDoesNotRetainObservedStorage(GameTestHelper helper) throws Exception {
        assertFailedWriteClearsState(helper, false);
    }

    @GameTest
    public void failedWriteDoesNotRetainScopedStorage(GameTestHelper helper) throws Exception {
        assertFailedWriteClearsState(helper, true);
    }

    private static void assertFailedWriteClearsState(GameTestHelper helper, boolean scoped) throws Exception {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        var path = Files.createTempFile("chestlogger-failed-write-", ".csv");
        try (var state = new AuditState()) {
            StorageAudit.clear();
            var storage = Storage.block(chest);
            StorageAudit.acknowledge(storage);
            // A closed writer deterministically exercises the production IOException path.
            var failedLog = new CsvFile(path);
            failedLog.close();
            state.log.set(null, failedLog);
            if (scoped) {
                try (var ignored = StorageAudit.scope(null, "COMMAND", storage)) {
                    chest.getItem(0).shrink(1);
                }
            } else {
                chest.getItem(0).shrink(1);
                StorageAudit.observe(storage);
            }
            helper.assertTrue(!ChestLoggerCsv.isLogging(), "Write failure actually disabled logging");
            state.assertEmpty(helper, "Write failure during " + (scoped ? "scope closure" : "observation"));
        } finally {
            Files.delete(path);
        }
        helper.succeed();
    }

    /** Preserve the other GameTests' baselines while exercising static failure/shutdown state. */
    private static final class AuditState implements AutoCloseable {
        private final Field log = ChestLoggerCsv.class.getDeclaredField("log");
        private final Object previousLog;
        private final Map<Map<Object, Object>, Map<Object, Object>> maps = new java.util.IdentityHashMap<>();
        private final Map<Collection<Object>, List<Object>> collections = new java.util.IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        private AuditState() throws ReflectiveOperationException {
            log.setAccessible(true);
            previousLog = log.get(null);
            for (var field : StorageAudit.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map<?, ?> map) maps.put((Map<Object, Object>) map, new LinkedHashMap<>((Map<Object, Object>) map));
                else if (value instanceof Collection<?> collection) collections.put((Collection<Object>) collection, new ArrayList<>(collection));
            }
        }

        private void assertEmpty(GameTestHelper helper, String operation) {
            helper.assertTrue(maps.keySet().stream().allMatch(Map::isEmpty)
                    && collections.keySet().stream().allMatch(Collection::isEmpty), operation + " leaves no retained audit state");
        }

        @Override public void close() throws IllegalAccessException {
            StorageAudit.clear();
            maps.forEach(Map::putAll);
            collections.forEach(Collection::addAll);
            log.set(null, previousLog);
        }
    }
}
