package com.chestlogger.mixin;

import com.chestlogger.Storage;
import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public abstract class StorageLivingMixin {
    @WrapMethod(method = "die")
    private void chestlogger$death(DamageSource source, Operation<Void> original) {
        var entity = (LivingEntity) (Object) this;
        var storage = Storage.entity(entity);
        if (storage == null) { original.call(source); return; }
        try (var scope = StorageAudit.scope(source.getEntity() instanceof ServerPlayer player ? player : null, "DESTROY", storage)) {
            var before = StorageAudit.beforeRemoval(storage);
            try (var ignored = StorageAudit.suspend()) { original.call(source); }
            if (entity.isDeadOrDying()) StorageAudit.removed(storage, before);
        }
    }
}
