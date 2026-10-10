package com.chestlogger.mixin;

import com.chestlogger.StorageAudit;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class StorageEntityMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void chestlogger$removed(Entity.RemovalReason reason, CallbackInfo ci) {
        StorageAudit.entityRemoved((Entity) (Object) this, reason);
    }
}
