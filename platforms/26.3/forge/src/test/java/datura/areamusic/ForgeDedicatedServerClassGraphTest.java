package datura.areamusic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeDedicatedServerClassGraphTest {
    private static final String MOD_ENTRYPOINT = "datura/areamusic/AreaMusic";
    private static final String SERVER_SUBSCRIBER =
            "datura/areamusic/server/ForgeAreaMusicServer";

    @Test
    void dedicatedServerEntrypointAndSubscriberGraphsHaveNoClientOnlyReferences()
            throws IOException {
        ProductionRootsResolver productionClasses =
                ProductionRootsResolver.fromSystemProperty();
        Set<String> visited = new LinkedHashSet<>();
        visited.addAll(DedicatedServerClassGraph.verify(
                MOD_ENTRYPOINT,
                productionClasses
        ));
        visited.addAll(DedicatedServerClassGraph.verify(
                SERVER_SUBSCRIBER,
                productionClasses
        ));

        assertTrue(visited.contains("datura/areamusic/network/AreaMusicNetwork"));
        assertTrue(visited.contains("datura/areamusic/server/AreaMusicServer"));
        assertTrue(visited.contains("datura/areamusic/server/PlayerAreaTracker"));
        assertTrue(visited.contains("datura/areamusic/server/AreaMusicServer$ReloadCandidate"));
        assertFalse(visited.stream().anyMatch(name -> name.startsWith(
                "datura/areamusic/client/"
        )));
    }

    @Test
    void reachableProjectHelperCannotHideAClientOnlyLinkFromTheRootScan()
            throws IOException {
        String root = "datura/areamusic/mutation/Root";
        String helper = "datura/areamusic/mutation/ReachableHelper";
        String clientOnly = "net/minecraft/client/Minecraft";
        byte[] rootBytes = SyntheticClassGraphFixture.linkingClass(root, helper);
        Map<String, byte[]> classes = Map.of(
                root, rootBytes,
                helper, SyntheticClassGraphFixture.classWithFieldType(helper, clientOnly)
        );

        assertFalse(
                new String(rootBytes, StandardCharsets.ISO_8859_1).contains(clientOnly),
                "the root must not contain a direct client-only reference"
        );
        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> DedicatedServerClassGraph.verify(root, classes::get)
        );
        assertTrue(failure.getMessage().contains(
                root + " -> " + helper + " -> " + clientOnly
        ));
    }

    @Test
    void safeCyclicProjectGraphIsVisitedOnceWithoutLooping() throws IOException {
        String root = "datura/areamusic/mutation/CyclicRoot";
        String helper = "datura/areamusic/mutation/CyclicHelper";
        Map<String, byte[]> classes = Map.of(
                root, SyntheticClassGraphFixture.linkingClass(root, helper),
                helper, SyntheticClassGraphFixture.linkingClass(helper, root)
        );

        assertEquals(
                List.of(root, helper),
                DedicatedServerClassGraph.verify(root, classes::get)
        );
    }

    @Test
    void clientLookingStringConstantIsNotTreatedAsAClassLink() throws IOException {
        String root = "datura/areamusic/mutation/StringRoot";
        byte[] rootBytes = SyntheticClassGraphFixture.classWithStringConstant(
                root,
                "net/minecraft/client/ThisIsOnlyText"
        );

        assertEquals(
                List.of(root),
                DedicatedServerClassGraph.verify(root, ignored -> rootBytes)
        );
    }
}
