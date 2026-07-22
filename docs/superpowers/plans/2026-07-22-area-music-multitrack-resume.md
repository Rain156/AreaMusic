# AreaMusic Multitrack Delay and Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 1–16 independently configured, delayed, concurrent tracks per area, optional in-memory resume-on-reentry, and a safely migrated lowercase `areamusic` directory to both Forge 1.20.1 and NeoForge 1.21.1.

**Architecture:** Schema v2 introduces immutable per-track definitions while the reader maps schema v1 into a one-track runtime model. The server sends one revisioned area state containing all track definitions; a client-only frame-clock timeline schedules tracks and stores logical resume snapshots, while the PCM engine owns decoding, fades, mixing, and isolated failures. A shared directory resolver is the only code allowed to create or migrate the music root.

**Tech Stack:** Java 17, Forge 47.4.21, Java 21, NeoForge 21.1.235, Gson, Forge `FriendlyByteBuf` / NeoForge `StreamCodec`, Java Sound, JUnit Jupiter 5.10.2, Gradle 8.8 / 9.2.1.

---

## Execution boundaries

- Forge work happens in `F:\Dev\Minecraft Mods\AreaMusic` on `rain/forge-1.20.1-multitrack`.
- Before feature edits, merge `rain/forge-1.20.1-jorbis-fix` so the Forge implementation includes bundled providers and the decoder zero-read fix.
- Do not edit `F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-1.20.1-audio-fix`. It contains unrelated local changes to `build.gradle` and the third-party notice.
- NeoForge work gets a new isolated worktree at `F:\Dev\Minecraft Mods\AreaMusic\.worktrees\neoforge-1.21.1-multitrack` from `neoforge-1.21.1`.
- The approved specification is `docs/superpowers/specs/2026-07-22-area-music-multitrack-resume-design.md`.
- Every production change follows RED → GREEN → REFACTOR. A focused test must fail for the expected missing behavior before its production edit.

## Final file structure

### Loader-independent files created in both version branches

- `src/main/java/datura/areamusic/area/AreaTrackDefinition.java` — one validated track configuration.
- `src/main/java/datura/areamusic/music/MusicDirectory.java` — canonical lowercase path selection and safe legacy migration.
- `src/main/java/datura/areamusic/client/audio/AreaPlaybackTimeline.java` — pure frame-clock delay and snapshot state.
- `src/main/java/datura/areamusic/client/audio/AudioStreamPreparer.java` — bounded asynchronous reopen/fast-forward for resume.
- `src/test/java/datura/areamusic/area/AreaTrackDefinitionTest.java`.
- `src/test/java/datura/areamusic/music/MusicDirectoryTest.java`.
- `src/test/java/datura/areamusic/client/audio/AreaPlaybackTimelineTest.java`.
- `src/test/java/datura/areamusic/client/audio/AudioStreamPreparerTest.java`.

### Loader-independent files modified in both version branches

- `src/main/java/datura/areamusic/area/AreaDefinition.java` — replace scalar playback fields with immutable tracks and resume flag.
- `src/main/java/datura/areamusic/area/AreaJsonCodec.java` — strict v1 reader plus strict v2 reader/writer.
- `src/main/java/datura/areamusic/area/AreaStorage.java` — validate every referenced MusicID.
- `src/main/java/datura/areamusic/playback/PlaybackState.java` — network-safe area state with tracks.
- `src/main/java/datura/areamusic/server/PlayerAreaTracker.java` — continue comparing complete immutable playback states.
- `src/main/java/datura/areamusic/server/AreaMusicServer.java` — v2 create defaults and canonical directory preparation.
- `src/main/java/datura/areamusic/client/ClientPlaybackSession.java` — pass revisions into the mixer and clear snapshots only after successful reload.
- `src/main/java/datura/areamusic/client/ClientAreaMusic.java` — canonical directory scan and structured migration errors.
- `src/main/java/datura/areamusic/client/audio/ClientAudioMixer.java` — revisioned `apply` contract.
- `src/main/java/datura/areamusic/client/audio/PcmAudioMixer.java` — frame-clock work during silent delays, failure draining, preparer lifecycle.
- `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java` — concurrent sessions, delays, crossfades, snapshots, isolated track failures.
- All directly corresponding tests under `src/test/java/datura/areamusic`.

### Loader-specific files

- Forge: `src/main/java/datura/areamusic/network/ClientboundPlaybackState.java` and `AreaMusicNetwork.java`.
- NeoForge: the same paths, retaining `CustomPacketPayload` and `StreamCodec`.
- Forge: create `src/main/java/datura/areamusic/gametest/AreaMusicGameTests.java`.
- NeoForge: modify its existing `src/main/java/datura/areamusic/gametest/AreaMusicGameTests.java`.

---

### Task 1: Establish the verified Forge baseline

**Files:**
- Verify only: repository and generated build outputs

- [ ] **Step 1: Confirm that only the feature worktree is in scope**

Run:

~~~powershell
git status --short --branch
git worktree list --porcelain
git -C '.worktrees/forge-1.20.1-audio-fix' status --short --branch
~~~

Expected: the root is on `rain/forge-1.20.1-multitrack` and clean; the separate Forge worktree still reports its pre-existing `build.gradle` and notice changes.

- [ ] **Step 2: Merge the latest verified Forge audio continuity baseline**

Run:

~~~powershell
git merge --no-edit rain/forge-1.20.1-jorbis-fix
~~~

Expected: a clean merge brings in commits `2ee6514`, `68dcc6e`, and `6bf641e` without touching the unrelated dirty worktree.

- [ ] **Step 3: Select Java 17 and verify the baseline**

Run:

~~~powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
.\gradlew.bat clean test build reobfJarJar --console=plain
~~~

Expected: Java 17.0.12 is active and Gradle ends with `BUILD SUCCESSFUL`. All existing MP3, OGG, WAV, FLAC, 48 kHz, zero-read, command, area, mixer, and volume tests pass.

- [ ] **Step 4: Record the baseline without adding generated files**

Run:

~~~powershell
git status --short
~~~

Expected: no tracked changes.

---

### Task 2: Introduce the v2 track model and v1 compatibility

**Files:**
- Create: `src/main/java/datura/areamusic/area/AreaTrackDefinition.java`
- Create: `src/test/java/datura/areamusic/area/AreaTrackDefinitionTest.java`
- Modify: `src/main/java/datura/areamusic/area/AreaDefinition.java:9-94`
- Modify: `src/main/java/datura/areamusic/area/AreaJsonCodec.java:17-170`
- Modify: `src/main/java/datura/areamusic/area/AreaStorage.java:72-106`
- Modify: `src/main/java/datura/areamusic/server/AreaMusicServer.java:137-190`
- Modify: `src/main/java/datura/areamusic/playback/PlaybackState.java:56-60`
- Modify: area, storage, resolver, playback, server, and mixer test fixtures that construct `AreaDefinition`

- [ ] **Step 1: Write failing track-model tests**

Create `AreaTrackDefinitionTest.java` with these behaviors:

~~~java
package datura.areamusic.area;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaTrackDefinitionTest {
    @Test
    void convertsDelaySecondsToExactMixerFrames() {
        AreaTrackDefinition track = new AreaTrackDefinition("ambient.ogg", 5, 0.8f, true, 250, 750);
        assertEquals(220_500L, track.delayFrames(44_100));
    }

    @Test
    void rejectsInvalidTrackFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("", 0, 1.0f, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", -1, 1.0f, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, Float.NaN, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, 1.0f, true, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 0, 60_001));
    }
}
~~~

Add to `AreaDefinitionTest`:

~~~java
@Test
void requiresBetweenOneAndSixteenTracksAndCopiesTheList() {
    List<AreaTrackDefinition> mutable = new ArrayList<>();
    mutable.add(track("first.ogg"));
    AreaDefinition area = AreaDefinition.create(
            "area", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, mutable, true, 0);
    mutable.add(track("second.ogg"));

    assertEquals(1, area.tracks().size());
    assertTrue(area.resumeOnReenter());
    assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
            "area", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, List.of(), false, 0));
    assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
            "area", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO,
            java.util.stream.IntStream.range(0, 17)
                    .mapToObj(index -> track("track-" + index + ".ogg")).toList(),
            false, 0));
}

private static AreaTrackDefinition track(String musicId) {
    return new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000);
}
~~~

- [ ] **Step 2: Write failing v1/v2 codec tests**

Replace the old required-fields test with one v1 compatibility test and add v2 defaults plus full round trip:

~~~java
@Test
void readsV1AsOneImmediateNonResumingTrack() {
    AreaDefinition area = codec.read("square", new StringReader(validV1Json()));
    assertEquals(1, area.tracks().size());
    assertEquals(new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000),
            area.tracks().get(0));
    assertFalse(area.resumeOnReenter());
}

@Test
void readsV2TrackDefaultsAndAllowsDuplicateMusicIds() {
    AreaDefinition area = codec.read("square", new StringReader("""
            {
              "schemaVersion": 2,
              "dimension": "minecraft:overworld",
              "pos1": { "x": 0, "y": 64, "z": 0 },
              "pos2": { "x": 10, "y": 80, "z": 10 },
              "tracks": [
                { "musicId": "track.ogg" },
                { "musicId": "track.ogg", "delaySeconds": 5, "loop": false }
              ],
              "resumeOnReenter": true
            }
            """));
    assertEquals(2, area.tracks().size());
    assertEquals(0, area.tracks().get(0).delaySeconds());
    assertEquals(5, area.tracks().get(1).delaySeconds());
    assertTrue(area.resumeOnReenter());
}

@Test
void writesOnlyStrictV2FieldsAndRoundTrips() {
    AreaDefinition original = AreaDefinition.create(
            "boss", OVERWORLD, BlockPos.ZERO, new BlockPos(5, 5, 5),
            List.of(
                    new AreaTrackDefinition("ambient.ogg", 0, 0.5f, true, 500, 1000),
                    new AreaTrackDefinition("voice.mp3", 3, 1.0f, false, 0, 250)
            ),
            true,
            12
    );
    String json = codec.write(original);
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
    assertEquals(2, root.get("schemaVersion").getAsInt());
    assertTrue(root.has("tracks"));
    assertFalse(root.has("musicId"));
    assertEquals(original, codec.read("boss", new StringReader(json)));
}

private static String validV1Json() {
    return """
            {
              "schemaVersion": 1,
              "dimension": "minecraft:overworld",
              "pos1": { "x": 0, "y": 64, "z": 0 },
              "pos2": { "x": 10, "y": 80, "z": 10 },
              "musicId": "track.ogg"
            }
            """;
}
~~~

Add:

~~~java
@Test
void rejectsInvalidV2ShapeAndDelayValues() {
    String oneTrack = validV2JsonWithTracks("{ \"musicId\": \"track.ogg\" }");
    String legacyRootField = oneTrack.replace(
            "\"tracks\":",
            "\"musicId\": \"legacy.ogg\", \"tracks\":");
    String seventeenTracks = java.util.stream.IntStream.range(0, 17)
            .mapToObj(index -> "{ \"musicId\": \"track-" + index + ".ogg\" }")
            .collect(java.util.stream.Collectors.joining(","));

    assertThrows(JsonParseException.class,
            () -> codec.read("area", new StringReader(legacyRootField)));
    assertThrows(JsonParseException.class,
            () -> codec.read("area", new StringReader(validV2JsonWithTracks(""))));
    assertThrows(JsonParseException.class,
            () -> codec.read("area", new StringReader(validV2JsonWithTracks(seventeenTracks))));
    assertThrows(JsonParseException.class,
            () -> codec.read("area", new StringReader(validV2JsonWithTracks(
                    "{ \"musicId\": \"track.ogg\", \"delaySeconds\": 0.5 }"))));
    assertThrows(JsonParseException.class,
            () -> codec.read("area", new StringReader(validV2JsonWithTracks(
                    "{ \"musicId\": \"track.ogg\", \"delaySeconds\": -1 }"))));
    assertThrows(JsonParseException.class,
            () -> codec.read("area", new StringReader(validV2JsonWithTracks(
                    "{ \"musicId\": \"track.ogg\", \"delaySecond\": 1 }"))));
}

private static String validV2JsonWithTracks(String tracks) {
    return """
            {
              "schemaVersion": 2,
              "dimension": "minecraft:overworld",
              "pos1": { "x": 0, "y": 64, "z": 0 },
              "pos2": { "x": 10, "y": 80, "z": 10 },
              "tracks": [ %s ]
            }
            """.formatted(tracks);
}
~~~

- [ ] **Step 3: Run the focused tests and observe RED**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.area.AreaTrackDefinitionTest" --tests "datura.areamusic.area.AreaDefinitionTest" --tests "datura.areamusic.area.AreaJsonCodecTest" --console=plain
~~~

Expected: test compilation fails because `AreaTrackDefinition` and the list-based `AreaDefinition.create` do not exist.

- [ ] **Step 4: Implement the immutable track definition**

Create the complete record:

~~~java
package datura.areamusic.area;

import java.util.Objects;

public record AreaTrackDefinition(
        String musicId,
        int delaySeconds,
        float volume,
        boolean loop,
        int fadeInMs,
        int fadeOutMs
) {
    public static final int MAX_FADE_MS = 60_000;

    public AreaTrackDefinition {
        Objects.requireNonNull(musicId, "musicId");
        if (musicId.isBlank()) {
            throw new IllegalArgumentException("Music ID must not be blank");
        }
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("Delay seconds must not be negative");
        }
        if (!Float.isFinite(volume) || volume < 0.0f || volume > 1.0f) {
            throw new IllegalArgumentException("Volume must be finite and between 0 and 1");
        }
        validateFade("fadeInMs", fadeInMs);
        validateFade("fadeOutMs", fadeOutMs);
    }

    public long delayFrames(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }
        return (long) delaySeconds * sampleRate;
    }

    private static void validateFade(String name, int value) {
        if (value < 0 || value > MAX_FADE_MS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_FADE_MS);
        }
    }
}
~~~

- [ ] **Step 5: Replace scalar area playback fields**

Use this final record shape:

~~~java
public record AreaDefinition(
        String id,
        ResourceLocation dimension,
        BlockPos min,
        BlockPos max,
        List<AreaTrackDefinition> tracks,
        boolean resumeOnReenter,
        int priority
) {
    public static final int MAX_TRACKS = 16;

    public AreaDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid area ID: " + id);
        }
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (tracks.isEmpty() || tracks.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("Track count must be between 1 and " + MAX_TRACKS);
        }
        BlockPos first = min;
        BlockPos second = max;
        min = new BlockPos(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())
        );
        max = new BlockPos(
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ())
        );
    }

    public static AreaDefinition create(
            String id,
            ResourceLocation dimension,
            BlockPos pos1,
            BlockPos pos2,
            List<AreaTrackDefinition> tracks,
            boolean resumeOnReenter,
            int priority
    ) {
        return new AreaDefinition(id, dimension, pos1, pos2, tracks, resumeOnReenter, priority);
    }
}
~~~

Keep `contains` and `volumeInBlocks` byte-for-byte equivalent to the current implementation.

- [ ] **Step 6: Implement strict v1/v2 parsing and v2 writing**

Use separate allowed-field sets:

~~~java
private static final int LEGACY_SCHEMA_VERSION = 1;
private static final int CURRENT_SCHEMA_VERSION = 2;
private static final Set<String> V1_ROOT_FIELDS = Set.of(
        "schemaVersion", "dimension", "pos1", "pos2", "musicId",
        "priority", "volume", "loop", "fadeInMs", "fadeOutMs"
);
private static final Set<String> V2_ROOT_FIELDS = Set.of(
        "schemaVersion", "dimension", "pos1", "pos2", "tracks",
        "resumeOnReenter", "priority"
);
private static final Set<String> TRACK_FIELDS = Set.of(
        "musicId", "delaySeconds", "volume", "loop", "fadeInMs", "fadeOutMs"
);
~~~

Parse the shared geometry first, then dispatch while mapping model validation into the codec's structured error type:

~~~java
try {
    return switch (schemaVersion) {
        case LEGACY_SCHEMA_VERSION -> readV1(areaId, root, dimension, pos1, pos2);
        case CURRENT_SCHEMA_VERSION -> readV2(areaId, root, dimension, pos1, pos2);
        default -> throw new JsonParseException("Unsupported schemaVersion: " + schemaVersion);
    };
} catch (IllegalArgumentException exception) {
    throw new JsonParseException(
            "Invalid area '" + areaId + "': " + exception.getMessage(), exception);
}
~~~

Implement the version-specific methods as:

~~~java
private static AreaDefinition readV1(
        String areaId,
        JsonObject root,
        ResourceLocation dimension,
        BlockPos pos1,
        BlockPos pos2
) {
    rejectUnknownFields(root, V1_ROOT_FIELDS, "root");
    AreaTrackDefinition track = new AreaTrackDefinition(
            requireString(root, "musicId"),
            0,
            optionalFloat(root, "volume", 1.0f),
            optionalBoolean(root, "loop", true),
            optionalInt(root, "fadeInMs", 2000),
            optionalInt(root, "fadeOutMs", 2000)
    );
    return AreaDefinition.create(
            areaId, dimension, pos1, pos2, List.of(track), false,
            optionalInt(root, "priority", 0));
}

private static AreaDefinition readV2(
        String areaId,
        JsonObject root,
        ResourceLocation dimension,
        BlockPos pos1,
        BlockPos pos2
) {
    rejectUnknownFields(root, V2_ROOT_FIELDS, "root");
    JsonArray array = requireArray(require(root, "tracks"), "tracks");
    if (array.size() == 0 || array.size() > AreaDefinition.MAX_TRACKS) {
        throw new JsonParseException(
                "tracks must contain between 1 and " + AreaDefinition.MAX_TRACKS + " entries");
    }
    List<AreaTrackDefinition> tracks = new ArrayList<>(array.size());
    for (int index = 0; index < array.size(); index++) {
        JsonObject track = requireObject(array.get(index), "tracks[" + index + "]");
        rejectUnknownFields(track, TRACK_FIELDS, "tracks[" + index + "]");
        tracks.add(new AreaTrackDefinition(
                requireString(track, "musicId"),
                optionalInt(track, "delaySeconds", 0),
                optionalFloat(track, "volume", 1.0f),
                optionalBoolean(track, "loop", true),
                optionalInt(track, "fadeInMs", 2000),
                optionalInt(track, "fadeOutMs", 2000)
        ));
    }
    return AreaDefinition.create(
            areaId, dimension, pos1, pos2, tracks,
            optionalBoolean(root, "resumeOnReenter", false),
            optionalInt(root, "priority", 0));
}

private static JsonArray requireArray(JsonElement element, String name) {
    if (!element.isJsonArray()) {
        throw new JsonParseException(name + " must be an array");
    }
    return element.getAsJsonArray();
}
~~~

Replace `write` with:

~~~java
public String write(AreaDefinition area) {
    JsonObject root = new JsonObject();
    root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
    root.addProperty("dimension", area.dimension().toString());
    root.add("pos1", position(area.min()));
    root.add("pos2", position(area.max()));
    JsonArray tracks = new JsonArray();
    for (AreaTrackDefinition track : area.tracks()) {
        JsonObject entry = new JsonObject();
        entry.addProperty("musicId", track.musicId());
        entry.addProperty("delaySeconds", track.delaySeconds());
        entry.addProperty("volume", track.volume());
        entry.addProperty("loop", track.loop());
        entry.addProperty("fadeInMs", track.fadeInMs());
        entry.addProperty("fadeOutMs", track.fadeOutMs());
        tracks.add(entry);
    }
    root.add("tracks", tracks);
    root.addProperty("resumeOnReenter", area.resumeOnReenter());
    root.addProperty("priority", area.priority());
    return GSON.toJson(root) + System.lineSeparator();
}
~~~

- [ ] **Step 7: Make all existing call sites compile with the list model**

Use this default track wherever `/areamusic create` or a test fixture previously supplied scalar playback fields:

~~~java
new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000)
~~~

For this task only, keep `PlaybackState.fromArea` behavior compatible by selecting `area.tracks().get(0)`; Task 3 replaces `PlaybackState` with the complete list before any release build.

Update `AreaStorage.load` to validate all tracks:

~~~java
for (AreaTrackDefinition track : area.tracks()) {
    if (!musicLibrary.contains(track.musicId())) {
        throw new IllegalArgumentException(
                "MusicID is not present in the server library: " + track.musicId());
    }
}
~~~

Replace the single-ID storage rejection fixture with:

~~~java
@Test
void rejectsAnAreaWhenAnyTrackIsMissingFromTheCandidateLibrary() throws Exception {
    Path areaDirectory = tempDir.resolve("config/areamusic/save-id");
    Path musicRoot = tempDir.resolve("areamusic");
    Files.createDirectories(musicRoot);
    Files.writeString(musicRoot.resolve("present.ogg"), "fixture");
    Files.createDirectories(areaDirectory);
    Files.writeString(areaDirectory.resolve("mixed.json"), validV2JsonWithTracks("""
            { "musicId": "present.ogg" },
            { "musicId": "missing.mp3", "delaySeconds": 5 }
            """));
    AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

    AreaStorage.LoadException error = assertThrows(
            AreaStorage.LoadException.class,
            () -> storage.load(MusicLibrary.scan(musicRoot))
    );

    assertTrue(error.getMessage().contains("missing.mp3"));
}

private static String validV2JsonWithTracks(String tracks) {
    return """
            {
              "schemaVersion": 2,
              "dimension": "minecraft:overworld",
              "pos1": { "x": 0, "y": 64, "z": 0 },
              "pos2": { "x": 10, "y": 80, "z": 10 },
              "tracks": [ %s ]
            }
            """.formatted(tracks);
}
~~~

After `storage.create(area)` in the existing creation test, parse the saved file and assert:

~~~java
JsonObject savedJson = JsonParser.parseString(Files.readString(saved)).getAsJsonObject();
assertEquals(2, savedJson.get("schemaVersion").getAsInt());
assertEquals(1, savedJson.getAsJsonArray("tracks").size());
assertFalse(savedJson.has("musicId"));
~~~

- [ ] **Step 8: Run focused and full tests**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.area.*" --tests "datura.areamusic.server.PlayerAreaTrackerTest" --console=plain
.\gradlew.bat test --console=plain
~~~

Expected: both commands end with `BUILD SUCCESSFUL` and all existing tests are adapted to the list-based constructor.

- [ ] **Step 9: Commit the schema slice**

Run:

~~~powershell
git add src/main/java/datura/areamusic/area src/main/java/datura/areamusic/server/AreaMusicServer.java src/main/java/datura/areamusic/playback/PlaybackState.java src/test/java/datura/areamusic
git commit -m "feat: add multitrack area schema"
~~~

---

### Task 3: Carry all tracks through Forge playback state and networking

**Files:**
- Modify: `src/main/java/datura/areamusic/playback/PlaybackState.java:7-67`
- Modify: `src/main/java/datura/areamusic/network/ClientboundPlaybackState.java:10-57`
- Modify: `src/main/java/datura/areamusic/network/AreaMusicNetwork.java:16`
- Modify: `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java:23-205`
- Modify: `src/test/java/datura/areamusic/playback/PlaybackStateTest.java:13-50`
- Modify: `src/test/java/datura/areamusic/server/PlayerAreaTrackerTest.java`

- [ ] **Step 1: Write a failing multitrack network round-trip**

Replace the playing-state fixture with:

~~~java
List<AreaTrackDefinition> tracks = List.of(
        new AreaTrackDefinition("village/day.mp3", 0, 0.75f, true, 1000, 2500),
        new AreaTrackDefinition("village/bell.ogg", 5, 0.5f, false, 0, 500)
);
PlaybackState state = PlaybackState.playing("square", tracks, true);
ClientboundPlaybackState message = new ClientboundPlaybackState(7L, state);
FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

ClientboundPlaybackState.encode(message, buffer);
ClientboundPlaybackState decoded = ClientboundPlaybackState.decode(buffer);

assertEquals(message, decoded);
assertEquals(0, buffer.readableBytes());
~~~

Add rejection tests for an empty playing list and a constructed list of 17 tracks. Change the stopped-state assertion to `assertTrue(decoded.state().tracks().isEmpty())`.

- [ ] **Step 2: Run the network test and observe RED**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.playback.PlaybackStateTest" --console=plain
~~~

Expected: test compilation fails because list-based `PlaybackState.playing` does not exist.

- [ ] **Step 3: Replace PlaybackState with the final list model**

Use this complete public API:

~~~java
public record PlaybackState(
        boolean playing,
        String areaId,
        List<AreaTrackDefinition> tracks,
        boolean resumeOnReenter
) {
    public PlaybackState {
        Objects.requireNonNull(areaId, "areaId");
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (!playing) {
            areaId = "";
            tracks = List.of();
            resumeOnReenter = false;
        } else {
            if (areaId.isBlank()) {
                throw new IllegalArgumentException("Area ID must not be blank");
            }
            if (tracks.isEmpty() || tracks.size() > AreaDefinition.MAX_TRACKS) {
                throw new IllegalArgumentException(
                        "Track count must be between 1 and " + AreaDefinition.MAX_TRACKS);
            }
        }
    }

    public static PlaybackState stopped() {
        return new PlaybackState(false, "", List.of(), false);
    }

    public static PlaybackState playing(
            String areaId,
            List<AreaTrackDefinition> tracks,
            boolean resumeOnReenter
    ) {
        return new PlaybackState(true, areaId, tracks, resumeOnReenter);
    }

    public static PlaybackState fromArea(AreaDefinition area) {
        return playing(area.id(), area.tracks(), area.resumeOnReenter());
    }
}
~~~

- [ ] **Step 4: Encode the bounded track list in Forge**

Set `AreaMusicNetwork.PROTOCOL_VERSION` to `"2"`. In `ClientboundPlaybackState`, after revision, playing flag, and area ID, write resume then count and tracks:

~~~java
buffer.writeBoolean(state.resumeOnReenter());
buffer.writeVarInt(state.tracks().size());
for (AreaTrackDefinition track : state.tracks()) {
    buffer.writeUtf(track.musicId(), MAX_MUSIC_ID_LENGTH);
    buffer.writeVarInt(track.delaySeconds());
    buffer.writeFloat(track.volume());
    buffer.writeBoolean(track.loop());
    buffer.writeVarInt(track.fadeInMs());
    buffer.writeVarInt(track.fadeOutMs());
}
~~~

Decode the count before allocation:

~~~java
boolean resumeOnReenter = buffer.readBoolean();
int trackCount = buffer.readVarInt();
if (trackCount < 1 || trackCount > AreaDefinition.MAX_TRACKS) {
    throw new IllegalArgumentException("Invalid network track count: " + trackCount);
}
List<AreaTrackDefinition> tracks = new ArrayList<>(trackCount);
for (int index = 0; index < trackCount; index++) {
    tracks.add(new AreaTrackDefinition(
            buffer.readUtf(MAX_MUSIC_ID_LENGTH),
            buffer.readVarInt(),
            buffer.readFloat(),
            buffer.readBoolean(),
            buffer.readVarInt(),
            buffer.readVarInt()
    ));
}
return new ClientboundPlaybackState(
        revision,
        PlaybackState.playing(areaId, tracks, resumeOnReenter)
);
~~~

Stopped messages still return immediately after the false playing flag.

- [ ] **Step 5: Keep the old single-track mixer compiling until its multitrack RED test**

Inside the current `PcmMixerEngine`, replace scalar state access with a private helper:

~~~java
private static AreaTrackDefinition primaryTrack(PlaybackState state) {
    return state.tracks().get(0);
}
~~~

Use that helper for MusicID, loop, volume, and fades. Do not add concurrent-track behavior in this task; Task 6 begins with a failing mixer test that exposes this temporary limitation.

- [ ] **Step 6: Run focused and full tests**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.playback.PlaybackStateTest" --tests "datura.areamusic.server.PlayerAreaTrackerTest" --console=plain
.\gradlew.bat test --console=plain
~~~

Expected: `BUILD SUCCESSFUL`. The network round-trip preserves both ordered tracks and `resumeOnReenter`.

- [ ] **Step 7: Commit the Forge state protocol**

Run:

~~~powershell
git add src/main/java/datura/areamusic/playback src/main/java/datura/areamusic/network src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java src/test/java/datura/areamusic/playback src/test/java/datura/areamusic/server
git commit -m "feat: sync multitrack playback states"
~~~

---

### Task 4: Canonicalize and migrate the lowercase music directory

**Files:**
- Create: `src/main/java/datura/areamusic/music/MusicDirectory.java`
- Create: `src/test/java/datura/areamusic/music/MusicDirectoryTest.java`
- Modify: `src/main/java/datura/areamusic/client/ClientAreaMusic.java:35-105`
- Modify: `src/main/java/datura/areamusic/server/AreaMusicServer.java:43-242`
- Modify: `src/test/java/datura/areamusic/music/MusicLibraryTest.java`
- Modify: `src/test/java/datura/areamusic/area/AreaStorageTest.java`

- [ ] **Step 1: Write failing directory creation and migration tests**

Create tests that enumerate exact child names rather than calling `Files.exists` with alternate casing:

~~~java
@Test
void createsOnlyTheLowercaseDirectory() throws Exception {
    Path result = MusicDirectory.prepare(tempDir);
    assertEquals(tempDir.resolve("areamusic").toAbsolutePath().normalize(), result);
    assertEquals(Set.of("areamusic"), exactChildNames(tempDir));
}

@Test
void migratesTheLegacyDirectoryThroughATemporarySibling() throws Exception {
    Path legacy = tempDir.resolve("AreaMusic");
    Files.createDirectories(legacy);
    Files.writeString(legacy.resolve("track.ogg"), "fixture");

    Path result = MusicDirectory.prepare(tempDir);

    assertEquals(Set.of("areamusic"), exactChildNames(tempDir));
    assertEquals("fixture", Files.readString(result.resolve("track.ogg")));
}

@Test
void rejectsTwoDistinctCaseVariantsWithoutChangingEither() throws Exception {
    assumeCaseSensitive(tempDir);
    Files.createDirectories(tempDir.resolve("AreaMusic"));
    Files.createDirectories(tempDir.resolve("areamusic"));

    assertThrows(MusicDirectory.ConflictException.class,
            () -> MusicDirectory.prepare(tempDir));
    assertEquals(Set.of("AreaMusic", "areamusic"), exactChildNames(tempDir));
}
~~~

Add:

~~~java
@Test
void rejectsALegacyNonDirectoryWithoutDeletingIt() throws Exception {
    Path legacy = tempDir.resolve("AreaMusic");
    Files.writeString(legacy, "not a directory");

    assertThrows(IOException.class, () -> MusicDirectory.prepare(tempDir));
    assertEquals("not a directory", Files.readString(legacy));
    assertEquals(Set.of("AreaMusic"), exactChildNames(tempDir));
}

private static Set<String> exactChildNames(Path root) throws IOException {
    try (Stream<Path> children = Files.list(root)) {
        return children.map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}

private static void assumeCaseSensitive(Path root) throws IOException {
    Path probe = root.resolve("case-probe");
    Files.writeString(probe, "probe");
    boolean insensitive = Files.exists(root.resolve("CASE-PROBE"));
    Files.delete(probe);
    org.junit.jupiter.api.Assumptions.assumeFalse(insensitive);
}
~~~

- [ ] **Step 2: Run the focused test and observe RED**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.music.MusicDirectoryTest" --console=plain
~~~

Expected: test compilation fails because `MusicDirectory` does not exist.

- [ ] **Step 3: Implement the directory resolver**

Provide this public contract:

~~~java
public final class MusicDirectory {
    public static final String DIRECTORY_NAME = "areamusic";
    static final String LEGACY_DIRECTORY_NAME = "AreaMusic";

    public static Path canonicalPath(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize().resolve(DIRECTORY_NAME);
    }

    public static synchronized Path prepare(Path gameDirectory) throws IOException {
        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Files.createDirectories(gameRoot);
        Path lowercase = null;
        Path legacy = null;
        try (Stream<Path> children = Files.list(gameRoot)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                String name = child.getFileName().toString();
                if (DIRECTORY_NAME.equals(name)) {
                    lowercase = child;
                } else if (LEGACY_DIRECTORY_NAME.equals(name)) {
                    legacy = child;
                }
            }
        }

        if (lowercase != null && legacy != null) {
            if (!Files.isSameFile(lowercase, legacy)) {
                throw new ConflictException(lowercase, legacy);
            }
            return requireRealDirectory(lowercase);
        }
        if (lowercase != null) {
            return requireRealDirectory(lowercase);
        }
        if (legacy != null) {
            requireRealDirectory(legacy);
            return migrateLegacy(gameRoot, legacy);
        }

        Path canonical = canonicalPath(gameRoot);
        Files.createDirectory(canonical);
        return canonical;
    }

    private static Path requireRealDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Music root must be a real directory: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path migrateLegacy(Path gameRoot, Path legacy) throws IOException {
        Path canonical = canonicalPath(gameRoot);
        Path temporary = gameRoot.resolve(".areamusic-migrate-" + UUID.randomUUID());
        move(legacy, temporary);
        boolean installed = false;
        try {
            move(temporary, canonical);
            installed = true;
            return canonical;
        } finally {
            if (!installed && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                    && Files.notExists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                move(temporary, legacy);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    public static final class ConflictException extends IOException {
        public ConflictException(Path lowercase, Path legacy) {
            super("Both music directories exist: " + lowercase + " and " + legacy);
        }
    }
}
~~~

The `prepare` body must perform these concrete operations:

1. `Files.createDirectories(normalizedGameDirectory)`.
2. Enumerate direct children once and identify exact names `areamusic` and `AreaMusic`.
3. Validate selected entries with `NOFOLLOW_LINKS` and reject symbolic links.
4. If only legacy exists, move it to `.areamusic-migrate-<UUID>`, then move that sibling to `areamusic`.
5. Prefer `ATOMIC_MOVE` and fall back to a normal move when unsupported.
6. If the second move fails, restore the temporary sibling to `AreaMusic` before rethrowing.
7. If both exact entries exist, use `Files.isSameFile`; normalize one same-file entry through the temporary sibling, otherwise throw `ConflictException`.

- [ ] **Step 4: Route every runtime scan through MusicDirectory**

In `AreaMusicServer`, store the normalized game directory, not a pre-created music root. `loadCandidate` must call:

~~~java
Path root = MusicDirectory.prepare(gameDirectory);
MusicLibrary candidateLibrary = MusicLibrary.scan(root);
~~~

The static empty library and `stop` use `MusicDirectory.canonicalPath` without performing I/O.

In `ClientAreaMusic`, let each asynchronous local reload call `MusicDirectory.prepare(gameDirectory)` before `MusicLibrary.scan`. A migration exception travels through the existing `SCAN` failure path and must not trigger a fallback scan that creates or ignores another directory.

Replace test fixture roots named `AreaMusic` with `areamusic` except in explicit migration tests.

- [ ] **Step 5: Run directory, library, server, and full tests**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.music.*" --tests "datura.areamusic.area.AreaStorageTest" --console=plain
.\gradlew.bat test --console=plain
~~~

Expected: `BUILD SUCCESSFUL` and exact-name enumeration confirms only lowercase creation.

- [ ] **Step 6: Commit the directory fix**

Run:

~~~powershell
git add src/main/java/datura/areamusic/music src/main/java/datura/areamusic/client/ClientAreaMusic.java src/main/java/datura/areamusic/server/AreaMusicServer.java src/test/java/datura/areamusic
git commit -m "fix: migrate music root to lowercase"
~~~

---

### Task 5: Build the pure frame-clock timeline

**Files:**
- Create: `src/main/java/datura/areamusic/client/audio/AreaPlaybackTimeline.java`
- Create: `src/test/java/datura/areamusic/client/audio/AreaPlaybackTimelineTest.java`

- [ ] **Step 1: Write failing delay and snapshot tests**

Use a 10 Hz sample rate to make expectations readable:

~~~java
@Test
void exposesTracksExactlyWhenTheirDelayExpires() {
    AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
            List.of(track("now.ogg", 0), track("later.ogg", 5)), 10);

    assertEquals(List.of(0), timeline.dueTrackIndices());
    timeline.markStarted(0);
    assertEquals(50L, timeline.framesUntilNextStart());
    timeline.advancePending(49);
    assertTrue(timeline.dueTrackIndices().isEmpty());
    timeline.advancePending(1);
    assertEquals(List.of(1), timeline.dueTrackIndices());
}

@Test
void snapshotRestoresRemainingDelayCursorCompletionAndFailure() {
    AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
            List.of(track("loop.ogg", 0), track("later.ogg", 10)), 10);
    timeline.markStarted(0);
    timeline.recordFramesRead(0, 37);
    timeline.advancePending(40);
    timeline.markCompleted(0);

    AreaPlaybackTimeline restored = AreaPlaybackTimeline.restore(
            timeline.snapshot(), List.of(track("loop.ogg", 0), track("later.ogg", 10)), 10);

    assertEquals(60L, restored.remainingDelayFrames(1));
    assertEquals(37L, restored.positionInLoopFrames(0));
    assertTrue(restored.completed(0));
}
~~~

Add:

~~~java
@Test
void loopRestartCompletionAndFailureAreIndependentPerTrack() {
    AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
            List.of(track("loop.ogg", 0), track("bad.ogg", 0)), 10);
    timeline.markStarted(0);
    timeline.recordFramesRead(0, 12);
    timeline.markLoopRestarted(0);
    timeline.markFailed(1);

    assertEquals(0L, timeline.positionInLoopFrames(0));
    assertTrue(timeline.failed(1));
    assertEquals(List.of(), timeline.dueTrackIndices());

    timeline.markCompleted(0);
    assertTrue(timeline.completed(0));
}

@Test
void rejectsInvalidSnapshotAndFrameAdvancement() {
    AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
            List.of(track("one.ogg", Integer.MAX_VALUE)), 44_100);
    assertEquals((long) Integer.MAX_VALUE * 44_100L,
            timeline.remainingDelayFrames(0));
    assertThrows(IllegalArgumentException.class, () -> timeline.advancePending(-1));
    assertThrows(IllegalArgumentException.class, () -> AreaPlaybackTimeline.restore(
            new AreaPlaybackTimeline.Snapshot(List.of()),
            List.of(track("one.ogg", 0)),
            44_100
    ));
}
~~~

Add this fixture helper to `AreaPlaybackTimelineTest`:

~~~java
private static AreaTrackDefinition track(String musicId, int delaySeconds) {
    return new AreaTrackDefinition(musicId, delaySeconds, 1.0f, true, 0, 0);
}
~~~

- [ ] **Step 2: Run the focused test and observe RED**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.AreaPlaybackTimelineTest" --console=plain
~~~

Expected: compilation fails because `AreaPlaybackTimeline` does not exist.

- [ ] **Step 3: Implement the exact timeline API**

The class has no Minecraft, filesystem, decoder, or thread dependency:

~~~java
public final class AreaPlaybackTimeline {
    public static AreaPlaybackTimeline fresh(
            List<AreaTrackDefinition> definitions, int sampleRate);

    public static AreaPlaybackTimeline restore(
            Snapshot snapshot,
            List<AreaTrackDefinition> definitions,
            int sampleRate);

    public List<Integer> dueTrackIndices();
    public long framesUntilNextStart();
    public void advancePending(long frames);
    public void markStarted(int trackIndex);
    public void recordFramesRead(int trackIndex, long frames);
    public void markLoopRestarted(int trackIndex);
    public void markCompleted(int trackIndex);
    public void markFailed(int trackIndex);
    public long remainingDelayFrames(int trackIndex);
    public long positionInLoopFrames(int trackIndex);
    public boolean started(int trackIndex);
    public boolean completed(int trackIndex);
    public boolean failed(int trackIndex);
    public Snapshot snapshot();

    public record Snapshot(List<TrackSnapshot> tracks) {
        public Snapshot {
            tracks = List.copyOf(tracks);
        }
    }

    public record TrackSnapshot(
            long remainingDelayFrames,
            long positionInLoopFrames,
            boolean started,
            boolean completed,
            boolean failed
    ) {}
}
~~~

`advancePending` subtracts from every not-started, not-completed, not-failed entry and clamps at zero. `dueTrackIndices` returns stable ascending JSON indices without mutating them. `framesUntilNextStart` returns `Long.MAX_VALUE` when no pending track remains.

- [ ] **Step 4: Run the focused tests and commit**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.AreaPlaybackTimelineTest" --console=plain
git add src/main/java/datura/areamusic/client/audio/AreaPlaybackTimeline.java src/test/java/datura/areamusic/client/audio/AreaPlaybackTimelineTest.java
git commit -m "feat: add resumable area timeline"
~~~

Expected: focused tests pass before the commit.

---

### Task 6: Schedule and mix concurrent delayed tracks

**Files:**
- Modify: `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java:14-330`
- Modify: `src/main/java/datura/areamusic/client/audio/PcmAudioMixer.java:11-310`
- Modify: `src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java`
- Modify: `src/test/java/datura/areamusic/client/audio/PcmAudioMixerTest.java`

- [ ] **Step 1: Write a failing concurrent-mix test**

~~~java
@Test
void startsDelayedTracksOnTheirExactFrameAndMixesThemConcurrently() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("first.wav"), constantFrames(50_000, (short) 1000));
    writeWav(root.resolve("second.wav"), constantFrames(50_000, (short) 2000));
    PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));
    engine.apply(1L, PlaybackState.playing("area", List.of(
            track("first.wav", 0, true, 0, 0),
            track("second.wav", 1, true, 0, 0)
    ), false));

    byte[] before = engine.renderFrames(44_100, 1.0f);
    byte[] after = engine.renderFrames(1, 1.0f);

    assertEquals(1000, leftSample(before, 44_099));
    assertEquals(3000, leftSample(after, 0));
    engine.close();
}
~~~

Add:

~~~java
@Test
void startsAllTracksWhoseDelayIsZeroOnTheFirstFrame() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("first.wav"), constantFrames(16, (short) 1000));
    writeWav(root.resolve("second.wav"), constantFrames(16, (short) 2000));
    PcmMixerEngine engine = new PcmMixerEngine(
            new AudioStreamFactory(), MusicLibrary.scan(root));
    engine.apply(1L, state(
            "area",
            false,
            track("first.wav", 0, false, 0, 0),
            track("second.wav", 0, false, 0, 0)
    ));

    assertEquals(3000, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.close();
}
~~~

Add an independent-volume mix assertion:

~~~java
@Test
void appliesEachTrackVolumeBeforeSaturatedMixing() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("first.wav"), constantFrames(16, (short) 10_000));
    writeWav(root.resolve("second.wav"), constantFrames(16, (short) 10_000));
    PcmMixerEngine engine = new PcmMixerEngine(
            new AudioStreamFactory(), MusicLibrary.scan(root));
    engine.apply(1L, PlaybackState.playing("area", List.of(
            new AreaTrackDefinition("first.wav", 0, 0.5f, false, 0, 0),
            new AreaTrackDefinition("second.wav", 0, 0.25f, false, 0, 0)
    ), false));

    assertEquals(7500, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.close();
}
~~~

Add these helpers once to `PcmMixerEngineTest` and use them in Tasks 6–8:

~~~java
private static AreaTrackDefinition track(
        String musicId,
        int delaySeconds,
        boolean loop,
        int fadeInMs,
        int fadeOutMs
) {
    return new AreaTrackDefinition(
            musicId, delaySeconds, 1.0f, loop, fadeInMs, fadeOutMs);
}

private static PlaybackState state(
        String areaId,
        boolean resumeOnReenter,
        AreaTrackDefinition... tracks
) {
    return PlaybackState.playing(areaId, List.of(tracks), resumeOnReenter);
}
~~~

- [ ] **Step 2: Write a failing isolated-error test**

~~~java
@Test
void oneMissingTrackDoesNotSilenceOtherTracks() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("good.wav"), constantFrames(32, (short) 2345));
    PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));
    engine.apply(1L, PlaybackState.playing("area", List.of(
            track("missing.wav", 0, false, 0, 0),
            track("good.wav", 0, false, 0, 0)
    ), false));

    byte[] rendered = engine.renderFrames(1, 1.0f);

    assertEquals(2345, leftSample(rendered, 0));
    assertEquals(List.of(AudioFailure.Kind.MISSING_FILE),
            engine.drainFailures().stream().map(AudioFailure::kind).toList());
    engine.close();
}
~~~

- [ ] **Step 3: Run focused tests and observe RED**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.PcmMixerEngineTest" --console=plain
~~~

Expected: compilation fails because revisioned `apply` and `drainFailures` do not exist; the temporary primary-track implementation also cannot produce 3000.

- [ ] **Step 4: Replace the single pending track with an active area session**

Use these engine entry points:

~~~java
public void apply(PlaybackState state) {
    apply(0L, state);
}

public void apply(long revision, PlaybackState state);
public byte[] renderFrames(int frameCount, float masterGain);
public List<AudioFailure> drainFailures();
public boolean hasWork();
public PlaybackState currentState();
~~~

The one-argument `apply` is a temporary compatibility bridge for `PcmAudioMixer` while Task 6 remains green. Delete it in Task 7 after `ClientAudioMixer` and `PcmAudioMixer` pass real revisions.

The current session owns:

~~~java
private static final class AreaSession {
    private final long revision;
    private final PlaybackState state;
    private final AreaPlaybackTimeline timeline;
    private final List<RuntimeTrack> tracks;
    private boolean outgoing;
    private boolean snapshotWhenSilent;
}
~~~

Render in exact-delay segments:

~~~java
int renderedFrames = 0;
while (renderedFrames < frameCount) {
    startDueTracks(currentSession);
    long untilStart = currentSession == null
            ? Long.MAX_VALUE
            : currentSession.timeline.framesUntilNextStart();
    int segmentFrames = (int) Math.min(
            frameCount - renderedFrames,
            Math.max(1L, untilStart)
    );
    mixSegment(output, renderedFrames, segmentFrames, masterGain);
    if (currentSession != null && !currentSession.outgoing) {
        currentSession.timeline.advancePending(segmentFrames);
    }
    renderedFrames += segmentFrames;
}
~~~

When `untilStart` is zero, call `startDueTracks` again before choosing a segment. Every successfully opened track calls `timeline.markStarted(index)`. Missing/open/decode failure calls `markFailed(index)` and appends one `AudioFailure`; it never throws out of the loop or removes another track.

Keep each `RuntimeTrack` keyed by JSON track index. Apply its own volume, loop, fade-in, and fade-out. Reset its timeline cursor on loop reopen and mark completion only for that index.

- [ ] **Step 5: Make the audio worker run during silent delays**

Replace `hasTracks` checks in `PcmAudioMixer` with `hasWork`. A playing current area counts as work even before its first delayed track; opening `SourceDataLine` and writing silent blocks paces the 44.1 kHz frame clock. A stopped state with no outgoing fades remains idle and closes the output.

After every `engine.apply` and `engine.renderFrames`, report each `engine.drainFailures()` item through the existing error listener.

- [ ] **Step 6: Run focused and full audio tests**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.PcmMixerEngineTest" --tests "datura.areamusic.client.audio.PcmAudioMixerTest" --console=plain
.\gradlew.bat test --console=plain
~~~

Expected: exact-delay, simultaneous mix, isolated failure, existing loop, crossfade, rapid transition, output retry, pause, and zero-read tests pass.

- [ ] **Step 7: Commit concurrent playback**

Run:

~~~powershell
git add src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java src/main/java/datura/areamusic/client/audio/PcmAudioMixer.java src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java src/test/java/datura/areamusic/client/audio/PcmAudioMixerTest.java
git commit -m "feat: mix delayed area tracks concurrently"
~~~

---

### Task 7: Persist resume snapshots and rebuild streams asynchronously

**Files:**
- Create: `src/main/java/datura/areamusic/client/audio/AudioStreamPreparer.java`
- Create: `src/test/java/datura/areamusic/client/audio/AudioStreamPreparerTest.java`
- Modify: `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java`
- Modify: `src/main/java/datura/areamusic/client/audio/PcmAudioMixer.java`
- Modify: `src/main/java/datura/areamusic/client/audio/ClientAudioMixer.java:6-13`
- Modify: `src/main/java/datura/areamusic/client/ClientPlaybackSession.java:9-105`
- Modify: `src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java`
- Modify: `src/test/java/datura/areamusic/client/ClientPlaybackSessionTest.java`

- [ ] **Step 1: Write failing false/true reentry tests**

Use zero fades and a WAV with distinct frames:

~~~java
@Test
void nonResumingAreaRestartsFromItsFirstFrame() throws Exception {
    PcmMixerEngine engine = engineWithWav("track.wav", new short[]{1000, 2000, 3000});
    PlaybackState state = state("area", false, track("track.wav", 0, false, 0, 0));
    engine.apply(1L, state);
    assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.apply(1L, PlaybackState.stopped());
    engine.renderFrames(1, 1.0f);
    engine.apply(1L, state);
    assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
}

@Test
void resumingAreaContinuesFromItsSavedFrame() throws Exception {
    PcmMixerEngine engine = engineWithWav("track.wav", new short[]{1000, 2000, 3000});
    PlaybackState state = state("area", true, track("track.wav", 0, false, 0, 0));
    engine.apply(1L, state);
    assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.apply(1L, PlaybackState.stopped());
    engine.renderFrames(1, 1.0f);
    engine.apply(1L, state);
    assertEquals(2000, firstNonSilentSample(engine));
}
~~~

Add:

~~~java
@Test
void resumingAreaPreservesRemainingDelay() throws Exception {
    PcmMixerEngine engine = engineWithWav(
            "delayed.wav", constantFrames(100_000, (short) 1234));
    PlaybackState state = state(
            "area", true, track("delayed.wav", 2, true, 0, 0));
    engine.apply(1L, state);
    engine.renderFrames(44_100, 1.0f);
    engine.apply(1L, PlaybackState.stopped());
    engine.apply(1L, state);

    assertEquals(0, leftSample(engine.renderFrames(44_099, 1.0f), 44_098));
    assertEquals(1234, firstNonSilentSample(engine));
    engine.close();
}

@Test
void completedNonLoopTrackStaysCompleteAfterResuming() throws Exception {
    PcmMixerEngine engine = engineWithWav("once.wav", new short[]{4321});
    PlaybackState state = state("area", true, track("once.wav", 0, false, 0, 0));
    engine.apply(1L, state);
    assertEquals(4321, firstLeftSample(engine.renderFrames(2, 1.0f)));
    engine.apply(1L, PlaybackState.stopped());
    engine.apply(1L, state);
    assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.close();
}

@Test
void loopingTrackRestoresItsCursorInsideTheCurrentPass() throws Exception {
    PcmMixerEngine engine = engineWithWav(
            "loop.wav", new short[]{1000, 2000, 3000});
    PlaybackState state = state("area", true, track("loop.wav", 0, true, 0, 0));
    engine.apply(1L, state);
    engine.renderFrames(1, 1.0f);
    engine.apply(1L, PlaybackState.stopped());
    engine.apply(1L, state);
    assertEquals(2000, firstNonSilentSample(engine));
    engine.close();
}

@Test
void successfulLibraryReplacementAndNewRevisionBothDiscardOldSnapshots() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("track.wav"), new short[]{1000, 2000, 3000});
    MusicLibrary library = MusicLibrary.scan(root);
    PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), library);
    PlaybackState state = state("area", true, track("track.wav", 0, false, 0, 0));
    engine.apply(1L, state);
    engine.renderFrames(1, 1.0f);
    engine.apply(1L, PlaybackState.stopped());

    engine.setMusicLibrary(library);
    engine.apply(1L, state);
    assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.apply(1L, PlaybackState.stopped());
    engine.apply(2L, state);
    assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.close();
}
~~~

In `ClientPlaybackSessionTest`, add a failure-path assertion:

~~~java
@Test
void failedReloadReappliesDesiredStateWithoutReplacingTheLibrary() {
    MusicLibrary library = MusicLibrary.empty(tempDir.resolve("areamusic"));
    FakeMixer mixer = new FakeMixer();
    ClientPlaybackSession session = new ClientPlaybackSession(library, ignored -> mixer);
    PlaybackState state = state("area", true, track("track.ogg", 0, true, 0, 0));
    session.connect();
    session.beginReload(9L);
    session.apply(9L, state);
    mixer.appliedStates.clear();
    mixer.appliedRevisions.clear();

    session.failReload();

    assertFalse(mixer.libraryUpdated);
    assertEquals(List.of(state), mixer.appliedStates);
    assertEquals(List.of(9L), mixer.appliedRevisions);
    session.close();
}

private static AreaTrackDefinition track(
        String musicId,
        int delaySeconds,
        boolean loop,
        int fadeInMs,
        int fadeOutMs
) {
    return new AreaTrackDefinition(
            musicId, delaySeconds, 1.0f, loop, fadeInMs, fadeOutMs);
}

private static PlaybackState state(
        String areaId,
        boolean resumeOnReenter,
        AreaTrackDefinition... tracks
) {
    return PlaybackState.playing(areaId, List.of(tracks), resumeOnReenter);
}
~~~

Extend `FakeMixer` with:

~~~java
private final List<Long> appliedRevisions = new ArrayList<>();

@Override
public void apply(long revision, PlaybackState state) {
    appliedRevisions.add(revision);
    appliedStates.add(state);
}
~~~

Add deterministic helpers:

~~~java
private PcmMixerEngine engineWithWav(String musicId, short[] frames) throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve(musicId), frames);
    return new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));
}

private static int firstNonSilentSample(PcmMixerEngine engine) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
        int sample = firstLeftSample(engine.renderFrames(1, 1.0f));
        if (sample != 0) {
            return sample;
        }
        Thread.sleep(1L);
    }
    throw new AssertionError("Restored stream did not become ready within two seconds");
}
~~~

- [ ] **Step 2: Write a failing fast-forward test**

~~~java
@Test
void preparesAStreamAtAnExactPcmFrameWithoutBlockingTheCaller() throws Exception {
    Path path = tempDir.resolve("areamusic/cursor.wav");
    writeWav(path, new short[]{100, 200, 300});
    try (AudioStreamPreparer preparer = new AudioStreamPreparer(new AudioStreamFactory(), 1)) {
        AudioInputStream stream = preparer.prepare(path, 2).get(2, TimeUnit.SECONDS);
        byte[] frame = stream.readNBytes(AudioStreamFactory.MIX_FORMAT.getFrameSize());
        assertEquals(300, PcmMath.readLittleEndian(frame, 0));
        stream.close();
    }
}

private static void writeWav(Path path, short[] monoSamples) throws Exception {
    Files.createDirectories(path.getParent());
    byte[] pcm = new byte[monoSamples.length * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
    for (int frame = 0; frame < monoSamples.length; frame++) {
        int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
        PcmMath.writeLittleEndian(pcm, offset, monoSamples[frame]);
        PcmMath.writeLittleEndian(pcm, offset + 2, monoSamples[frame]);
    }
    try (AudioInputStream stream = new AudioInputStream(
            new ByteArrayInputStream(pcm),
            AudioStreamFactory.MIX_FORMAT,
            monoSamples.length
    )) {
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
    }
}
~~~

- [ ] **Step 3: Run focused tests and observe RED**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.AudioStreamPreparerTest" --tests "datura.areamusic.client.audio.PcmMixerEngineTest" --tests "datura.areamusic.client.ClientPlaybackSessionTest" --console=plain
~~~

Expected: compilation fails because `AudioStreamPreparer` and revisioned mixer/session contracts are absent; the old engine restarts every state.

- [ ] **Step 4: Implement bounded asynchronous preparation**

Use a fixed daemon pool with the public API:

~~~java
public final class AudioStreamPreparer implements AutoCloseable {
    public AudioStreamPreparer(AudioStreamFactory streamFactory, int workerCount);
    public CompletableFuture<AudioInputStream> prepare(Path path, long frameOffset);
    @Override public void close();
}
~~~

`prepare` opens decoded PCM with `AudioStreamFactory.open`, advances exactly `frameOffset * MIX_FORMAT.getFrameSize()` bytes using `skip` with a read-and-discard fallback whenever `skip` returns zero, and closes the stream on cancellation or failure. Reject negative offsets and worker counts below one.

Give `PcmMixerEngine` these constructors so normal engines own their bounded preparer while focused tests may inject one:

~~~java
public PcmMixerEngine(AudioStreamFactory streamFactory, MusicLibrary musicLibrary) {
    this(
            streamFactory,
            musicLibrary,
            new AudioStreamPreparer(streamFactory, 2),
            true
    );
}

PcmMixerEngine(
        AudioStreamFactory streamFactory,
        MusicLibrary musicLibrary,
        AudioStreamPreparer streamPreparer
) {
    this(streamFactory, musicLibrary, streamPreparer, false);
}
~~~

The private four-argument constructor stores `ownsStreamPreparer`. `close` cancels all pending preparations and closes the preparer only when that flag is true.

- [ ] **Step 5: Store only logical snapshots after outgoing fades**

Add:

~~~java
private final Map<ResumeKey, SessionSnapshot> resumeSnapshots = new HashMap<>();

private record ResumeKey(long revision, String areaId) {}

private record SessionSnapshot(
        PlaybackState state,
        AreaPlaybackTimeline.Snapshot timeline
) {}
~~~

When an area exits, freeze pending delays immediately. Started tracks finish their individual fade-outs while cursor recording continues. Once all its runtime streams are silent, close them; if `resumeOnReenter` is true, store the snapshot, otherwise discard it.

On reentry with the same revision, area ID, and equal track definitions, restore the timeline. Started incomplete tracks request `AudioStreamPreparer.prepare(path, positionInLoopFrames)`; pending tracks retain their remaining delay; completed and failed entries remain silent. Poll completed futures from the audio thread and fade in only after attachment.

`setMusicLibrary` clears `resumeSnapshots` only after a successful client reload calls it.

- [ ] **Step 6: Pass revisions through the client mixer contract**

Change:

~~~java
void apply(long revision, PlaybackState state);
~~~

Update `ClientPlaybackSession` with these exact state-delivery rules:

~~~java
public void connect() {
    if (mixer != null) {
        return;
    }
    mixer = Objects.requireNonNull(mixerFactory.create(musicLibrary), "mixer");
    mixer.start();
    if (latestRevision >= 0L) {
        mixer.apply(latestRevision, desiredState);
    }
}

public void apply(long revision, PlaybackState state) {
    validateRevision(revision);
    if (revision < latestRevision) {
        return;
    }
    latestRevision = revision;
    desiredState = Objects.requireNonNull(state, "state");
    if (mixer != null && !reloadPending) {
        mixer.apply(revision, state);
    }
}

public void finishReload(MusicLibrary library) {
    musicLibrary = Objects.requireNonNull(library, "library");
    reloadPending = false;
    if (mixer != null) {
        mixer.updateMusicLibrary(library);
        if (latestRevision >= 0L) {
            mixer.apply(latestRevision, desiredState);
        }
    }
}

public void failReload() {
    reloadPending = false;
    if (mixer != null && latestRevision >= 0L) {
        mixer.apply(latestRevision, desiredState);
    }
}
~~~

`disconnect` retains its current reset of `desiredState`, `latestRevision`, and `reloadPending` before closing the mixer.

`PcmAudioMixer` stores a pending record containing revision and state:

~~~java
private record PlaybackUpdate(long revision, PlaybackState state) {}
~~~

It constructs the normal two-argument `PcmMixerEngine`, whose `close` releases the owned preparer after the audio thread stops.

- [ ] **Step 7: Run focused and full tests**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.AudioStreamPreparerTest" --tests "datura.areamusic.client.audio.PcmMixerEngineTest" --tests "datura.areamusic.client.ClientPlaybackSessionTest" --console=plain
.\gradlew.bat test --console=plain
~~~

Expected: resume true continues; resume false restarts; pending delays, loop cursor, completion, successful/failed reload semantics, and async exact-frame preparation all pass.

- [ ] **Step 8: Commit resume behavior**

Run:

~~~powershell
git add src/main/java/datura/areamusic/client src/test/java/datura/areamusic/client
git commit -m "feat: resume area timelines on reentry"
~~~

---

### Task 8: Preserve crossfades, same-ID continuity, and bounded rapid transitions

**Files:**
- Modify: `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java`
- Modify: `src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java`

- [ ] **Step 1: Write failing multi-entry continuity tests**

Add:

~~~java
@Test
void matchesImmediateSameMusicIdsByOccurrenceOrderAcrossAreas() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("shared.wav"), new short[]{1000, 2000, 3000});
    PcmMixerEngine engine = new PcmMixerEngine(
            new AudioStreamFactory(), MusicLibrary.scan(root));
    PlaybackState first = state("first", false,
            track("shared.wav", 0, false, 0, 0),
            track("shared.wav", 0, false, 0, 0));
    PlaybackState second = state("second", false,
            track("shared.wav", 0, false, 0, 0),
            track("shared.wav", 0, false, 0, 0));
    engine.apply(1L, first);
    assertEquals(2000, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.apply(1L, second);
    assertEquals(4000, firstLeftSample(engine.renderFrames(1, 1.0f)));
    engine.close();
}

@Test
void delayedDestinationTrackDoesNotStealAnOutgoingSameIdStream() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("shared.wav"), constantFrames(300_000, (short) 1000));
    PcmMixerEngine engine = new PcmMixerEngine(
            new AudioStreamFactory(), MusicLibrary.scan(root));
    engine.apply(1L, state("first", false, track("shared.wav", 0, true, 0, 0)));
    engine.renderFrames(1, 1.0f);
    engine.apply(1L, state("second", false, track("shared.wav", 5, true, 0, 1000)));
    byte[] firstSecond = engine.renderFrames(44_100, 1.0f);
    assertEquals(0, leftSample(firstSecond, 44_099));
    engine.close();
}
~~~

Add this rapid-session test and a package-private `liveSessionCount()` accessor:

~~~java
@Test
void fifthRapidAreaTransitionSoftFadesInsteadOfHardEvicting() throws Exception {
    Path root = tempDir.resolve("areamusic");
    writeWav(root.resolve("track0.wav"), constantFrames(4096, (short) 10_000));
    for (int index = 1; index < 5; index++) {
        writeWav(root.resolve("track" + index + ".wav"), constantFrames(4096, (short) 0));
    }
    PcmMixerEngine engine = new PcmMixerEngine(
            new AudioStreamFactory(), MusicLibrary.scan(root));
    for (int index = 0; index < 5; index++) {
        engine.apply(1L, state(
                "area" + index,
                false,
                track("track" + index + ".wav", 0, true, 0, 60_000)
        ));
        engine.renderFrames(1, 1.0f);
    }

    assertTrue(firstLeftSample(engine.renderFrames(1, 1.0f)) > 9_000);
    assertTrue(engine.liveSessionCount() <= 4);
    engine.close();
}
~~~

- [ ] **Step 2: Run focused tests and observe RED**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.PcmMixerEngineTest" --console=plain
~~~

Expected: at least the duplicate occurrence and rapid-session bound tests fail.

- [ ] **Step 3: Implement deterministic matching and bounded sessions**

Pair reusable streams by:

~~~java
private record TrackIdentity(String musicId, int occurrence) {}
~~~

Build occurrence counters in JSON order. Only destination tracks with zero remaining delay may claim an outgoing stream. Reassign the runtime stream to the destination index, update loop and gain target, and mark the destination timeline entry started. Never match a delayed destination.

Keep at most four live area sessions. On a fifth rapid transition, choose the quietest outgoing session, mark it expedited, and fade all its tracks to zero over 20 ms. Remove it only after silence, preserving the existing no-hard-cut property.

If a resumable area is reentered while its outgoing fade is still live, cancel `snapshotWhenSilent`, restore it as current, and retarget gains without closing or reopening streams.

- [ ] **Step 4: Run audio regressions and commit**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.client.audio.*" --console=plain
.\gradlew.bat test --console=plain
git add src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java
git commit -m "fix: preserve multitrack transition continuity"
~~~

Expected: all audio and full tests pass before commit.

---

### Task 9: Verify Forge integration and package the feature

**Files:**
- Create: `src/main/java/datura/areamusic/gametest/AreaMusicGameTests.java`
- Modify: `docs/superpowers/specs/2026-07-10-area-music-design.md`
- Modify: `docs/superpowers/specs/2026-07-10-area-music-neoforge-1.21.1-design.md`
- Modify: `docs/superpowers/plans/2026-07-10-area-music.md`
- Modify: `docs/superpowers/plans/2026-07-10-area-music-neoforge-1.21.1.md`

- [ ] **Step 1: Add a Forge GameTest smoke assertion**

Create:

~~~java
package datura.areamusic.gametest;

import datura.areamusic.AreaMusic;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(AreaMusic.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AreaMusicGameTests {
    private AreaMusicGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void multitrackServerSmokeTest(GameTestHelper helper) {
        List<AreaTrackDefinition> tracks = List.of(
                new AreaTrackDefinition("ambient.ogg", 0, 1.0f, true, 0, 0),
                new AreaTrackDefinition("voice.mp3", 5, 0.8f, false, 0, 1000)
        );
        AreaDefinition area = AreaDefinition.create(
                "smoke",
                ResourceLocation.tryParse("minecraft:overworld"),
                BlockPos.ZERO,
                BlockPos.ZERO,
                tracks,
                true,
                0
        );
        PlaybackState state = PlaybackState.fromArea(area);
        if (!state.tracks().equals(tracks)
                || !state.resumeOnReenter()
                || !"areamusic".equals(MusicDirectory.DIRECTORY_NAME)) {
            helper.fail("AreaMusic multitrack server model did not round-trip");
            return;
        }
        helper.succeed();
    }
}
~~~

This source contains no `net.minecraft.client` import.

- [ ] **Step 2: Compile and run the dedicated GameTest server**

Run:

~~~powershell
.\gradlew.bat compileJava runGameTestServer --console=plain
~~~

Expected: the server loads AreaMusic without client classes, the multitrack smoke test succeeds, and Gradle ends with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the complete Forge verification gate**

Run:

~~~powershell
.\gradlew.bat clean test build reobfJarJar runGameTestServer --console=plain
~~~

Expected: all unit tests and GameTests pass and `build/libs/areamusic-0.0.1-all.jar` exists.

- [ ] **Step 4: Inspect the packaged JAR and forbidden uppercase literals**

Run:

~~~powershell
& "$env:JAVA_HOME\bin\jar.exe" tf build/libs/areamusic-0.0.1-all.jar | Select-String 'datura/areamusic|javazoom|jorbis|jflac'
Get-ChildItem -Recurse -File -Path src | Select-String -Pattern 'resolve\("AreaMusic"\)|<gameDir>/AreaMusic|run/AreaMusic'
git diff --check
git status --short
~~~

Expected: AreaMusic classes and all decoder providers are packaged; no runtime path instruction or source literal creates uppercase `AreaMusic`; diff check is clean.

- [ ] **Step 5: Commit Forge integration evidence**

Run:

~~~powershell
git add src/main/java/datura/areamusic/gametest src/main/resources docs
git commit -m "test: verify Forge multitrack playback"
~~~

Do not stage `build`, `run`, logs, audio fixtures, or files from another worktree.

---

### Task 10: Port the feature to a clean NeoForge 1.21.1 worktree

**Files:**
- Create worktree: `F:\Dev\Minecraft Mods\AreaMusic\.worktrees\neoforge-1.21.1-multitrack`
- Create/modify the same loader-independent source and test paths listed in Tasks 2–8
- Modify: `src/main/java/datura/areamusic/network/ClientboundPlaybackState.java:12-65`
- Modify: `src/main/java/datura/areamusic/network/AreaMusicNetwork.java:12-40`
- Modify: `src/main/java/datura/areamusic/server/AreaMusicServer.java:44-242`
- Modify: `src/main/java/datura/areamusic/client/ClientAreaMusic.java:48-105`
- Modify: `src/main/java/datura/areamusic/gametest/AreaMusicGameTests.java`
- Modify: `src/test/java/datura/areamusic/playback/PlaybackStateTest.java`

- [ ] **Step 1: Create the isolated NeoForge feature branch**

From the repository root, run:

~~~powershell
git worktree add '.worktrees/neoforge-1.21.1-multitrack' -b rain/neoforge-1.21.1-multitrack neoforge-1.21.1
git -C '.worktrees/neoforge-1.21.1-multitrack' status --short --branch
~~~

Expected: the new worktree is clean on `rain/neoforge-1.21.1-multitrack`. The existing NeoForge worktree and dirty Forge worktree remain untouched.

- [ ] **Step 2: Select Java 21 and verify the NeoForge baseline**

Run in the new worktree:

~~~powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
.\gradlew.bat clean test build runGameTestServer --console=plain
~~~

Expected: Java 21.0.9 and `BUILD SUCCESSFUL`, including the existing OGG zero-read continuity tests and server reload smoke test.

- [ ] **Step 3: Bring the approved design document onto the NeoForge branch**

Run:

~~~powershell
$planCommit = git -C 'F:\Dev\Minecraft Mods\AreaMusic' log --reverse --format=%H -- 'docs/superpowers/plans/2026-07-22-area-music-multitrack-resume.md' | Select-Object -First 1
git cherry-pick d5f4159 $planCommit
~~~

Expected: the approved design and this implementation plan are added without production changes.

- [ ] **Step 4: Write the NeoForge multitrack codec test first**

Change NeoForge `PlaybackStateTest` to use this exact fixture:

~~~java
List<AreaTrackDefinition> tracks = List.of(
        new AreaTrackDefinition("village/day.mp3", 0, 0.75f, true, 1000, 2500),
        new AreaTrackDefinition("village/bell.ogg", 5, 0.5f, false, 0, 500)
);
PlaybackState state = PlaybackState.playing("square", tracks, true);
ClientboundPlaybackState message = new ClientboundPlaybackState(7L, state);
FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

ClientboundPlaybackState.STREAM_CODEC.encode(buffer, message);
ClientboundPlaybackState decoded = ClientboundPlaybackState.STREAM_CODEC.decode(buffer);

assertEquals(message, decoded);
assertEquals(0, buffer.readableBytes());
~~~

Add:

~~~java
assertThrows(IllegalArgumentException.class,
        () -> PlaybackState.playing("area", List.of(), false));
List<AreaTrackDefinition> tooMany = java.util.stream.IntStream.range(0, 17)
        .mapToObj(index -> new AreaTrackDefinition(
                "track-" + index + ".ogg", 0, 1.0f, true, 0, 0))
        .toList();
assertThrows(IllegalArgumentException.class,
        () -> PlaybackState.playing("area", tooMany, false));
~~~

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.playback.PlaybackStateTest" --console=plain
~~~

Expected: compilation fails on the old scalar `PlaybackState.playing` signature.

- [ ] **Step 5: Port loader-independent tests and confirm the wider NeoForge RED state**

Use `apply_patch` to add or update these test paths before their production counterparts:

~~~text
src/test/java/datura/areamusic/area/AreaTrackDefinitionTest.java
src/test/java/datura/areamusic/area/AreaDefinitionTest.java
src/test/java/datura/areamusic/area/AreaJsonCodecTest.java
src/test/java/datura/areamusic/area/AreaStorageTest.java
src/test/java/datura/areamusic/music/MusicDirectoryTest.java
src/test/java/datura/areamusic/client/audio/AreaPlaybackTimelineTest.java
src/test/java/datura/areamusic/client/audio/AudioStreamPreparerTest.java
src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java
src/test/java/datura/areamusic/client/audio/PcmAudioMixerTest.java
src/test/java/datura/areamusic/client/ClientPlaybackSessionTest.java
~~~

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.area.*" --tests "datura.areamusic.music.*" --tests "datura.areamusic.client.audio.AreaPlaybackTimelineTest" --console=plain
~~~

Expected: compilation fails on missing `AreaTrackDefinition`, `MusicDirectory`, `AreaPlaybackTimeline`, and `AudioStreamPreparer`.

- [ ] **Step 6: Port the loader-independent final APIs**

Apply the final source shapes defined in this plan to the NeoForge worktree with `apply_patch`:

~~~text
AreaTrackDefinition(musicId, delaySeconds, volume, loop, fadeInMs, fadeOutMs)
AreaDefinition(id, dimension, min, max, tracks, resumeOnReenter, priority)
PlaybackState(playing, areaId, tracks, resumeOnReenter)
ClientAudioMixer.apply(long revision, PlaybackState state)
PcmMixerEngine.apply(long revision, PlaybackState state)
~~~

Implement `MusicDirectory` with exact-name enumeration, temporary-sibling migration, atomic-move fallback, rollback, symlink rejection, and distinct-directory conflict rejection. Implement `AreaPlaybackTimeline` with one entry per JSON index, frame-based remaining delays, cursor/completion/failure flags, immutable snapshots, and stable due-index order. Implement `AudioStreamPreparer` with a bounded daemon executor and exact decoded-PCM frame skipping.

Port the multitrack engine state machine with a current session, at most four outgoing sessions, per-index runtime tracks, isolated failure draining, revision-plus-area snapshots, and asynchronous restored streams. Preserve NeoForge commit `594ca96` behavior in `RuntimeTrack.readFrames`: reset the zero-read counter after positive data or loop reopen, continue through up to 64 consecutive zero reads, and report a decode failure on the 65th.

Do not replace NeoForge build files, event imports, payload registration, reload status, or service-provider resources with Forge versions.

- [ ] **Step 7: Adapt the NeoForge StreamCodec**

Keep `CustomPacketPayload` and replace only scalar body encoding. The final codec order is:

~~~text
revision VarLong
playing boolean
areaId UTF(max 64) when playing
resumeOnReenter boolean when playing
trackCount VarInt (1..16) when playing
for each track:
  musicId UTF(max 1024)
  delaySeconds VarInt
  volume float
  loop boolean
  fadeInMs VarInt
  fadeOutMs VarInt
~~~

Before allocating, reject `trackCount < 1 || trackCount > AreaDefinition.MAX_TRACKS`. Decode exactly `trackCount` entries into an `ArrayList` using the field order above, then construct `PlaybackState.playing(areaId, tracks, resumeOnReenter)`. Set `AreaMusicNetwork.PROTOCOL_VERSION` to `"2"` and retain the current `PayloadRegistrar.playToClient` registration.

- [ ] **Step 8: Adapt NeoForge server/client integration without changing event APIs**

Use `MusicDirectory.canonicalPath` for empty snapshots and `MusicDirectory.prepare` inside background reload scans. Retain NeoForge `PlayerTickEvent.Post`, `ClientTickEvent.Post`, reload status records, and custom payload delivery. `/areamusic create` emits the same v2 single-track file as Forge.

Add this second method to the existing NeoForge `AreaMusicGameTests`, retaining its reload smoke test:

~~~java
@GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
public static void multitrackModelSmokeTest(GameTestHelper helper) {
    List<AreaTrackDefinition> tracks = List.of(
            new AreaTrackDefinition("ambient.ogg", 0, 1.0f, true, 0, 0),
            new AreaTrackDefinition("voice.mp3", 5, 0.8f, false, 0, 1000)
    );
    AreaDefinition area = AreaDefinition.create(
            "smoke",
            ResourceLocation.tryParse("minecraft:overworld"),
            BlockPos.ZERO,
            BlockPos.ZERO,
            tracks,
            true,
            0
    );
    PlaybackState state = PlaybackState.fromArea(area);
    if (!state.tracks().equals(tracks)
            || !state.resumeOnReenter()
            || !"areamusic".equals(MusicDirectory.DIRECTORY_NAME)) {
        helper.fail("AreaMusic multitrack model did not round-trip");
        return;
    }
    helper.succeed();
}
~~~

Add imports for `AreaDefinition`, `AreaTrackDefinition`, `MusicDirectory`, `PlaybackState`, `BlockPos`, `ResourceLocation`, and `java.util.List`. Keep the existing NeoForge GameTest annotations and imports unchanged.

- [ ] **Step 9: Run NeoForge focused and full tests**

Run:

~~~powershell
.\gradlew.bat test --tests "datura.areamusic.area.*" --tests "datura.areamusic.music.*" --tests "datura.areamusic.playback.*" --tests "datura.areamusic.client.audio.*" --console=plain
.\gradlew.bat test --console=plain
~~~

Expected: `BUILD SUCCESSFUL`; v1/v2 JSON, multitrack codec, directory migration, frame delays, resume snapshots, all four codecs, 48 kHz rate, and zero-read continuity pass.

- [ ] **Step 10: Commit the NeoForge port**

Run:

~~~powershell
git add src docs/superpowers/specs/2026-07-22-area-music-multitrack-resume-design.md
git commit -m "feat: add NeoForge multitrack area playback"
~~~

---

### Task 11: Run cross-version acceptance and package both releases

**Files:**
- Verify both feature worktrees and ignored runtime fixtures

- [ ] **Step 1: Build and GameTest NeoForge**

Run in the NeoForge feature worktree:

~~~powershell
.\gradlew.bat clean test build runGameTestServer --console=plain
~~~

Expected: `BUILD SUCCESSFUL` and `build/libs/areamusic-neoforge-1.21.1-0.0.1.jar` exists.

- [ ] **Step 2: Re-run the Forge release gate after the NeoForge port**

Run in the Forge feature worktree:

~~~powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build reobfJarJar runGameTestServer --console=plain
~~~

Expected: `BUILD SUCCESSFUL` and `build/libs/areamusic-0.0.1-all.jar` exists.

- [ ] **Step 3: Exercise one v1 and one v2 fixture with identical bytes on both versions**

Place byte-identical UTF-8 copies under the Forge and NeoForge ignored `run/config/areamusic/<saveId>/` directories. The v1 fixture is:

~~~json
{
  "schemaVersion": 1,
  "dimension": "minecraft:overworld",
  "pos1": { "x": 20, "y": 64, "z": 0 },
  "pos2": { "x": 30, "y": 80, "z": 10 },
  "musicId": "ambient.ogg"
}
~~~

The v2 fixture is:

~~~json
{
  "schemaVersion": 2,
  "dimension": "minecraft:overworld",
  "pos1": { "x": 0, "y": 64, "z": 0 },
  "pos2": { "x": 10, "y": 80, "z": 10 },
  "tracks": [
    { "musicId": "ambient.ogg" },
    {
      "musicId": "voice.mp3",
      "delaySeconds": 5,
      "volume": 0.8,
      "loop": false,
      "fadeInMs": 0,
      "fadeOutMs": 1000
    }
  ],
  "resumeOnReenter": true,
  "priority": 0
}
~~~

Launch each development client and verify: immediate ambience, voice at five seconds, simultaneous overlap, exit fade, remaining-delay resume, playback-cursor resume, non-loop completion staying complete, vanilla Music volume independence, master-volume muting, and no uppercase directory creation.

- [ ] **Step 4: Inspect both artifacts and compute hashes**

Run:

~~~powershell
Get-FileHash 'build/libs/areamusic-0.0.1-all.jar' -Algorithm SHA256
Get-FileHash '.worktrees/neoforge-1.21.1-multitrack/build/libs/areamusic-neoforge-1.21.1-0.0.1.jar' -Algorithm SHA256
~~~

Inspect both JAR listings for AreaMusic classes and decoder providers, and confirm no test fixtures are packaged.

- [ ] **Step 5: Verify repository hygiene**

Run:

~~~powershell
git status --short --branch
git -C '.worktrees/neoforge-1.21.1-multitrack' status --short --branch
git -C '.worktrees/forge-1.20.1-audio-fix' status --short --branch
git diff --check
git -C '.worktrees/neoforge-1.21.1-multitrack' diff --check
~~~

Expected: both feature branches are clean; the unrelated Forge worktree still contains exactly its original local changes; no generated artifact is tracked.

- [ ] **Step 6: Record final verification**

Add a short verification section to this plan containing the exact Gradle outcomes, artifact paths, sizes, and SHA-256 values. Commit it independently on each feature branch:

~~~powershell
git add docs/superpowers/plans/2026-07-22-area-music-multitrack-resume.md
git commit -m "docs: record multitrack verification"
$verificationCommit = git rev-parse HEAD
git -C '.worktrees/neoforge-1.21.1-multitrack' cherry-pick $verificationCommit
~~~

Do not push, merge, or create a pull request until the user chooses the integration path.
