package com.chestlogger.mixin;

import com.chestlogger.Storage;
import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelChunk.class)
public abstract class StorageBlockMixin {
    @WrapMethod(method = "setBlockState")
    private BlockState chestlogger$removed(BlockPos pos, BlockState state, int flags, Operation<BlockState> original) {
        var chunk = (LevelChunk) (Object) this;
        var oldState = chunk.getBlockState(pos);
        if (!oldState.hasBlockEntity() || oldState.shouldChangedStateKeepBlockEntity(state)) return original.call(pos, state, flags);
        var entity = chunk.getBlockEntity(pos);
        var storage = Storage.block(entity);
        try (var removal = StorageAudit.captureRemoval(storage)) {
            var result = original.call(pos, state, flags);
            if (result != null && storage != null && chunk.getBlockEntity(pos) != entity) removal.finish();
            return result;
        }
    }
}
