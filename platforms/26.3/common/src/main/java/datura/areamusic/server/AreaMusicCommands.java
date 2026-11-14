package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import datura.areamusic.area.AreaCondition;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.TrackTime;
import datura.areamusic.playback.PlaylistLoopPlayback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class AreaMusicCommands {
    private static final Pattern COLOR = Pattern.compile("#?[0-9a-fA-F]{6}");

    private AreaMusicCommands() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Operations operations
    ) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(operations, "operations");
        dispatcher.register(Commands.literal("areamusic")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("create")
                        .then(Commands.argument("areaId", StringArgumentType.word())
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .then(musicId(operations)
                                                        .executes(context -> createArea(context, operations)))))))
                .then(Commands.literal("delete")
                        .then(areaId(operations).executes(context ->
                                operations.deleteArea(context.getSource(), areaId(context)))))
                .then(Commands.literal("list")
                        .executes(context -> operations.listAreas(context.getSource())))
                .then(Commands.literal("info")
                        .then(areaId(operations).executes(context ->
                                operations.showArea(context.getSource(), areaId(context)))))
                .then(Commands.literal("tp")
                        .then(areaId(operations).executes(context ->
                                operations.teleportToArea(context.getSource(), areaId(context)))))
                .then(Commands.literal("set").then(setProperties(areaId(operations), operations)))
                .then(Commands.literal("music")
                        .then(Commands.literal("add").then(areaId(operations)
                                .then(musicId(operations).executes(context -> edit(
                                        context, operations,
                                        area -> AreaEdits.addMusic(area, musicId(context)))))))
                        .then(Commands.literal("remove").then(areaId(operations)
                                .then(musicId(operations).executes(context -> edit(
                                        context, operations,
                                        area -> AreaEdits.removeMusic(area, musicId(context))))))))
                .then(Commands.literal("box")
                        .then(Commands.literal("add").then(areaId(operations)
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .executes(context -> edit(context, operations, area ->
                                                        AreaEdits.addBox(
                                                                area,
                                                                position(context, "pos1"),
                                                                position(context, "pos2"))))))))
                        .then(Commands.literal("remove").then(areaId(operations)
                                .then(Commands.argument("number", IntegerArgumentType.integer(
                                                1, AreaDefinition.MAX_EXTRA_BOXES + 1))
                                        .executes(context -> edit(context, operations, area ->
                                                AreaEdits.removeBox(
                                                        area,
                                                        IntegerArgumentType.getInteger(context, "number"))))))))
                .then(Commands.literal("reload")
                        .executes(context -> operations.requestReload(context.getSource())))
                .then(Commands.literal("help").executes(AreaMusicCommands::showHelp))
                .executes(AreaMusicCommands::showHelp));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> setProperties(
            RequiredArgumentBuilder<CommandSourceStack, String> areaId,
            Operations operations
    ) {
        areaId.then(Commands.literal("name")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> edit(context, operations, area ->
                                area.withDisplayName(checkedName(StringArgumentType.getString(context, "name")))))));
        areaId.then(Commands.literal("volume")
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                        .executes(context -> edit(context, operations, area -> AreaEdits.withVolume(
                                area, IntegerArgumentType.getInteger(context, "percent") / 100.0f)))));
        areaId.then(Commands.literal("start")
                .then(Commands.argument("time", StringArgumentType.greedyString())
                        .suggests((context, builder) ->
                                SharedSuggestionProvider.suggest(new String[]{"0:00", "0:30", "1:00"}, builder))
                        .executes(context -> edit(context, operations, area ->
                                area.withStartOffsetMs(parseStart(StringArgumentType.getString(context, "time")))))));
        areaId.then(Commands.literal("shuffle")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> edit(context, operations, area ->
                                AreaEdits.withShuffle(area, BoolArgumentType.getBool(context, "value"))))));
        areaId.then(Commands.literal("gap")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(
                                0, PlaylistLoopPlayback.MAX_GAP_MS / 1000))
                        .executes(context -> edit(context, operations, area -> AreaEdits.withGap(
                                area, IntegerArgumentType.getInteger(context, "seconds") * 1000)))));
        areaId.then(Commands.literal("announce")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> edit(context, operations, area ->
                                area.withAnnounce(BoolArgumentType.getBool(context, "value"))))));
        areaId.then(Commands.literal("wholedimension")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> edit(context, operations, area ->
                                area.withWholeDimension(BoolArgumentType.getBool(context, "value"))))));
        LiteralArgumentBuilder<CommandSourceStack> time = Commands.literal("time");
        for (AreaCondition.DayPart dayPart : AreaCondition.DayPart.values()) {
            time.then(Commands.literal(dayPart.id())
                    .executes(context -> edit(context, operations,
                            area -> area.withCondition(area.condition().withDayPart(dayPart)))));
        }
        areaId.then(time);
        LiteralArgumentBuilder<CommandSourceStack> weather = Commands.literal("weather");
        for (AreaCondition.Weather kind : AreaCondition.Weather.values()) {
            weather.then(Commands.literal(kind.id())
                    .executes(context -> edit(context, operations,
                            area -> area.withCondition(area.condition().withWeather(kind)))));
        }
        areaId.then(weather);
        areaId.then(Commands.literal("priority")
                .then(Commands.argument("priority", IntegerArgumentType.integer())
                        .executes(context -> edit(context, operations, area ->
                                area.withPriority(IntegerArgumentType.getInteger(context, "priority"))))));
        areaId.then(Commands.literal("resume")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> edit(context, operations, area ->
                                area.withResumeOnReenter(BoolArgumentType.getBool(context, "value"))))));
        areaId.then(Commands.literal("enabled")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> edit(context, operations, area ->
                                area.withEnabled(BoolArgumentType.getBool(context, "value"))))));
        areaId.then(Commands.literal("fadein").then(fadeArgument()
                .executes(context -> edit(context, operations, area ->
                        AreaEdits.withFadeIn(area, IntegerArgumentType.getInteger(context, "ms"))))));
        areaId.then(Commands.literal("fadeout").then(fadeArgument()
                .executes(context -> edit(context, operations, area ->
                        AreaEdits.withFadeOut(area, IntegerArgumentType.getInteger(context, "ms"))))));
        areaId.then(Commands.literal("color")
                .then(Commands.argument("color", StringArgumentType.greedyString())
                        .suggests((context, builder) ->
                                SharedSuggestionProvider.suggest(new String[]{"auto", "#"}, builder))
                        .executes(context -> edit(context, operations, area ->
                                area.withColor(parseColor(StringArgumentType.getString(context, "color")))))));
        LiteralArgumentBuilder<CommandSourceStack> mode = Commands.literal("mode");
        for (AreaEdits.SimpleMode simpleMode : AreaEdits.SimpleMode.values()) {
            mode.then(Commands.literal(simpleMode.id())
                    .executes(context -> edit(context, operations,
                            area -> AreaEdits.withMode(area, simpleMode))));
        }
        areaId.then(mode);
        areaId.then(corner("pos1", operations, (area, corner) -> area.withBounds(corner, area.pos2())));
        areaId.then(corner("pos2", operations, (area, corner) -> area.withBounds(area.pos1(), corner)));
        return areaId;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> corner(
            String name,
            Operations operations,
            BiFunction<AreaDefinition, AreaPosition, AreaDefinition> change
    ) {
        return Commands.literal(name)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> {
                            BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
                            AreaPosition corner = new AreaPosition(pos.getX(), pos.getY(), pos.getZ());
                            return edit(context, operations, area -> change.apply(area, corner));
                        }));
    }

    private static AreaPosition position(CommandContext<CommandSourceStack> context, String name) {
        BlockPos pos = BlockPosArgument.getBlockPos(context, name);
        return new AreaPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> fadeArgument() {
        return Commands.argument("ms", IntegerArgumentType.integer(0, AreaTrackDefinition.MAX_FADE_MS));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> areaId(Operations operations) {
        return Commands.argument("areaId", StringArgumentType.word())
                .suggests((context, builder) ->
                        SharedSuggestionProvider.suggest(operations.areaIds(), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> musicId(Operations operations) {
        return Commands.argument("musicId", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestMusicIds(operations.musicIds(), builder));
    }

    private static String areaId(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "areaId");
    }

    private static String musicId(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "musicId");
    }

    private static int edit(
            CommandContext<CommandSourceStack> context,
            Operations operations,
            UnaryOperator<AreaDefinition> change
    ) {
        return operations.editArea(context.getSource(), areaId(context), change);
    }

    static int parseColor(String text) {
        String trimmed = text.strip();
        if (trimmed.toLowerCase(Locale.ROOT).equals("auto")) {
            return AreaDefinition.AUTO_COLOR;
        }
        if (!COLOR.matcher(trimmed).matches()) {
            throw new InvalidValue("color");
        }
        return Integer.parseInt(trimmed.substring(trimmed.length() - 6), 16);
    }

    private static String checkedName(String name) {
        String trimmed = name.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > AreaDefinition.MAX_DISPLAY_NAME_LENGTH) {
            throw new InvalidValue("name");
        }
        return trimmed;
    }

    /**
     * A value the command cannot use, with a reason that has a translation: the suffix of a
     * {@code commands.areamusic.invalid.*} key. Other failures only have an English message.
     */
    static final class InvalidValue extends IllegalArgumentException {
        private final String reason;

        InvalidValue(String reason) {
            super("Invalid " + reason);
            this.reason = reason;
        }

        String translationKey() {
            return "commands.areamusic.invalid." + reason;
        }
    }

    /** A position in the music such as {@code 1:30} or {@code 90}, in milliseconds. */
    static int parseStart(String text) {
        return TrackTime.parseMillis(text).orElseThrow(() -> new InvalidValue("start"));
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        for (Component line : CommandReplies.help()) {
            context.getSource().sendSuccess(() -> line, false);
        }
        return 1;
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

        Iterable<String> areaIds();

        int createArea(
                CommandSourceStack source,
                String areaId,
                BlockPos pos1,
                BlockPos pos2,
                String musicId
        );

        int deleteArea(CommandSourceStack source, String areaId);

        int listAreas(CommandSourceStack source);

        int showArea(CommandSourceStack source, String areaId);

        int teleportToArea(CommandSourceStack source, String areaId);

        /**
         * Applies {@code change} to the named area. The change may throw
         * {@link IllegalArgumentException} with a message for the user.
         */
        int editArea(CommandSourceStack source, String areaId, UnaryOperator<AreaDefinition> change);

        int requestReload(CommandSourceStack source);
    }
}
