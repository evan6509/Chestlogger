package com.chestlogger;

import com.chestlogger.mixin.CompoundContainerAccessor;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Containers {
    private Containers() { }

    public static List<Storage> inMenu(AbstractContainerMenu menu, ServerPlayer player) {
        StorageAudit.track(Storage.ender(player));
        Set<Storage> found = new LinkedHashSet<>();
        Set<Container> visited = new LinkedHashSet<>();
        for (var slot : menu.slots) collect(slot.container, found, visited);
        return List.copyOf(found);
    }

    public static List<Storage> inContainer(Container container) {
        Set<Storage> found = new LinkedHashSet<>();
        collect(container, found, new LinkedHashSet<>());
        return List.copyOf(found);
    }

    /** Resolve a combined inventory slot to the physical chest half that owns it. */
    public static Storage atSlot(Container container, int slot) {
        if (container instanceof CompoundContainer combined) {
            var accessor = (CompoundContainerAccessor) combined;
            var first = accessor.chestlogger$getFirst();
            return slot < first.getContainerSize() ? atSlot(first, slot)
                    : atSlot(accessor.chestlogger$getSecond(), slot - first.getContainerSize());
        }
        return resolve(container);
    }

    public static Storage resolve(Container container) {
        if (container instanceof BlockEntity block) return StorageAudit.track(Storage.block(block));
        if (container instanceof Entity entity) return StorageAudit.track(Storage.entity(entity));
        return StorageAudit.find(container);
    }

    private static void collect(Container container, Set<Storage> found,
                                Set<Container> visited) {
        if (!visited.add(container)) return;
        if (container instanceof CompoundContainer combined) {
            var accessor = (CompoundContainerAccessor) combined;
            collect(accessor.chestlogger$getFirst(), found, visited);
            collect(accessor.chestlogger$getSecond(), found, visited);
        } else {
            var storage = resolve(container);
            if (storage != null) found.add(storage);
        }
    }
}
