package com.chestlogger.mixin;

import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.Container;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LootTable.class)
public abstract class StorageLootMixin {
    @WrapMethod(method = "fill")
    private void chestlogger$loot(Container container, LootParams params, long seed, Operation<Void> original) {
        try (var ignored = StorageAudit.suspend()) { original.call(container, params, seed); }
        StorageAudit.lootGenerated(container);
    }
}
