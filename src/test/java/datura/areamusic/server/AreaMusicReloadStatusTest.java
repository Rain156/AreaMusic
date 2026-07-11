package datura.areamusic.server;

import datura.areamusic.gametest.AreaMusicGameTests;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicReloadStatusTest {
    @Test
    void exposesImmutableReloadLifecycleStatus() throws Exception {
        Method accessor = assertDoesNotThrow(
                () -> AreaMusicServer.class.getMethod("reloadStatus"),
                "The server must expose reload completion and failure instead of relying on logs"
        );
        assertTrue(Modifier.isPublic(accessor.getModifiers()));
        assertTrue(Modifier.isStatic(accessor.getModifiers()));

        Object initial = accessor.invoke(null);
        Class<?> statusType = initial.getClass();
        assertTrue(statusType.isRecord(), "Reload status must be an immutable record snapshot");
        assertEquals(
                List.of("phase", "trackCount", "areaCount", "failureMessage"),
                Arrays.stream(statusType.getRecordComponents()).map(RecordComponent::getName).toList()
        );
        assertStatus(initial, "NOT_READY", 0, 0, "");

        Object loading = invokeFactory(statusType, "loading", new Class<?>[0]);
        Object succeeded = invokeFactory(statusType, "succeeded", new Class<?>[]{int.class, int.class}, 7, 2);
        Object failed = invokeFactory(statusType, "failed", new Class<?>[]{String.class}, new Object[]{null});
        assertStatus(loading, "LOADING", 0, 0, "");
        assertStatus(succeeded, "SUCCEEDED", 7, 2, "");
        assertStatus(failed, "FAILED", 0, 0, "Unknown reload failure");

        Field publishedStatus = AreaMusicServer.class.getDeclaredField("reloadStatus");
        assertTrue(Modifier.isPrivate(publishedStatus.getModifiers()));
        assertTrue(Modifier.isStatic(publishedStatus.getModifiers()));
        assertTrue(Modifier.isVolatile(publishedStatus.getModifiers()), "Reload snapshots must be safely published");
    }

    @Test
    void smokeTestWaitsAndRejectsFailuresOrWrongCounts() throws Exception {
        Class<?> statusType = assertDoesNotThrow(
                () -> Class.forName("datura.areamusic.server.AreaMusicServer$ReloadStatus"),
                "The current server exposes no state that distinguishes loading, failure, and loaded counts"
        );
        Method evaluate = assertDoesNotThrow(
                () -> AreaMusicGameTests.class.getDeclaredMethod("evaluateReload", statusType)
        );
        evaluate.setAccessible(true);

        Object notReady = invokeFactory(statusType, "notReady", new Class<?>[0]);
        Object loading = invokeFactory(statusType, "loading", new Class<?>[0]);
        Object failed = invokeFactory(statusType, "failed", new Class<?>[]{String.class}, "broken fixture");
        Object correct = invokeFactory(statusType, "succeeded", new Class<?>[]{int.class, int.class}, 7, 2);
        Object wrongTracks = invokeFactory(statusType, "succeeded", new Class<?>[]{int.class, int.class}, 6, 2);
        Object wrongAreas = invokeFactory(statusType, "succeeded", new Class<?>[]{int.class, int.class}, 7, 1);

        assertEquals("WAIT", evaluate.invoke(null, notReady).toString());
        assertEquals("WAIT", evaluate.invoke(null, loading).toString());
        assertEquals("FAIL", evaluate.invoke(null, failed).toString());
        assertEquals("SUCCEED", evaluate.invoke(null, correct).toString());
        assertEquals("FAIL", evaluate.invoke(null, wrongTracks).toString());
        assertEquals("FAIL", evaluate.invoke(null, wrongAreas).toString());
    }

    private static Object invokeFactory(
            Class<?> statusType,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        return statusType.getMethod(name, parameterTypes).invoke(null, arguments);
    }

    private static void assertStatus(
            Object status,
            String phase,
            int trackCount,
            int areaCount,
            String failureMessage
    ) throws Exception {
        Class<?> type = status.getClass();
        assertEquals(phase, type.getMethod("phase").invoke(status).toString());
        assertEquals(trackCount, type.getMethod("trackCount").invoke(status));
        assertEquals(areaCount, type.getMethod("areaCount").invoke(status));
        assertEquals(failureMessage, type.getMethod("failureMessage").invoke(status));
    }
}
