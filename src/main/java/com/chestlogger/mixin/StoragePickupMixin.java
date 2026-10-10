package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerCsv;
import com.chestlogger.Storage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemEntity.class)
public abstract class StoragePickupMixin {
    @WrapMethod(method = "playerTouch")
    private void chestlogger$pickup(Player actor, Operation<Void> original) {
        var item = (ItemEntity) (Object) this;
        if (!(actor instanceof ServerPlayer player) || !ChestLoggerCsv.isLogging()) { original.call(actor); return; }
        var storage = Storage.item(item);
        var before = storage.contents();
        original.call(actor);
        ChestLoggerCsv.auditChanges(player, storage, before, item.isRemoved() ? java.util.Map.of() : storage.contents(), "PICKUP_");
    }
}
