package com.chestlogger.mixin;

import com.chestlogger.HopperTransfer;
import com.chestlogger.Storage;
import com.chestlogger.StorageAudit;
import com.chestlogger.Containers;
import com.chestlogger.ChestLoggerCsv;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @WrapMethod(method = "ejectItems")
    private static boolean chestlogger$pushAttempt(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                                                  HopperBlockEntity hopper, Operation<Boolean> original) {
        try (var ignored = StorageAudit.suspend()) { return original.call(level, pos, hopper); }
    }
    @WrapMethod(method = "tryTakeInItemFromSlot")
    private static boolean chestlogger$pullAttempt(Hopper hopper, Container source, int slot, Direction direction,
                                                  Operation<Boolean> original) {
        try (var ignored = StorageAudit.suspend()) { return original.call(hopper, source, slot, direction); }
    }
    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
    private static boolean chestlogger$looseItem(Container destination, ItemEntity entity, Operation<Boolean> original) {
        var source = Storage.item(entity);
        var target = Containers.resolve(destination);
        ItemStack item = entity.getItem().copy();
        try (var ignored = StorageAudit.suspend()) {
            boolean result = original.call(destination, entity);
            int moved = item.getCount() - (entity.isRemoved() ? 0 : entity.getItem().getCount());
            if (target != null && moved > 0) {
                ChestLoggerCsv.hopperTransferred(source, java.util.Map.of(target, moved), item.copyWithCount(1));
                StorageAudit.acknowledge(target);
            }
            return result;
        }
    }
    @WrapOperation(method = "ejectItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack chestlogger$pushed(Container source, Container destination, ItemStack item,
                                              Direction direction, Operation<ItemStack> original) {
        // The source is the hopper block itself, so every slot has the same physical position.
        var transfer = HopperTransfer.capture(source, 0, destination, item);
        try (var ignored = com.chestlogger.StorageAudit.suspend()) {
            ItemStack remainder = original.call(source, destination, item, direction);
            if (transfer != null) transfer.complete(remainder);
            return remainder;
        }
    }

    @WrapOperation(method = "tryTakeInItemFromSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack chestlogger$pulled(Container source, Container destination, ItemStack item,
                                              Direction direction, Operation<ItemStack> original,
                                              @Local(argsOnly = true) int sourceSlot) {
        var transfer = HopperTransfer.capture(source, sourceSlot, destination, item);
        try (var ignored = com.chestlogger.StorageAudit.suspend()) {
            ItemStack remainder = original.call(source, destination, item, direction);
            if (transfer != null) transfer.complete(remainder);
            return remainder;
        }
    }
}
