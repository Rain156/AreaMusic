package datura.areamusic.edit;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaEditServiceTest {
    private static final Set<String> LIBRARY = Set.of("day.ogg", "night.ogg");

    @Test
    void putNewAreaIsAppliedWithRemoveAsInverse() {
        AreaEditService service = service(List.of());

        EditResult result = service.apply(new EditOp.Put(area("village", "day.ogg")));

        assertTrue(result.applied());
        assertTrue(result.changed());
        assertEquals(new EditOp.Remove("village"), result.inverse());
        assertNull(result.reason());
        assertEquals(8L, result.revision());
        assertEquals(8L, service.revision());
        assertEquals(List.of(area("village", "day.ogg")), service.areas());
    }

    @Test
    void putExistingAreaReturnsPreviousValueAsInverse() {
        AreaDefinition before = area("village", "day.ogg");
        AreaEditService service = service(List.of(before));

        EditResult result = service.apply(new EditOp.Put(before.withPriority(3)));

        assertTrue(result.changed());
        assertEquals(new EditOp.Put(before), result.inverse());
        assertEquals(3, service.find("village").orElseThrow().priority());
        assertEquals(8L, service.revision());
    }

    @Test
    void putIdenticalAreaIsAppliedButUnchanged() {
        AreaDefinition area = area("village", "day.ogg");
        AreaEditService service = service(List.of(area));
        List<AreaDefinition> snapshotBefore = service.areas();

        EditResult result = service.apply(new EditOp.Put(area("village", "day.ogg")));

        assertTrue(result.applied());
        assertFalse(result.changed());
        assertEquals(7L, result.revision());
        assertEquals(7L, service.revision());
        assertEquals(snapshotBefore, service.areas());
    }

    @Test
    void putWithUnknownMusicIsRejectedAndLeavesStateUntouched() {
        AreaDefinition existing = area("village", "day.ogg");
        AreaEditService service = service(List.of(existing));

        EditResult created = service.apply(new EditOp.Put(area("cave", "missing.ogg")));
        EditResult replaced = service.apply(new EditOp.Put(area("village", "missing.ogg")));

        for (EditResult result : List.of(created, replaced)) {
            assertFalse(result.applied());
            assertFalse(result.changed());
            assertNull(result.inverse());
            assertEquals(RejectReason.UNKNOWN_MUSIC, result.reason());
            assertEquals("missing.ogg", result.detail());
            assertEquals(7L, result.revision());
        }
        assertEquals(List.of(existing), service.areas());
        assertEquals(7L, service.revision());
    }

    @Test
    void removeUnknownAreaIsRejected() {
        AreaEditService service = service(List.of(area("village", "day.ogg")));

        EditResult result = service.apply(new EditOp.Remove("cave"));

        assertFalse(result.applied());
        assertEquals(RejectReason.UNKNOWN_AREA, result.reason());
        assertEquals("cave", result.detail());
        assertEquals(7L, service.revision());
        assertEquals(1, service.areas().size());
    }

    @Test
    void removeReturnsPutAsInverseAndApplyingInverseRestoresArea() {
        AreaDefinition village = area("village", "day.ogg").withDisplayName("村庄");
        AreaEditService service = service(List.of(village));

        EditResult removed = service.apply(new EditOp.Remove("village"));
        assertEquals(new EditOp.Put(village), removed.inverse());
        assertEquals(List.of(), service.areas());
        assertFalse(service.contains("village"));

        EditResult restored = service.apply(removed.inverse());
        assertEquals(new EditOp.Remove("village"), restored.inverse());
        assertEquals(List.of(village), service.areas());
        assertEquals(9L, service.revision());
    }

    @Test
    void inverseOfRemoveRestoresAreaEvenIfItsMusicWasDeletedMeanwhile() {
        AreaDefinition orphan = area("orphan", "gone.ogg");
        AreaEditService service = service(List.of(orphan));

        EditResult removed = service.apply(new EditOp.Remove("orphan"));
        EditResult restored = service.apply(removed.inverse());

        assertFalse(restored.applied());
        assertEquals(RejectReason.UNKNOWN_MUSIC, restored.reason());
        assertEquals("gone.ogg", restored.detail());
    }

    @Test
    void putBeyondLimitIsRejectedButReplacingStillWorks() {
        List<AreaDefinition> full = new ArrayList<>();
        for (int index = 0; index < AreaEditService.MAX_AREAS; index++) {
            full.add(area("a" + index, "day.ogg"));
        }
        AreaEditService service = service(full);

        EditResult overflow = service.apply(new EditOp.Put(area("one-more", "day.ogg")));
        EditResult replace = service.apply(new EditOp.Put(area("a0", "night.ogg")));

        assertFalse(overflow.applied());
        assertEquals(RejectReason.TOO_MANY_AREAS, overflow.reason());
        assertTrue(replace.changed());
        assertEquals(AreaEditService.MAX_AREAS, service.areas().size());
    }

    @Test
    void areasSnapshotIsSortedAndImmutable() {
        AreaEditService service = service(List.of(area("zeta", "day.ogg"), area("alpha", "day.ogg")));
        List<AreaDefinition> before = service.areas();

        service.apply(new EditOp.Put(area("mid", "day.ogg")));

        assertEquals(List.of("alpha", "zeta"), before.stream().map(AreaDefinition::id).toList());
        assertEquals(
                List.of("alpha", "mid", "zeta"),
                service.areas().stream().map(AreaDefinition::id).toList()
        );
        assertThrows(UnsupportedOperationException.class, () -> service.areas().clear());
    }

    @Test
    void replaceAllBumpsRevisionAndSkipsMusicValidation() {
        AreaEditService service = service(List.of(area("village", "day.ogg")));

        service.replaceAll(List.of(area("orphan", "gone.ogg")));

        assertEquals(8L, service.revision());
        assertEquals(List.of("orphan"), service.areas().stream().map(AreaDefinition::id).toList());
    }

    @Test
    void rejectsNegativeInitialRevision() {
        assertThrows(IllegalArgumentException.class,
                () -> new AreaEditService(List.of(), -1L, LIBRARY::contains));
    }

    private static AreaEditService service(List<AreaDefinition> initial) {
        return new AreaEditService(initial, 7L, LIBRARY::contains);
    }

    private static AreaDefinition area(String id, String musicId) {
        return AreaDefinition.create(
                id, "minecraft:overworld",
                new AreaPosition(0, 0, 0), new AreaPosition(4, 4, 4),
                List.of(new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000)),
                false, 0);
    }
}
