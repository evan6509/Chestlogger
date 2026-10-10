package com.chestlogger.mixin;

import com.chestlogger.Storage;
import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
public abstract class StorageInteractionMixin {
    @Shadow protected ServerPlayer player;
    @WrapMethod(method = "destroyBlock")
    private boolean chestlogger$break(BlockPos pos, Operation<Boolean> original) {
        try (var scope = StorageAudit.scope(player, "BREAK", Storage.block(player.level().getBlockEntity(pos)))) {
            return original.call(pos);
        }
    }
    @WrapMethod(method = "useItemOn")
    private InteractionResult chestlogger$interact(ServerPlayer actor, Level level, ItemStack item,
            InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        try (var scope = StorageAudit.scope(actor, "INTERACTION", Storage.block(level.getBlockEntity(hit.getBlockPos())))) {
            return original.call(actor, level, item, hand, hit);
        }
    }
}
