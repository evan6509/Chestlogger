package com.chestlogger.mixin;

import com.chestlogger.HopperTransfer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @WrapOperation(method = "ejectItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack chestlogger$pushed(Container source, Container destination, ItemStack item,
                                              Direction direction, Operation<ItemStack> original) {
        // The source is the hopper block itself, so every slot has the same physical position.
        var transfer = HopperTransfer.capture(source, 0, destination, item);
        ItemStack remainder = original.call(source, destination, item, direction);
        if (transfer != null) transfer.complete(remainder);
        return remainder;
    }

    @WrapOperation(method = "tryTakeInItemFromSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack chestlogger$pulled(Container source, Container destination, ItemStack item,
                                              Direction direction, Operation<ItemStack> original,
                                              @Local(argsOnly = true) int sourceSlot) {
        var transfer = HopperTransfer.capture(source, sourceSlot, destination, item);
        ItemStack remainder = original.call(source, destination, item, direction);
        if (transfer != null) transfer.complete(remainder);
        return remainder;
    }
}
