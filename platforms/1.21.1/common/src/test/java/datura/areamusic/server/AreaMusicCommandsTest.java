package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
