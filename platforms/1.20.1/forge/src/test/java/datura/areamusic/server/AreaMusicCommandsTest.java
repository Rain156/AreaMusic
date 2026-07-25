package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicCommandsTest {
    @Test
    void registersGreedyMusicIdArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        AreaMusicCommands.register(dispatcher);

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
    void suggestsOriginalUnquotedMusicIds() {
        Set<String> expectedSuggestions = Set.of(
                "音乐 名称.mp3",
                "文件夹名称/音乐 名称.ogg"
        );

        Set<String> actualSuggestions = AreaMusicCommands.suggestMusicIds(
                        List.copyOf(expectedSuggestions),
                        new SuggestionsBuilder("", 0)
                ).join().getList().stream()
                .map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());

        assertEquals(expectedSuggestions, actualSuggestions);
        assertTrue(actualSuggestions.stream().noneMatch(suggestion -> suggestion.startsWith("\"")));
    }
}
