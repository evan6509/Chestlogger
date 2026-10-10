package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerCsv;
import com.chestlogger.InventorySnapshot;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
    @WrapMethod(method = "clicked")
    private void chestlogger$clicked(int slot, int button, ContainerInput input, Player player,
                                    Operation<Void> original) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            original.call(slot, button, input, player);
            return;
        }
        var before = InventorySnapshot.capture((AbstractContainerMenu) (Object) this, serverPlayer);
        try (var ignored = com.chestlogger.StorageAudit.suspend()) {
            try {
                original.call(slot, button, input, player);
            } finally {
                ChestLoggerCsv.changed(serverPlayer, before);
            }
        }
    }
}
