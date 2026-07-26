package datura.areamusic.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLifecycleCoordinatorTest {
    @Test
    void reloadCompletionBelongsToTheExactLifecycleAndSender() {
        RecordingSender inactive = new RecordingSender("inactive");
        RecordingSender firstSender = new RecordingSender("first");
        RecordingSender sameServerRestartSender = new RecordingSender("same-restart");
        RecordingSender replacementSender = new RecordingSender("replacement");
        ServerLifecycleCoordinator<Object, RecordingSender, String, String> lifecycle =
                new ServerLifecycleCoordinator<>(inactive);
        Object firstServer = new Object();
        Object replacementServer = new Object();
        ServerLifecycleCoordinator.Lifecycle<Object> firstLifecycle =
                lifecycle.start(firstServer, firstSender);
        ServerLifecycleCoordinator.ReloadOperation<Object> stoppedReload =
                lifecycle.beginReload(firstLifecycle);
        assertNotNull(stoppedReload);

        assertFalse(lifecycle.stop(replacementServer));
        assertTrue(lifecycle.reloadInFlight());
        assertSame(firstSender, lifecycle.senderFor(firstLifecycle));
        assertTrue(lifecycle.stop(firstServer));

        ServerLifecycleCoordinator.Lifecycle<Object> sameServerLifecycle =
                lifecycle.start(firstServer, sameServerRestartSender);
        ServerLifecycleCoordinator.ReloadOperation<Object> sameServerReload =
                lifecycle.beginReload(sameServerLifecycle);
        AtomicInteger commits = new AtomicInteger();
        assertNull(lifecycle.finishReload(stoppedReload));
        assertTrue(lifecycle.reloadInFlight());
        assertEquals(List.of(), sameServerRestartSender.events);

        ServerLifecycleCoordinator.Lifecycle<Object> replacementLifecycle =
                lifecycle.start(replacementServer, replacementSender);
        ServerLifecycleCoordinator.ReloadOperation<Object> activeReload =
                lifecycle.beginReload(replacementLifecycle);
        assertNull(lifecycle.finishReload(sameServerReload));
        assertTrue(lifecycle.reloadInFlight());
        assertEquals(List.of(), replacementSender.events);

        ServerLifecycleCoordinator.Completion<Object, RecordingSender> completion =
                lifecycle.finishReload(activeReload);
        assertNotNull(completion);
        assertSame(replacementServer, completion.server());
        assertSame(replacementSender, completion.sender());
        commits.incrementAndGet();
        completion.sender().send("active");

        assertFalse(lifecycle.reloadInFlight());
        assertEquals(1, commits.get());
        assertEquals(List.of("replacement:active"), replacementSender.events);
        assertEquals(List.of(), firstSender.events);
        assertEquals(List.of(), sameServerRestartSender.events);
    }

    @Test
    void staleCreateCannotRemoveTheReplacementMarkerOrCommit() {
        RecordingSender inactive = new RecordingSender("inactive");
        RecordingSender firstSender = new RecordingSender("first");
        RecordingSender replacementSender = new RecordingSender("replacement");
        ServerLifecycleCoordinator<Object, RecordingSender, String, String> lifecycle =
                new ServerLifecycleCoordinator<>(inactive);
        Object firstServer = new Object();
        Object replacementServer = new Object();
        ServerLifecycleCoordinator.Lifecycle<Object> firstLifecycle =
                lifecycle.start(firstServer, firstSender);
        ServerLifecycleCoordinator.CreateOperation<Object> staleCreate =
                lifecycle.beginCreate(firstLifecycle, "shared-area");
        ServerLifecycleCoordinator.Lifecycle<Object> replacementLifecycle =
                lifecycle.start(replacementServer, replacementSender);
        ServerLifecycleCoordinator.CreateOperation<Object> activeCreate =
                lifecycle.beginCreate(replacementLifecycle, "shared-area");
        AtomicInteger areas = new AtomicInteger();
        AtomicInteger revision = new AtomicInteger();

        assertNull(lifecycle.finishCreate(staleCreate));
        assertTrue(lifecycle.createInFlight("shared-area"));
        assertEquals(0, areas.get());
        assertEquals(0, revision.get());
        assertEquals(List.of(), replacementSender.events);

        ServerLifecycleCoordinator.Completion<Object, RecordingSender> completion =
                lifecycle.finishCreate(activeCreate);
        assertNotNull(completion);
        assertSame(replacementServer, completion.server());
        assertSame(replacementSender, completion.sender());
        areas.incrementAndGet();
        revision.incrementAndGet();
        completion.sender().send("active");

        assertFalse(lifecycle.createInFlight("shared-area"));
        assertEquals(1, areas.get());
        assertEquals(1, revision.get());
        assertEquals(List.of("replacement:active"), replacementSender.events);
        assertEquals(List.of(), firstSender.events);
    }

    @Test
    void startResetsTrackersWorkMarkersAndSender() {
        RecordingSender inactive = new RecordingSender("inactive");
        RecordingSender firstSender = new RecordingSender("first");
        RecordingSender replacementSender = new RecordingSender("replacement");
        ServerLifecycleCoordinator<Object, RecordingSender, String, String> lifecycle =
                new ServerLifecycleCoordinator<>(inactive);
        Object firstServer = new Object();
        Object replacementServer = new Object();
        ServerLifecycleCoordinator.Lifecycle<Object> firstLifecycle =
                lifecycle.start(firstServer, firstSender);
        lifecycle.tracker(firstLifecycle, "player", () -> "first-tracker");
        assertNotNull(lifecycle.beginCreate(firstLifecycle, "area"));

        ServerLifecycleCoordinator.Lifecycle<Object> replacementLifecycle =
                lifecycle.start(replacementServer, replacementSender);

        assertSame(replacementServer, lifecycle.server());
        assertSame(replacementSender, lifecycle.senderFor(replacementLifecycle));
        assertSame(inactive, lifecycle.senderFor(firstLifecycle));
        assertEquals(0, lifecycle.trackerCount());
        assertFalse(lifecycle.createInFlight("area"));
        assertFalse(lifecycle.reloadInFlight());
    }

    @Test
    void stopResetsTrackersWorkMarkersAndSender() {
        RecordingSender inactive = new RecordingSender("inactive");
        RecordingSender activeSender = new RecordingSender("active");
        ServerLifecycleCoordinator<Object, RecordingSender, String, String> lifecycle =
                new ServerLifecycleCoordinator<>(inactive);
        Object server = new Object();
        ServerLifecycleCoordinator.Lifecycle<Object> reloadLifecycle =
                lifecycle.start(server, activeSender);
        lifecycle.tracker(reloadLifecycle, "player", () -> "tracker");
        assertNotNull(lifecycle.beginReload(reloadLifecycle));

        assertTrue(lifecycle.stop(server));

        assertNull(lifecycle.server());
        assertSame(inactive, lifecycle.senderFor(reloadLifecycle));
        assertEquals(0, lifecycle.trackerCount());
        assertFalse(lifecycle.reloadInFlight());

        ServerLifecycleCoordinator.Lifecycle<Object> createLifecycle =
                lifecycle.start(server, activeSender);
        lifecycle.tracker(createLifecycle, "player", () -> "tracker");
        assertNotNull(lifecycle.beginCreate(createLifecycle, "area"));
        assertTrue(lifecycle.stop(server));
        assertEquals(0, lifecycle.trackerCount());
        assertFalse(lifecycle.createInFlight("area"));
        assertSame(inactive, lifecycle.senderFor(createLifecycle));
    }

    @Test
    void wrongServerCannotRemoveTheActiveTracker() {
        RecordingSender inactive = new RecordingSender("inactive");
        RecordingSender activeSender = new RecordingSender("active");
        ServerLifecycleCoordinator<Object, RecordingSender, String, String> lifecycle =
                new ServerLifecycleCoordinator<>(inactive);
        Object activeServer = new Object();
        Object otherServer = new Object();
        ServerLifecycleCoordinator.Lifecycle<Object> activeLifecycle =
                lifecycle.start(activeServer, activeSender);
        ServerLifecycleCoordinator.Lifecycle<Object> otherLifecycle =
                new ServerLifecycleCoordinator.Lifecycle<>(otherServer, activeLifecycle.generation());
        String tracker = lifecycle.tracker(activeLifecycle, "player", () -> "tracker");

        assertFalse(lifecycle.removeTracker(otherLifecycle, "player"));
        assertSame(tracker, lifecycle.tracker(activeLifecycle, "player", () -> "replacement"));
        assertEquals(1, lifecycle.trackerCount());
        assertTrue(lifecycle.removeTracker(activeLifecycle, "player"));
        assertEquals(0, lifecycle.trackerCount());
    }

    @Test
    void capturedStateCannotBeginWorkAfterTheSameServerObjectRestarts() {
        RecordingSender inactive = new RecordingSender("inactive");
        RecordingSender firstSender = new RecordingSender("first");
        RecordingSender replacementSender = new RecordingSender("replacement");
        ServerLifecycleCoordinator<Object, RecordingSender, String, String> lifecycle =
                new ServerLifecycleCoordinator<>(inactive);
        Object server = new Object();
        ServerLifecycleCoordinator.Lifecycle<Object> captured =
                lifecycle.start(server, firstSender);
        ServerLifecycleCoordinator.Lifecycle<Object> replacement =
                lifecycle.start(server, replacementSender);

        assertNull(lifecycle.beginReload(captured));
        assertFalse(lifecycle.reloadInFlight());
        ServerLifecycleCoordinator.ReloadOperation<Object> activeReload =
                lifecycle.beginReload(replacement);
        assertNotNull(activeReload);
        assertNotNull(lifecycle.finishReload(activeReload));

        assertNull(lifecycle.beginCreate(captured, "shared-area"));
        assertFalse(lifecycle.createInFlight("shared-area"));
        ServerLifecycleCoordinator.CreateOperation<Object> activeCreate =
                lifecycle.beginCreate(replacement, "shared-area");
        assertNotNull(activeCreate);
        assertNotNull(lifecycle.finishCreate(activeCreate));
        assertFalse(lifecycle.createInFlight("shared-area"));
    }

    private static final class RecordingSender {
        private final String name;
        private final List<String> events = new ArrayList<>();

        private RecordingSender(String name) {
            this.name = name;
        }

        private void send(String event) {
            events.add(name + ':' + event);
        }
    }
}
