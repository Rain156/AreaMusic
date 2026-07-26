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
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class AreaMusicCommands {
    private AreaMusicCommands() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Operations operations
    ) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(operations, "operations");
        dispatcher.register(Commands.literal("areamusic")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("create")
                        .then(Commands.argument("areaId", StringArgumentType.word())
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .then(Commands.argument("musicId", StringArgumentType.greedyString())
                                                        .suggests((context, builder) -> suggestMusicIds(
                                                                operations.musicIds(), builder))
                                                        .executes(context -> createArea(context, operations)))))))
                .then(Commands.literal("reload")
                        .executes(context -> operations.requestReload(context.getSource()))));
    }

    private static int createArea(
            CommandContext<CommandSourceStack> context,
            Operations operations
    ) {
        return operations.createArea(
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

    public interface Operations {
        Iterable<String> musicIds();

        int createArea(
                CommandSourceStack source,
                String areaId,
                BlockPos pos1,
                BlockPos pos2,
                String musicId
        );

        int requestReload(CommandSourceStack source);
    }
}
