package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerCsv;
import com.chestlogger.Storage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecartItem.class)
public abstract class StorageMinecartPlacementMixin {
    @WrapOperation(method = "useOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean chestlogger$placed(ServerLevel level, Entity entity, Operation<Boolean> original,
                                       @Local(argsOnly = true) UseOnContext context) {
        boolean added = original.call(level, entity);
        var storage = Storage.entity(entity);
        if (added && storage != null && context.getPlayer() instanceof ServerPlayer player) {
            ChestLoggerCsv.placedEntity(player, storage);
        }
        return added;
    }
}
