package com.chestlogger.mixin;

import com.chestlogger.StorageAudit;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class StorageChangeMixin {
    @Inject(method = "setChanged()V", at = @At("RETURN"))
    private void chestlogger$changed(CallbackInfo ci) { StorageAudit.observe(this); }
}
