package com.chestlogger.mixin;

import com.chestlogger.StorageAudit;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Commands.class)
public abstract class StorageCommandMixin {
    @WrapMethod(method = "performCommand")
    private void chestlogger$command(ParseResults<CommandSourceStack> parse, String command, Operation<Void> original) {
        try (var scope = StorageAudit.scope(parse.getContext().getSource().getPlayer(), "COMMAND")) {
            StorageAudit.prepareCommand(scope);
            original.call(parse, command);
        }
    }
}
