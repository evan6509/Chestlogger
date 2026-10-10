package com.chestlogger.mixin;

import com.chestlogger.StorageAudit;
import net.minecraft.world.SimpleContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleContainer.class)
public abstract class SimpleStorageChangeMixin {
    @Inject(method = "setChanged", at = @At("RETURN"))
    private void chestlogger$changed(CallbackInfo ci) { StorageAudit.observe(this); }
}
