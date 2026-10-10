package com.chestlogger.mixin;

import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerExplosion.class)
public abstract class StorageExplosionMixin {
    @WrapMethod(method = "explode")
    private int chestlogger$explosion(Operation<Integer> original) {
        var source = ((ServerExplosion) (Object) this).getIndirectSourceEntity();
        try (var scope = StorageAudit.scope(source instanceof ServerPlayer player ? player : null, "EXPLOSION")) {
            return original.call();
        }
    }
}
