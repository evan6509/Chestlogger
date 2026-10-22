package com.chestlogger;

import com.chestlogger.mixin.ItemStackComponentsAccessor;
import com.chestlogger.mixin.PatchedComponentsAccessor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.Collections;
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

    /** Server-thread cache: compare every slot before reusing immutable totals. */
    public static final class Cache {
        private int[] counts;
        private ItemStack[] stacks;
        private Object[] patches;
        private Map<ItemKey, Integer> totals = Map.of();

        public Map<ItemKey, Integer> contents(Iterable<ItemStack> inventory) {
            int size = counts == null ? 0 : counts.length;
            int slot = 0;
            boolean unchanged = true;
            for (var stack : inventory) {
                int count = stack.getCount();
                if (slot >= size || stack != stacks[slot] || count != counts[slot]
                        || count != 0 && patch(stack) != patches[slot]) {
                    unchanged = false;
                    break;
                }
                slot++;
            }
            if (unchanged && slot == size) return totals;

            var current = new ArrayList<ItemStack>();
            for (var stack : inventory) current.add(stack);
            // ItemKey copies mark every populated stack's component patch copy-on-write.
            // Keep that patch identity as an exact token: native component mutators must
            // replace it before writing. Count is captured separately; a different stack
            // always rebuilds (its item and component prototype may differ).
            var updated = Collections.unmodifiableMap(InventorySnapshot.contents(current));
            var nextStacks = current.toArray(ItemStack[]::new);
            var nextCounts = new int[nextStacks.length];
            var nextPatches = new Object[nextStacks.length];
            for (int i = 0; i < nextStacks.length; i++) {
                nextCounts[i] = nextStacks[i].getCount();
                if (nextCounts[i] != 0) nextPatches[i] = patch(nextStacks[i]);
            }
            stacks = nextStacks;
            counts = nextCounts;
            patches = nextPatches;
            totals = updated;
            return totals;
        }

        private static Object patch(ItemStack stack) {
            var components = ((ItemStackComponentsAccessor) (Object) stack).chestlogger$getComponents();
            return ((PatchedComponentsAccessor) (Object) components).chestlogger$getPatch();
        }
    }

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
