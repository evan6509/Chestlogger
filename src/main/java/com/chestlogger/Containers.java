package com.chestlogger;

import com.chestlogger.mixin.CompoundContainerAccessor;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Containers {
    private Containers() { }

    public static List<RandomizableContainerBlockEntity> inMenu(AbstractContainerMenu menu) {
        Set<RandomizableContainerBlockEntity> found = new LinkedHashSet<>();
        Set<Container> visited = new LinkedHashSet<>();
        for (var slot : menu.slots) collect(slot.container, found, visited);
        return List.copyOf(found);
    }

    private static void collect(Container container, Set<RandomizableContainerBlockEntity> found,
                                Set<Container> visited) {
        if (!visited.add(container)) return;
        if (container instanceof RandomizableContainerBlockEntity blockEntity) {
            if (blockEntity.getLevel() != null && !blockEntity.getLevel().isClientSide()) found.add(blockEntity);
        } else if (container instanceof CompoundContainer combined) {
            var accessor = (CompoundContainerAccessor) combined;
            collect(accessor.chestlogger$getFirst(), found, visited);
            collect(accessor.chestlogger$getSecond(), found, visited);
        }
    }
}
