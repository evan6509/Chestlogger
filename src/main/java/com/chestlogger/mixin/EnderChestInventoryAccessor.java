package com.chestlogger.mixin;

import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerEnderChestContainer.class)
public interface EnderChestInventoryAccessor {
    @Accessor("activeChest") EnderChestBlockEntity chestlogger$getActiveChest();
}
