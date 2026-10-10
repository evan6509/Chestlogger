package com.chestlogger;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Audit only loaded inventories. Known operations acknowledge their changes to avoid duplicate totals. */
public final class StorageAudit {
    private static final Map<Object, Storage> stores = new IdentityHashMap<>();
    private static final Map<Container, Storage> inventories = new IdentityHashMap<>();
    private static final Map<Storage, Map<InventorySnapshot.ItemKey, Integer>> last = new LinkedHashMap<>();
    private static final java.util.Set<Object> destroyedOwners = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final Map<Object, java.util.List<Removal>> pendingRemovals = new IdentityHashMap<>();
    private static Scope active;
    private static int suspended;

    public static void initialize() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> track(Storage.block(entity)));
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            var storage = stores.get(entity);
            if (!pendingRemovals.containsKey(entity)) observe(storage);
            forget(storage);
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> chunk.getBlockEntities().values().forEach(entity ->
                acknowledge(track(Storage.block(entity)))));
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            var storage = track(Storage.entity(entity));
            acknowledge(storage);
            if (entity instanceof ServerPlayer player) acknowledge(track(Storage.ender(player)));
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (!pendingRemovals.containsKey(entity) && !destroyedOwners.contains(entity)) observe(stores.get(entity));
            forget(stores.get(entity));
            destroyedOwners.remove(entity);
            if (entity instanceof ServerPlayer player) forget(stores.get(player.getEnderChestInventory()));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ChestLoggerCsv.isLogging()) new ArrayList<>(stores.values()).forEach(StorageAudit::observe);
        });
    }

    public static Storage track(Storage storage) {
        if (storage == null || !ChestLoggerCsv.isLogging()) return storage;
        var existing = stores.get(storage.owner);
        // Horses can resize their inventory after a chest is attached.
        if (existing != null && existing.inventory == storage.inventory) return existing;
        // Retain the previous total when an inventory is resized/replaced. Old snapshots
        // still resolve the owner's current contents, so discarded slots remain auditable.
        if (existing != null && existing.inventory != null) inventories.remove(existing.inventory);
        stores.put(storage.owner, storage);
        if (storage.inventory != null) inventories.put(storage.inventory, storage);
        return storage;
    }
    public static Storage find(Container inventory) { return inventories.get(inventory); }
    public static void acknowledge(Storage storage) {
        if (storage == null || !ChestLoggerCsv.isLogging()) return;
        storage = track(storage);
        var contents = storage.contents();
        if (contents != null) last.put(storage, contents);
    }
    public static void observe(Storage storage) {
        if (storage == null || suspended > 0 || !ChestLoggerCsv.isLogging()) return;
        storage = track(storage.owner instanceof Entity entity ? Storage.entity(entity) : storage);
        if (storage == null || destroyedOwners.contains(storage.owner)) return;
        var contents = storage.contents();
        if (contents == null) return; // Reading unopened loot inventories would change vanilla loot generation.
        if (active != null) {
            active.before.putIfAbsent(storage, last.getOrDefault(storage, contents));
            return;
        }
        ChestLoggerCsv.auditChanges(null, storage, last.get(storage), contents, "STORAGE_");
        last.put(storage, contents);
    }
    public static void observe(Object owner) {
        if (owner instanceof BlockEntity entity) observe(Storage.block(entity));
        else if (owner instanceof Container container) observe(find(container));
    }
    public static void forget(Storage storage) {
        if (storage == null) return;
        stores.remove(storage.owner);
        if (storage.inventory != null) inventories.remove(storage.inventory);
        last.remove(storage);
    }
    public static void clear() { stores.clear(); inventories.clear(); last.clear(); destroyedOwners.clear(); pendingRemovals.clear(); active = null; suspended = 0; }
    public static void prepareCommand(Scope scope) {
        for (var storage : new ArrayList<>(stores.values())) scope.prepare(storage);
    }

    public static Scope scope(ServerPlayer actor, String cause, Storage... targets) {
        var scope = new Scope(actor, cause);
        for (var target : targets) scope.prepare(target);
        active = scope;
        return scope;
    }
    public static Silence suspend() { suspended++; return new Silence(); }
    public static final class Silence implements AutoCloseable {
        @Override public void close() { suspended = Math.max(0, suspended - 1); }
    }
    public static final class Scope implements AutoCloseable {
        private final Scope parent = active;
        public final ServerPlayer actor;
        public final String cause;
        private final Map<Storage, Map<InventorySnapshot.ItemKey, Integer>> before = new LinkedHashMap<>();
        private final java.util.Set<Storage> attributed = new java.util.HashSet<>();
        private Scope(ServerPlayer actor, String cause) { this.actor = actor; this.cause = cause; }
        public void prepare(Storage storage) {
            if (storage == null) return;
            var previous = active;
            active = parent;
            try { observe(storage); } finally { active = previous; }
            storage = track(storage);
            var contents = storage.contents();
            if (parent != null && contents != null && parent.before.containsKey(storage)) {
                parent.flush(storage, parent.before.get(storage), contents);
                parent.before.put(storage, contents);
            }
            if (contents != null) before.put(storage, contents);
            attributed.add(storage);
        }
        @Override public void close() {
            active = parent;
            for (var entry : before.entrySet()) {
                if (!stores.containsKey(entry.getKey().owner)) continue;
                var after = entry.getKey().contents();
                flush(entry.getKey(), entry.getValue(), after);
                if (after != null) last.put(entry.getKey(), after);
                if (parent != null && after != null && parent.before.containsKey(entry.getKey())) parent.before.put(entry.getKey(), after);
            }
        }
        private void flush(Storage storage, Map<InventorySnapshot.ItemKey, Integer> before, Map<InventorySnapshot.ItemKey, Integer> after) {
            boolean known = attributed.contains(storage) || cause.equals("COMMAND");
            ChestLoggerCsv.auditChanges(known ? actor : null, storage, before, after,
                    known ? cause.equals("COMMAND") ? "COMMAND_" : "" : "STORAGE_");
        }
    }

    public static Map<InventorySnapshot.ItemKey, Integer> beforeRemoval(Storage storage) {
        if (storage == null) return null;
        storage = track(storage);
        if (active != null && active.before.containsKey(storage)) return active.before.get(storage);
        var contents = storage.contents();
        if (contents != null) return contents;
        return last.get(storage);
    }
    public static void removed(Storage storage, Map<InventorySnapshot.ItemKey, Integer> contents) {
        if (storage == null) return;
        String action = active == null ? "DESTROY" : active.cause;
        if (!java.util.Set.of("BREAK", "EXPLOSION", "COMMAND", "DESTROY").contains(action)) action = "DESTROY";
        ChestLoggerCsv.destroyed(active == null ? null : active.actor, storage, contents, action);
        if (storage.owner instanceof Entity) destroyedOwners.add(storage.owner);
        if (active != null) active.before.remove(storage);
        forget(storage);
    }
    public static void entityRemoved(Entity entity, Entity.RemovalReason reason) {
        if (suspended > 0 || !reason.shouldDestroy() || destroyedOwners.contains(entity)) return;
        var storage = track(Storage.entity(entity));
        if (storage != null) {
            removed(storage, beforeRemoval(storage));
            destroyedOwners.add(entity);
        }
    }

    public static Removal captureRemoval(Storage storage) { return new Removal(storage); }
    public static final class Removal implements AutoCloseable {
        public final Storage storage;
        private Map<InventorySnapshot.ItemKey, Integer> contents;
        private Removal(Storage storage) {
            this.storage = storage;
            contents = beforeRemoval(storage);
            if (storage != null) pendingRemovals.computeIfAbsent(storage.owner, ignored -> new ArrayList<>()).add(this);
        }
        public void finish() {
            if (storage != null && !destroyedOwners.contains(storage.owner)) removed(storage, contents);
        }
        @Override public void close() {
            if (storage == null) return;
            var pending = pendingRemovals.get(storage.owner);
            if (pending != null) {
                pending.remove(this);
                if (pending.isEmpty()) pendingRemovals.remove(storage.owner);
            }
        }
    }
    public static void lootGenerated(Container container) {
        var storage = Containers.resolve(container);
        if (storage == null) return;
        var contents = storage.contents();
        if (contents == null) return;
        last.put(storage, contents);
        var pending = pendingRemovals.get(storage.owner);
        if (pending != null) for (var removal : pending) if (removal.contents == null) removal.contents = contents;
        if (active != null && !active.before.containsKey(storage)) active.before.put(storage, contents);
    }
}
