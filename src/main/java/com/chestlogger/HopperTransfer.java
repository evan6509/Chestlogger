package com.chestlogger;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** Observe a hopper insertion without changing vanilla's transfer or rollback behavior. */
public final class HopperTransfer {
    private final Storage source;
    private final ItemStack item;
    private final int offeredCount;
    private final Map<Storage, Integer> before;

    private HopperTransfer(Storage source, Container destination, ItemStack offered) {
        this.source = source;
        this.item = offered.copyWithCount(1);
        this.offeredCount = offered.getCount();
        this.before = new LinkedHashMap<>();
        for (var physical : Containers.inContainer(destination)) before.put(physical, count(physical.inventory, item));
    }

    public static HopperTransfer capture(Container source, int sourceSlot, Container destination, ItemStack offered) {
        if (!ChestLoggerCsv.isLogging() || offered.isEmpty()) return null;
        var physicalSource = Containers.atSlot(source, sourceSlot);
        if (physicalSource == null) return null;
        var transfer = new HopperTransfer(physicalSource, destination, offered);
        return transfer.before.isEmpty() ? null : transfer;
    }

    public void complete(ItemStack remainder) {
        int moved = offeredCount - remainder.getCount();
        // A failed insertion is restored by vanilla and must not create a removal row.
        if (moved <= 0) return;
        Map<Storage, Integer> additions = new LinkedHashMap<>();
        for (var entry : before.entrySet()) {
            int delta = count(entry.getKey().inventory, item) - entry.getValue();
            if (delta > 0) additions.put(entry.getKey(), delta);
        }
        // Only log a complete, balanced transfer between supported physical containers.
        if (additions.values().stream().mapToInt(Integer::intValue).sum() == moved) {
            ChestLoggerCsv.hopperTransferred(source, additions, item);
            StorageAudit.acknowledge(source);
            additions.keySet().forEach(StorageAudit::acknowledge);
        }
    }

    private static int count(Container container, ItemStack item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, item)) count += stack.getCount();
        }
        return count;
    }
}
