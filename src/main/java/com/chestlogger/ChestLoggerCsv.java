package com.chestlogger;

import com.chestlogger.csv.CsvCell;
import com.chestlogger.csv.CsvFile;
import com.chestlogger.csv.InventoryDiff;
import com.mojang.serialization.JsonOps;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
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
        // AFTER runs only for successful breaks, retaining the original entity's position and type.
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer
                    && blockEntity instanceof RandomizableContainerBlockEntity container) {
                event(serverPlayer, List.of(container), "BREAK", null);
            }
        });
    }

    private static void closeLog() {
        if (log == null) return;
        try { log.close(); }
        catch (IOException e) { LOGGER.error("Cannot close chest CSV", e); }
        finally { log = null; }
    }

    public static void opened(ServerPlayer player, AbstractContainerMenu menu) {
        event(player, Containers.inMenu(menu), "OPEN", null);
    }

    public static void closed(ServerPlayer player, AbstractContainerMenu menu) {
        event(player, Containers.inMenu(menu), "CLOSE", null);
    }

    public static void changed(ServerPlayer player,
            Map<RandomizableContainerBlockEntity, Map<InventorySnapshot.ItemKey, Integer>> before) {
        if (log == null || before.isEmpty()) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        for (var entry : before.entrySet()) {
            var container = entry.getKey();
            for (var change : InventoryDiff.between(entry.getValue(), InventorySnapshot.contents(container))) {
                rows.add(row(player, container, eventId, time, change.delta() > 0 ? "ADD" : "REMOVE",
                        change.item().stack(), change.delta(), null));
            }
        }
        write(rows);
    }

    public static void hopperPlaced(BlockPlaceContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return;
        BlockPos hopper = context.getClickedPos();
        var level = context.getLevel();
        if (level.getBlockState(hopper).is(Blocks.HOPPER)
                && level.getBlockEntity(hopper.above()) instanceof RandomizableContainerBlockEntity container) {
            // The directly affected physical half is enough; duplicating it inflates query totals.
            event(player, List.of(container), "HOPPER_PLACED_BELOW", hopper);
        }
    }

    private static void event(ServerPlayer player, List<RandomizableContainerBlockEntity> containers,
                              String action, BlockPos related) {
        if (log == null || containers.isEmpty()) return;
        ZonedDateTime time = ZonedDateTime.now();
        String eventId = UUID.randomUUID().toString();
        List<List<CsvCell>> rows = new ArrayList<>();
        for (var container : containers) {
            rows.add(row(player, container, eventId, time, action, null, 0, related));
        }
        write(rows);
    }

    private static List<CsvCell> row(ServerPlayer player, RandomizableContainerBlockEntity container,
                                    String eventId, ZonedDateTime time, String action,
                                    ItemStack item, int delta, BlockPos related) {
        var level = container.getLevel();
        BlockPos pos = container.getBlockPos();
        String itemData = "";
        if (item != null) {
            var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            itemData = ItemStack.CODEC.encodeStart(ops, item).resultOrPartial(
                    error -> LOGGER.warn("Unable to encode logged item data: {}", error))
                    .map(Object::toString).orElse("");
        }
        return List.of(
                CsvCell.text(eventId),
                CsvCell.text(time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                CsvCell.text(time.toLocalDate().toString()),
                CsvCell.text(time.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))),
                CsvCell.text(time.getZone().getId()),
                CsvCell.text(level.dimension().identifier().toString()),
                CsvCell.text(BuiltInRegistries.BLOCK.getKey(container.getBlockState().getBlock()).toString()),
                CsvCell.number(pos.getX()), CsvCell.number(pos.getY()), CsvCell.number(pos.getZ()),
                CsvCell.text(player.getGameProfile().name()), CsvCell.text(player.getUUID().toString()),
                CsvCell.text(action),
                CsvCell.text(item == null ? "" : BuiltInRegistries.ITEM.getKey(item.getItem()).toString()),
                CsvCell.text(item == null ? "" : item.getHoverName().getString()),
                item == null ? CsvCell.text("") : CsvCell.number(Math.abs(delta)),
                item == null ? CsvCell.text("") : CsvCell.number(delta),
                CsvCell.text(itemData),
                related == null ? CsvCell.text("") : CsvCell.number(related.getX()),
                related == null ? CsvCell.text("") : CsvCell.number(related.getY()),
                related == null ? CsvCell.text("") : CsvCell.number(related.getZ()));
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
