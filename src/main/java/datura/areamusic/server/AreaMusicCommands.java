package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;

import java.util.concurrent.CompletableFuture;

public final class AreaMusicCommands {
    private AreaMusicCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("areamusic")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("create")
                        .then(Commands.argument("areaId", StringArgumentType.word())
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .then(Commands.argument("musicId", StringArgumentType.greedyString())
                                                        .suggests((context, builder) -> suggestMusicIds(
                                                                AreaMusicServer.musicIds(), builder))
                                                        .executes(AreaMusicCommands::createArea))))))
                .then(Commands.literal("reload")
                        .executes(context -> AreaMusicServer.requestReload(context.getSource()))));
    }

    private static int createArea(CommandContext<CommandSourceStack> context) {
        return AreaMusicServer.createArea(
                context.getSource(),
                StringArgumentType.getString(context, "areaId"),
                BlockPosArgument.getBlockPos(context, "pos1"),
                BlockPosArgument.getBlockPos(context, "pos2"),
                StringArgumentType.getString(context, "musicId")
        );
    }

    static CompletableFuture<Suggestions> suggestMusicIds(
            Iterable<String> musicIds,
            SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(musicIds, builder);
    }
}
