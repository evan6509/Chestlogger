package com.chestlogger;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ChestLoggerCommands {
    private ChestLoggerCommands() {}

    public static void register() {
        var metadata = FabricLoader.getInstance().getModContainer("chestlogger_csv")
                .orElseThrow().getMetadata();
        String info = metadata.getName() + " version " + metadata.getVersion().getFriendlyString();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("chestlogger")
                        .then(Commands.literal("info").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(info), false);
                            return 1;
                        }))));
    }
}
