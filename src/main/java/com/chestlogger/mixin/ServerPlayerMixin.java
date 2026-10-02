package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerCsv;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "openMenu", at = @At("RETURN"))
    private void chestlogger$opened(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (cir.getReturnValue().isPresent()) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            ChestLoggerCsv.opened(player, player.containerMenu);
        }
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void chestlogger$closed(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        ChestLoggerCsv.closed(player, player.containerMenu);
    }
}
