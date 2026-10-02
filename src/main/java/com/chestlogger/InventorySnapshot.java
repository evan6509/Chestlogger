package com.chestlogger;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InventorySnapshot {
    /** A private, count-normalized copy keeps component changes distinct and keys immutable. */
    public static final class ItemKey {
        private final ItemStack stack;

        public ItemKey(ItemStack stack) { this.stack = stack.copyWithCount(1); }
        public ItemStack stack() { return stack.copy(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof ItemKey key && ItemStack.isSameItemSameComponents(stack, key.stack);
        }

        @Override
        public int hashCode() { return ItemStack.hashItemAndComponents(stack); }
    }

    private InventorySnapshot() { }

    public static Map<ItemKey, Integer> contents(RandomizableContainerBlockEntity container) {
        Map<ItemKey, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) result.merge(new ItemKey(stack), stack.getCount(), Integer::sum);
        }
        return result;
    }

    public static Map<RandomizableContainerBlockEntity, Map<ItemKey, Integer>> capture(AbstractContainerMenu menu) {
        Map<RandomizableContainerBlockEntity, Map<ItemKey, Integer>> result = new LinkedHashMap<>();
        for (var container : Containers.inMenu(menu)) result.put(container, contents(container));
        return result;
    }
}
