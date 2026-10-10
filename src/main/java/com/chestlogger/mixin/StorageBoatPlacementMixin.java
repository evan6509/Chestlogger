package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerCsv;
import com.chestlogger.Storage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BoatItem.class)
public abstract class StorageBoatPlacementMixin {
    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean chestlogger$placed(Level level, Entity entity, Operation<Boolean> original,
                                       @Local(argsOnly = true) Player actor) {
        boolean added = original.call(level, entity);
        var storage = Storage.entity(entity);
        if (added && storage != null && actor instanceof ServerPlayer player) {
            ChestLoggerCsv.placedEntity(player, storage);
        }
        return added;
    }
}
