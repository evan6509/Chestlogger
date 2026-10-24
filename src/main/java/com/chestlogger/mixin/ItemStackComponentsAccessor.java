package com.chestlogger.mixin;

import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStack.class)
public interface ItemStackComponentsAccessor {
    @Accessor("components") PatchedDataComponentMap chestlogger$getComponents();
}
