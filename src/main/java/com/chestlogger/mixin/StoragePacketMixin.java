package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerCsv;
import com.chestlogger.InventorySnapshot;
import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class StoragePacketMixin {
    @Shadow public ServerPlayer player;
    @WrapMethod(method = "handleContainerButtonClick")
    private void chestlogger$button(ServerboundContainerButtonClickPacket packet, Operation<Void> original) {
        if (!player.level().getServer().isSameThread()) { original.call(packet); return; }
        var before = InventorySnapshot.capture(player.containerMenu, player);
        try (var ignored = StorageAudit.suspend()) {
            try { original.call(packet); } finally { ChestLoggerCsv.changed(player, before); }
        }
    }
    @WrapMethod(method = "handlePlaceRecipe")
    private void chestlogger$recipe(ServerboundPlaceRecipePacket packet, Operation<Void> original) {
        if (!player.level().getServer().isSameThread()) { original.call(packet); return; }
        var before = InventorySnapshot.capture(player.containerMenu, player);
        try (var ignored = StorageAudit.suspend()) {
            try { original.call(packet); } finally { ChestLoggerCsv.changed(player, before); }
        }
    }
}
