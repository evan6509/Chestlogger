package com.chestlogger;

import com.chestlogger.csv.CsvCell;
import com.chestlogger.csv.CsvFile;
import com.chestlogger.csv.InventoryDiff;
import com.mojang.serialization.JsonOps;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChestLoggerCsv implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("chestlogger_csv");
    private static CsvFile log;

    @Override
    public void onInitialize() {
        ChestLoggerCommands.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            var path = server.getWorldPath(LevelResource.ROOT).resolve("ChestLog").resolve("chestlog.csv");
            try {
                log = new CsvFile(path);
                LOGGER.info("Writing container activity to {}", path.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Cannot open chest CSV; container logging is disabled", e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> closeLog());
        StorageAudit.initialize();
    }

    private static void closeLog() {
        if (log == null) return;
        try { log.close(); }
        catch (IOException e) { LOGGER.error("Cannot close chest CSV", e); }
        finally { log = null; StorageAudit.clear(); }
    }

    public static void opened(ServerPlayer player, AbstractContainerMenu menu) {
        event(player, Containers.inMenu(menu, player), "OPEN", null);
    }

    public static void closed(ServerPlayer player, AbstractContainerMenu menu) {
        event(player, Containers.inMenu(menu, player), "CLOSE", null);
    }

    public static boolean isLogging() { return log != null; }

    public static void hopperTransferred(Storage source,
                                         Map<Storage, Integer> additions, ItemStack item) {
        if (log == null) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        for (var entry : additions.entrySet()) {
            var destination = entry.getKey();
            int quantity = entry.getValue();
            rows.add(row(null, source, eventId, time, "HOPPER_REMOVE", item, -quantity, destination));
            rows.add(row(null, destination, eventId, time, "HOPPER_ADD", item, quantity, source));
        }
        write(rows);
    }

    public static void changed(ServerPlayer player,
            Map<Storage, Map<InventorySnapshot.ItemKey, Integer>> before) {
        if (log == null || before.isEmpty()) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        for (var entry : before.entrySet()) {
            var container = entry.getKey();
            var after = container.contents();
            if (after == null) continue;
            for (var change : InventoryDiff.between(entry.getValue(), after)) {
                rows.add(row(player, container, eventId, time, change.delta() > 0 ? "ADD" : "REMOVE",
                        change.item().stack(), change.delta(), null));
            }
        }
        write(rows);
        before.keySet().forEach(StorageAudit::acknowledge);
    }

    public static void placed(BlockPlaceContext context) {
        if (log == null || !(context.getPlayer() instanceof ServerPlayer player)) return;
        BlockPos pos = context.getClickedPos();
        var level = context.getLevel();
        var placed = StorageAudit.track(Storage.block(level.getBlockEntity(pos)));
        if (placed == null) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        rows.add(row(player, placed, eventId, time, "PLACE", null, 0, null));
        if (level.getBlockState(pos).is(Blocks.HOPPER)
                && Storage.block(level.getBlockEntity(pos.above())) != null) {
            // The directly affected physical half is enough; duplicating it inflates query totals.
            rows.add(row(player, Storage.block(level.getBlockEntity(pos.above())), eventId, time, "HOPPER_PLACED_BELOW", null, 0, placed));
        }
        var contents = placed.contents();
        if (contents != null) for (var entry : contents.entrySet()) {
            rows.add(row(player, placed, eventId, time, "PLACE_CONTENTS", entry.getKey().stack(), entry.getValue(), null));
        }
        write(rows);
        StorageAudit.acknowledge(placed);
    }

    public static void auditChanges(ServerPlayer player, Storage storage,
            Map<InventorySnapshot.ItemKey, Integer> before, Map<InventorySnapshot.ItemKey, Integer> after,
            String prefix) {
        if (log == null || before == null || after == null) return;
        var changes = InventoryDiff.between(before, after);
        if (changes.isEmpty()) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        for (var change : changes) {
            rows.add(row(player, storage, eventId, time, prefix + (change.delta() > 0 ? "ADD" : "REMOVE"),
                    change.item().stack(), change.delta(), null));
        }
        write(rows);
    }

    public static void placedEntity(ServerPlayer player, Storage storage) {
        if (log == null) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        rows.add(row(player, storage, eventId, time, "PLACE", null, 0, null));
        var contents = storage.contents();
        if (contents != null) for (var entry : contents.entrySet()) {
            rows.add(row(player, storage, eventId, time, "PLACE_CONTENTS", entry.getKey().stack(), entry.getValue(), null));
        }
        write(rows);
        StorageAudit.acknowledge(storage);
    }

    public static void destroyed(ServerPlayer player, Storage storage,
            Map<InventorySnapshot.ItemKey, Integer> contents, String action) {
        if (log == null) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        rows.add(row(player, storage, eventId, time, action, null, 0, null));
        if (contents != null) for (var entry : contents.entrySet()) {
            rows.add(row(player, storage, eventId, time, action + "_CONTENTS", entry.getKey().stack(), -entry.getValue(), null));
        }
        write(rows);
    }

    private static void event(ServerPlayer player, List<Storage> containers,
                              String action, Storage related) {
        if (log == null || containers.isEmpty()) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        for (var container : containers) {
            rows.add(row(player, container, eventId, time, action, null, 0, related));
        }
        write(rows);
    }

    private static List<CsvCell> row(ServerPlayer player, Storage container,
                                    String eventId, ZonedDateTime time, String action,
                                    ItemStack item, int delta, Storage related) {
        var level = container.level();
        BlockPos pos = container.pos();
        String itemData = "";
        if (item != null) {
            var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            itemData = ItemStack.CODEC.encodeStart(ops, item).resultOrPartial(
                    error -> LOGGER.warn("Unable to encode logged item data: {}", error))
                    .map(Object::toString).orElse("");
        }
        return List.of(
                CsvCell.text(eventId),
                CsvCell.text(time.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                CsvCell.text(time.toLocalDate().toString()),
                CsvCell.text(time.format(DateTimeFormatter.ofPattern("HH:mm:ss"))),
                CsvCell.text(time.getZone().getId()),
                CsvCell.text(level.dimension().identifier().toString()),
                CsvCell.text(container.type()),
                CsvCell.number(pos.getX()), CsvCell.number(pos.getY()), CsvCell.number(pos.getZ()),
                CsvCell.text(player == null ? "" : player.getGameProfile().name()),
                CsvCell.text(player == null ? "" : player.getUUID().toString()),
                CsvCell.text(action),
                CsvCell.text(item == null ? "" : BuiltInRegistries.ITEM.getKey(item.getItem()).toString()),
                CsvCell.text(item == null ? "" : item.getHoverName().getString()),
                item == null ? CsvCell.text("") : CsvCell.number(Math.abs(delta)),
                item == null ? CsvCell.text("") : CsvCell.number(delta),
                CsvCell.text(itemData),
                related == null ? CsvCell.text("") : CsvCell.number(related.pos().getX()),
                related == null ? CsvCell.text("") : CsvCell.number(related.pos().getY()),
                related == null ? CsvCell.text("") : CsvCell.number(related.pos().getZ()),
                CsvCell.text(container.entityUuid),
                CsvCell.text(related == null ? "" : related.entityUuid),
                CsvCell.text(container.ownerUuid));
    }

    private static void write(List<List<CsvCell>> rows) {
        if (rows.isEmpty() || log == null) return;
        try { log.append(rows); }
        catch (IOException e) {
            LOGGER.error("Cannot write chest CSV; logging is disabled until the server restarts", e);
            closeLog();
        }
    }
}
