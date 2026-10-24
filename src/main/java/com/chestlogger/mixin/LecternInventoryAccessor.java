package com.chestlogger.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LecternBlockEntity.class)
public interface LecternInventoryAccessor {
    @Accessor("bookAccess") Container chestlogger$getInventory();
}
