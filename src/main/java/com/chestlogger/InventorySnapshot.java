package com.chestlogger;

import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

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

    public static Map<ItemKey, Integer> contents(Iterable<ItemStack> container) {
        Map<ItemKey, Integer> result = new LinkedHashMap<>();
        for (ItemStack stack : container) {
            if (!stack.isEmpty()) result.merge(new ItemKey(stack), stack.getCount(), Integer::sum);
        }
        return result;
    }

    public static Map<Storage, Map<ItemKey, Integer>> capture(AbstractContainerMenu menu, ServerPlayer player) {
        Map<Storage, Map<ItemKey, Integer>> result = new LinkedHashMap<>();
        for (var container : Containers.inMenu(menu, player)) {
            StorageAudit.observe(container);
            var contents = container.contents();
            if (contents != null) result.put(container, contents);
        }
        return result;
    }
}
