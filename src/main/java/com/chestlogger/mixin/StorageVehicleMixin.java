package com.chestlogger.mixin;

import com.chestlogger.Storage;
import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({AbstractChestBoat.class, AbstractMinecartContainer.class})
public abstract class StorageVehicleMixin {
    @WrapMethod(method = "remove")
    private void chestlogger$removed(Entity.RemovalReason reason, Operation<Void> original) {
        try (var removal = StorageAudit.captureRemoval(Storage.entity((Entity) (Object) this))) {
            try (var ignored = StorageAudit.suspend()) { original.call(reason); }
            if (reason.shouldDestroy()) removal.finish();
        }
    }
    @WrapMethod(method = "destroy")
    private void chestlogger$destroyed(ServerLevel level, DamageSource source, Operation<Void> original) {
        var storage = Storage.entity((Entity) (Object) this);
        try (var scope = StorageAudit.scope(source.getEntity() instanceof ServerPlayer player ? player : null, "DESTROY", storage)) {
            original.call(level, source);
        }
    }
}
