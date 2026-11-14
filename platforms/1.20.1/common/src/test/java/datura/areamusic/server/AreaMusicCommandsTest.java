package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaylistLoopPlayback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicCommandsTest {
    @Test
    void registersGreedyMusicIdArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        AreaMusicCommands.register(dispatcher, new RecordingOperations());

        CommandNode<CommandSourceStack> musicIdNode = dispatcher.getRoot()
                .getChild("areamusic")
                .getChild("create")
                .getChild("areaId")
                .getChild("pos1")
                .getChild("pos2")
                .getChild("musicId");
        assertInstanceOf(ArgumentCommandNode.class, musicIdNode);
        ArgumentCommandNode<?, ?> argumentNode = (ArgumentCommandNode<?, ?>) musicIdNode;
        StringArgumentType argumentType = assertInstanceOf(StringArgumentType.class, argumentNode.getType());

        assertEquals(StringArgumentType.StringType.GREEDY_PHRASE, argumentType.getType());
    }

    @Test
    void delegatesCreateArgumentsAndReturnValueToInjectedOperations() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RecordingOperations operations = new RecordingOperations();
        operations.createResult = 23;
        AreaMusicCommands.register(dispatcher, operations);
        CommandSourceStack source = commandSource();

        int result = dispatcher.execute(
                "areamusic create plaza 1 2 3 4 5 6 folder/music track.ogg",
                source
        );

        assertEquals(23, result);
        assertSame(source, operations.createSource);
        assertEquals("plaza", operations.areaId);
        assertEquals(new BlockPos(1, 2, 3), operations.pos1);
        assertEquals(new BlockPos(4, 5, 6), operations.pos2);
        assertEquals("folder/music track.ogg", operations.musicId);
    }

    @Test
    void delegatesReloadSourceAndReturnValueToInjectedOperations() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RecordingOperations operations = new RecordingOperations();
        operations.reloadResult = 17;
        AreaMusicCommands.register(dispatcher, operations);
        CommandSourceStack source = commandSource();

        int result = dispatcher.execute("areamusic reload", source);

        assertEquals(17, result);
        assertSame(source, operations.reloadSource);
    }

    @Test
    void suggestsOriginalUnquotedMusicIds() {
        Set<String> expectedSuggestions = Set.of(
                "音乐 名称.mp3",
                "文件夹名称/音乐 名称.ogg"
        );
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RecordingOperations operations = new RecordingOperations();
        operations.suggestedMusicIds = List.copyOf(expectedSuggestions);
        AreaMusicCommands.register(dispatcher, operations);

        Set<String> actualSuggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(
                        "areamusic create plaza 1 2 3 4 5 6 ",
                        commandSource()
                )).join().getList().stream()
                .map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());

        assertEquals(expectedSuggestions, actualSuggestions);
        assertEquals(1, operations.musicIdRequests);
        assertTrue(actualSuggestions.stream().noneMatch(suggestion -> suggestion.startsWith("\"")));
    }

    @Test
    void delegatesDeleteListInfoAndTeleport() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RecordingOperations operations = new RecordingOperations();
        AreaMusicCommands.register(dispatcher, operations);
        CommandSourceStack source = commandSource();

        assertEquals(31, dispatcher.execute("areamusic delete plaza", source));
        assertEquals(32, dispatcher.execute("areamusic list", source));
        assertEquals(33, dispatcher.execute("areamusic info plaza", source));
        assertEquals(34, dispatcher.execute("areamusic tp plaza", source));

        assertEquals(List.of("delete plaza", "list", "info plaza", "tp plaza"), operations.calls);
    }

    @Test
    void setCommandsProduceTheMatchingAreaChange() throws Exception {
        AreaDefinition base = sampleArea();

        assertEquals("村庄 广场", change("set plaza name 村庄 广场").apply(base).displayName());
        assertEquals(0.4f, AreaEdits.volumeOf(change("set plaza volume 40").apply(base)));
        assertEquals(-3, change("set plaza priority -3").apply(base).priority());
        AreaDefinition playlist = AreaEdits.addMusic(base, "second.ogg");
        assertTrue(AreaEdits.shuffleOf(change("set plaza shuffle true").apply(playlist)));
        assertEquals(4_000, AreaEdits.gapOf(change("set plaza gap 4").apply(playlist)));
        assertEquals(datura.areamusic.area.AreaCondition.DayPart.NIGHT,
                change("set plaza time night").apply(base).condition().dayPart());
        assertEquals(datura.areamusic.area.AreaCondition.Weather.THUNDER,
                change("set plaza weather thunder").apply(base).condition().weather());
        assertFalse(change("set plaza announce false").apply(base).announce());
        assertTrue(change("set plaza wholedimension true").apply(base).wholeDimension());
        AreaDefinition twoBoxes = change("box add plaza 20 64 0 30 70 4").apply(base);
        assertEquals(new AreaPosition(30, 70, 4), twoBoxes.extraBoxes().get(0).pos2());
        assertEquals(base, change("box remove plaza 2").apply(twoBoxes));
        assertEquals(90_000, change("set plaza start 1:30").apply(base).startOffsetMs());
        assertEquals(12_500, change("set plaza start 12.5").apply(base).startOffsetMs());
        assertEquals(0, change("set plaza start 0").apply(base.withStartOffsetMs(5_000)).startOffsetMs());
        assertTrue(change("set plaza resume true").apply(base).resumeOnReenter());
        assertFalse(change("set plaza enabled false").apply(base).enabled());
        assertEquals(150, AreaEdits.fadeInOf(change("set plaza fadein 150").apply(base)));
        assertEquals(60000, AreaEdits.fadeOutOf(change("set plaza fadeout 60000").apply(base)));
        assertEquals(0x1D9E75, change("set plaza color #1d9e75").apply(base).color());
        assertEquals(0xABCDEF, change("set plaza color ABCDEF").apply(base).color());
        assertEquals(AreaDefinition.AUTO_COLOR,
                change("set plaza color auto").apply(base.withColor(5)).color());
        assertEquals(AreaEdits.SimpleMode.PLAYLIST,
                AreaEdits.modeOf(change("set plaza mode playlist").apply(base)));
        assertEquals(new AreaPosition(7, 8, 9), change("set plaza pos1 7 8 9").apply(base).pos1());
        assertEquals(base.pos2(), change("set plaza pos1 7 8 9").apply(base).pos2());
        assertEquals(new AreaPosition(-1, -2, -3), change("set plaza pos2 -1 -2 -3").apply(base).pos2());
        assertEquals(base.pos1(), change("set plaza pos2 -1 -2 -3").apply(base).pos1());
    }

    @Test
    void musicCommandsAddAndRemoveGreedyMusicIds() throws Exception {
        AreaDefinition base = sampleArea();

        AreaDefinition added = change("music add plaza folder/second track.ogg").apply(base);
        assertEquals(List.of("first.ogg", "folder/second track.ogg"), added.musicIds());
        assertInstanceOf(PlaylistLoopPlayback.class, added.playback());
        assertEquals(List.of("first.ogg"),
                change("music remove plaza folder/second track.ogg").apply(added).musicIds());
    }

    @Test
    void invalidValuesSurfaceAsIllegalArgumentFromTheChange() throws Exception {
        AreaDefinition base = sampleArea();

        assertThrows(IllegalArgumentException.class,
                () -> change("set plaza color purple").apply(base));
        assertThrows(IllegalArgumentException.class,
                () -> change("set plaza shuffle true").apply(base), "only a playlist can be shuffled");
        assertThrows(IllegalArgumentException.class,
                () -> change("box remove plaza 1").apply(base), "the only box cannot go");
        assertThrows(IllegalArgumentException.class,
                () -> change("set plaza start soon").apply(base));
        assertThrows(IllegalArgumentException.class,
                () -> change("set plaza start 7:00:00").apply(base));
        assertThrows(IllegalArgumentException.class,
                () -> change("set plaza name " + "x".repeat(65)).apply(base));
        assertThrows(IllegalArgumentException.class,
                () -> change("music remove plaza first.ogg").apply(base));
    }

    @Test
    void outOfRangeNumbersAreRejectedByTheParser() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        AreaMusicCommands.register(dispatcher, new RecordingOperations());

        for (String command : List.of(
                "areamusic set plaza volume 101",
                "areamusic set plaza volume -1",
                "areamusic set plaza fadein 60001",
                "areamusic set plaza mode shuffle",
                "areamusic set plaza"
        )) {
            assertThrows(CommandSyntaxException.class,
                    () -> dispatcher.execute(command, commandSource()), command);
        }
    }

    @Test
    void suggestsExistingAreaIds() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        AreaMusicCommands.register(dispatcher, new RecordingOperations());

        for (String prefix : List.of(
                "areamusic delete ", "areamusic info ", "areamusic tp ",
                "areamusic set ", "areamusic music add ", "areamusic music remove "
        )) {
            Set<String> suggestions = dispatcher.getCompletionSuggestions(
                            dispatcher.parse(prefix, commandSource())
                    ).join().getList().stream()
                    .map(suggestion -> suggestion.getText())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("plaza", "cave"), suggestions, prefix);
        }
    }

    @Test
    void helpIsShownForTheBareCommandAndListsEverySettingThatSetAccepts() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        AreaMusicCommands.register(dispatcher, new RecordingOperations());
        Set<String> settings = dispatcher.getRoot().getChild("areamusic").getChild("set")
                .getChild("areaId").getChildren().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toSet());

        String help = text(CommandReplies.help());

        assertTrue(settings.contains("start") && settings.size() >= 12, settings.toString());
        for (String setting : settings) {
            assertTrue(help.contains(setting), setting + " is missing from the help");
        }
        for (String subcommand : List.of("list", "info", "create", "set", "music", "tp", "delete", "reload")) {
            assertTrue(help.contains("/areamusic " + subcommand), subcommand);
        }
        assertEquals(1, dispatcher.execute("areamusic", commandSource()));
        assertEquals(1, dispatcher.execute("areamusic help", commandSource()));
    }

    @Test
    void anEditReplyNamesExactlyWhatChanged() {
        AreaDefinition before = sampleArea();
        AreaDefinition after = AreaEdits.withVolume(
                before.withDisplayName("广场").withStartOffsetMs(90_000), 0.4f);

        String reply = text(List.of(CommandReplies.updated(before, after)));

        assertTrue(reply.contains("commands.areamusic.edit.success"), reply);
        for (String property : List.of("name", "volume", "start")) {
            assertTrue(reply.contains("commands.areamusic.property." + property + "}"), reply);
        }
        for (String untouched : List.of("priority", "mode", "music", "bounds", "color", "enabled")) {
            assertFalse(reply.contains("commands.areamusic.property." + untouched + "}"), reply);
        }
        assertTrue(reply.contains("广场") && reply.contains("40%") && reply.contains("1:30"), reply);
        assertTrue(text(List.of(CommandReplies.updated(before, before)))
                .contains("commands.areamusic.edit.unchanged"));
    }

    @Test
    void listAndInfoShowWhatMattersAboutAnArea() {
        AreaDefinition plain = sampleArea();
        AreaDefinition tuned = plain.withStartOffsetMs(5_500).withEnabled(false);
        MusicLibrary nothing = MusicLibrary.empty(Path.of("music"));

        String plainInfo = text(CommandReplies.info(plain, nothing));
        String tunedInfo = text(CommandReplies.info(tuned, nothing));
        String list = text(CommandReplies.list(List.of(plain, tuned), nothing));

        assertFalse(plainInfo.contains("commands.areamusic.info.start"), plainInfo);
        assertTrue(tunedInfo.contains("{commands.areamusic.info.start 0:05.5}"), tunedInfo);
        assertTrue(tunedInfo.contains("commands.areamusic.list.disabled"), tunedInfo);
        assertTrue(plainInfo.contains("first.ogg") && plainInfo.contains("0 64 0 \u2192 10 80 10"), plainInfo);
        assertTrue(plainInfo.contains("11\u00d717\u00d711"), plainInfo);
        assertTrue(plainInfo.contains("commands.areamusic.info.music_missing"), plainInfo);
        assertTrue(list.contains("{commands.areamusic.list.header 2}"), list);
        assertTrue(list.contains("commands.areamusic.list.missing_music"), list);
        assertEquals(1, list.split("commands.areamusic.list.disabled", -1).length - 1, list);
    }

    /** Flattens replies to text: literals as they are, translations as {@code {key args}}. */
    private static String text(List<Component> lines) {
        StringBuilder out = new StringBuilder();
        for (Component line : lines) {
            append(out, line);
            out.append('\n');
        }
        return out.toString();
    }

    private static void append(StringBuilder out, Component component) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            out.append('{').append(translatable.getKey());
            for (Object argument : translatable.getArgs()) {
                out.append(' ');
                if (argument instanceof Component nested) {
                    append(out, nested);
                } else {
                    out.append(argument);
                }
            }
            out.append('}');
        } else {
            component.getContents().visit(literal -> {
                out.append(literal);
                return java.util.Optional.empty();
            });
        }
        for (Component sibling : component.getSiblings()) {
            append(out, sibling);
        }
    }

    private static UnaryOperator<AreaDefinition> change(String command) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RecordingOperations operations = new RecordingOperations();
        AreaMusicCommands.register(dispatcher, operations);

        assertEquals(35, dispatcher.execute("areamusic " + command, commandSource()));
        assertEquals("plaza", operations.editAreaId);
        return operations.change;
    }

    private static AreaDefinition sampleArea() {
        return AreaDefinition.create(
                "plaza", "minecraft:overworld",
                new AreaPosition(0, 64, 0), new AreaPosition(10, 80, 10),
                List.of(new AreaTrackDefinition("first.ogg", 0, 1.0f, true, 2000, 2000)),
                false, 0);
    }

    private static CommandSourceStack commandSource() {
        return new CommandSourceStack(
                CommandSource.NULL,
                Vec3.ZERO,
                Vec2.ZERO,
                null,
                4,
                "test",
                Component.literal("test"),
                null,
                null
        );
    }

    private static final class RecordingOperations implements AreaMusicCommands.Operations {
        private int createResult;
        private int reloadResult;
        private CommandSourceStack createSource;
        private CommandSourceStack reloadSource;
        private String areaId;
        private BlockPos pos1;
        private BlockPos pos2;
        private String musicId;
        private List<String> suggestedMusicIds = List.of("track.ogg");
        private int musicIdRequests;
        private final List<String> calls = new java.util.ArrayList<>();
        private String editAreaId;
        private UnaryOperator<AreaDefinition> change;

        @Override
        public Iterable<String> areaIds() {
            return List.of("plaza", "cave");
        }

        @Override
        public int deleteArea(CommandSourceStack source, String areaId) {
            calls.add("delete " + areaId);
            return 31;
        }

        @Override
        public int listAreas(CommandSourceStack source) {
            calls.add("list");
            return 32;
        }

        @Override
        public int showArea(CommandSourceStack source, String areaId) {
            calls.add("info " + areaId);
            return 33;
        }

        @Override
        public int teleportToArea(CommandSourceStack source, String areaId) {
            calls.add("tp " + areaId);
            return 34;
        }

        @Override
        public int editArea(
                CommandSourceStack source, String areaId, UnaryOperator<AreaDefinition> change
        ) {
            editAreaId = areaId;
            this.change = change;
            return 35;
        }

        @Override
        public Iterable<String> musicIds() {
            musicIdRequests++;
            return suggestedMusicIds;
        }

        @Override
        public int createArea(
                CommandSourceStack source,
                String areaId,
                BlockPos pos1,
                BlockPos pos2,
                String musicId
        ) {
            createSource = source;
            this.areaId = areaId;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.musicId = musicId;
            return createResult;
        }

        @Override
        public int requestReload(CommandSourceStack source) {
            reloadSource = source;
            return reloadResult;
        }
    }
}
