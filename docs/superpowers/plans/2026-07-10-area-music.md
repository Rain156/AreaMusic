# AreaMusic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Forge 1.20.1 mod that assigns local audio files to server-controlled cuboid regions, persists regions per save, and crossfades music on the client.

**Architecture:** The physical server owns immutable music and area snapshots, resolves one active area per player, and sends playback state changes through a Forge SimpleChannel. Each physical client scans its own `AreaMusic` directory and streams decoded PCM through a dedicated Java Sound mixer. Core path, area, persistence, and fade logic remain independent enough for JUnit tests.

**Tech Stack:** Java 17, Forge 47.4.21, ForgeGradle 6, Gson, Brigadier, Forge SimpleChannel, Java Sound, MP3SPI 1.9.5.4, VorbisSPI 1.0.3.3, jFLAC 1.5.2, JUnit Jupiter 5.10.2.

---

## File Map

- `build.gradle`: add JUnit and Jar-in-Jar codec dependencies.
- `gradle.properties`: complete mod metadata.
- `src/main/java/datura/areamusic/AreaMusic.java`: minimal mod entry point.
- `src/main/java/datura/areamusic/area/AreaDefinition.java`: validated immutable area model.
- `src/main/java/datura/areamusic/area/AreaResolver.java`: deterministic overlap resolution.
- `src/main/java/datura/areamusic/area/AreaJsonCodec.java`: strict JSON parsing and formatting.
- `src/main/java/datura/areamusic/area/AreaStorage.java`: save ID, atomic writes, transactional loads.
- `src/main/java/datura/areamusic/music/MusicLibrary.java`: recursive safe file scan and MusicID completion snapshot.
- `src/main/java/datura/areamusic/playback/PlaybackState.java`: common network playback value.
- `src/main/java/datura/areamusic/network/AreaMusicNetwork.java`: channel and client-bound message registration.
- `src/main/java/datura/areamusic/server/AreaMusicServer.java`: lifecycle, reload, player cache, and state sync.
- `src/main/java/datura/areamusic/server/AreaMusicCommands.java`: `/areamusic create` and `/areamusic reload`.
- `src/main/java/datura/areamusic/client/ClientAreaMusic.java`: client library/controller lifecycle and packet targets.
- `src/main/java/datura/areamusic/client/audio/AudioStreamFactory.java`: Java Sound SPI decoding to common PCM.
- `src/main/java/datura/areamusic/client/audio/FadeEnvelope.java`: sample-accurate linear fade state.
- `src/main/java/datura/areamusic/client/audio/PcmMath.java`: saturating PCM mixing.
- `src/main/java/datura/areamusic/client/audio/PcmAudioMixer.java`: streaming tracks, looping, transitions, pause, cleanup.
- `src/main/resources/assets/areamusic/lang/en_us.json`: English feedback.
- `src/main/resources/assets/areamusic/lang/zh_cn.json`: Simplified Chinese feedback.
- `src/test/java/datura/areamusic/...`: focused unit tests matching each core component.

### Task 1: Replace the MDK Example and Configure Dependencies

**Files:**
- Modify: `build.gradle`
- Modify: `gradle.properties`
- Create: `src/main/java/datura/areamusic/AreaMusic.java`
- Delete: `src/main/java/datura/areamusic/Areamusic.java`
- Delete: `src/main/java/datura/areamusic/Config.java`

- [ ] **Step 1: Add the test and embedded-codec build configuration**

Add before `minecraft {`:

```groovy
jarJar.enable()
```

Add to `dependencies`:

```groovy
testImplementation platform('org.junit:junit-bom:5.10.2')
testImplementation 'org.junit.jupiter:junit-jupiter'

jarJar(group: 'com.googlecode.soundlibs', name: 'mp3spi', version: '1.9.5.4') {
    jarJar.pin(it, '[1.9.5.4,1.9.6)')
}
jarJar(group: 'com.googlecode.soundlibs', name: 'vorbisspi', version: '1.0.3.3') {
    jarJar.pin(it, '[1.0.3.3,1.0.4)')
}
jarJar(group: 'org.jflac', name: 'jflac-codec', version: '1.5.2') {
    jarJar.pin(it, '[1.5.2,1.6)')
}
```

Add after the Java compile configuration:

```groovy
tasks.named('test', Test).configure {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Replace the example entry point**

```java
package datura.areamusic;

import net.minecraftforge.fml.common.Mod;

@Mod(AreaMusic.MOD_ID)
public final class AreaMusic {
    public static final String MOD_ID = "areamusic";
}
```

- [ ] **Step 3: Resolve dependencies and compile the empty entry point**

Run: `.\gradlew.bat compileJava --stacktrace`

Expected: `BUILD SUCCESSFUL`; no example item, block, or config references remain.

- [ ] **Step 4: Commit the build baseline**

```powershell
git add build.gradle gradle.properties src/main/java/datura/areamusic
git commit --only -m "build: configure AreaMusic dependencies" -- build.gradle gradle.properties src/main/java/datura/areamusic
```

### Task 2: Implement Region Modeling and Resolution with TDD

**Files:**
- Create: `src/test/java/datura/areamusic/area/AreaDefinitionTest.java`
- Create: `src/test/java/datura/areamusic/area/AreaResolverTest.java`
- Create: `src/main/java/datura/areamusic/area/AreaDefinition.java`
- Create: `src/main/java/datura/areamusic/area/AreaResolver.java`

- [ ] **Step 1: Write failing boundary and priority tests**

Tests construct areas with reversed endpoints and assert inclusive boundaries, dimension isolation, priority descending, block volume ascending, and ID lexical fallback:

```java
AreaDefinition broad = AreaDefinition.create("broad", new ResourceLocation("minecraft:overworld"),
        new BlockPos(10, 80, 10), new BlockPos(0, 60, 0), "music/a.ogg", 5, 1.0f, true, 2000, 2000);
AreaDefinition narrow = AreaDefinition.create("narrow", new ResourceLocation("minecraft:overworld"),
        new BlockPos(2, 64, 2), new BlockPos(4, 66, 4), "music/b.ogg", 5, 1.0f, true, 2000, 2000);

assertTrue(broad.contains(new ResourceLocation("minecraft:overworld"), new BlockPos(0, 60, 0)));
assertEquals("narrow", AreaResolver.resolve(List.of(broad, narrow),
        new ResourceLocation("minecraft:overworld"), new BlockPos(3, 65, 3)).orElseThrow().id());
```

- [ ] **Step 2: Run tests and verify the model is absent**

Run: `.\gradlew.bat test --tests "datura.areamusic.area.*"`

Expected: compilation fails because `AreaDefinition` and `AreaResolver` do not exist.

- [ ] **Step 3: Implement validated immutable areas and comparator-based resolution**

Use this public contract:

```java
public record AreaDefinition(String id, ResourceLocation dimension, BlockPos min, BlockPos max,
                             String musicId, int priority, float volume, boolean loop,
                             int fadeInMs, int fadeOutMs) {
    public static AreaDefinition create(String id, ResourceLocation dimension, BlockPos pos1, BlockPos pos2,
                                        String musicId, int priority, float volume, boolean loop,
                                        int fadeInMs, int fadeOutMs);
    public boolean contains(ResourceLocation candidateDimension, BlockPos position);
    public long volumeInBlocks();
}

public final class AreaResolver {
    public static Optional<AreaDefinition> resolve(Collection<AreaDefinition> areas,
                                                   ResourceLocation dimension, BlockPos position);
}
```

Validate the ID regex, nonblank MusicID, finite volume in `[0,1]`, and fade ranges in `[0,60000]`. Normalize endpoints in `create`. Sort matching regions by priority descending, block volume ascending, then ID ascending.

- [ ] **Step 4: Run the area tests**

Run: `.\gradlew.bat test --tests "datura.areamusic.area.*"`

Expected: all tests pass.

- [ ] **Step 5: Commit the area core**

```powershell
git add src/main/java/datura/areamusic/area src/test/java/datura/areamusic/area
git commit --only -m "feat: model and resolve music areas" -- src/main/java/datura/areamusic/area src/test/java/datura/areamusic/area
```

### Task 3: Build the Music Library Scanner with TDD

**Files:**
- Create: `src/test/java/datura/areamusic/music/MusicLibraryTest.java`
- Create: `src/main/java/datura/areamusic/music/MusicLibrary.java`

- [ ] **Step 1: Write failing temporary-directory tests**

Create `village/day.mp3`, `ambient/Cave.OGG`, an unsupported text file, and a case-only duplicate. Assert recursive IDs use `/`, extensions are case-insensitive, maps are immutable, and case-only duplicates fail the scan.

```java
MusicLibrary library = MusicLibrary.scan(tempDir);
assertEquals(Set.of("ambient/Cave.OGG", "village/day.mp3"), library.ids());
assertThrows(MusicLibrary.ScanException.class, () -> MusicLibrary.scan(tempDirWithCaseConflict));
```

- [ ] **Step 2: Verify the scanner tests fail**

Run: `.\gradlew.bat test --tests "datura.areamusic.music.MusicLibraryTest"`

Expected: compilation fails because `MusicLibrary` does not exist.

- [ ] **Step 3: Implement a safe immutable scan**

Use this contract:

```java
public final class MusicLibrary {
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("ogg", "mp3", "wav", "flac");
    public static MusicLibrary empty(Path root);
    public static MusicLibrary scan(Path root) throws IOException, ScanException;
    public Path root();
    public Set<String> ids();
    public Optional<Path> find(String musicId);
    public boolean contains(String musicId);
}
```

Create the root directory, walk without following links, accept only regular non-link files, check normalized paths remain under the normalized root, sort by ID, and maintain a `Locale.ROOT` lowercase collision map.

- [ ] **Step 4: Run scanner tests and commit**

Run: `.\gradlew.bat test --tests "datura.areamusic.music.MusicLibraryTest"`

Expected: all tests pass.

```powershell
git add src/main/java/datura/areamusic/music src/test/java/datura/areamusic/music
git commit --only -m "feat: index external music files" -- src/main/java/datura/areamusic/music src/test/java/datura/areamusic/music
```

### Task 4: Implement Strict Per-Save JSON Storage with TDD

**Files:**
- Create: `src/test/java/datura/areamusic/area/AreaJsonCodecTest.java`
- Create: `src/test/java/datura/areamusic/area/AreaStorageTest.java`
- Create: `src/main/java/datura/areamusic/area/AreaJsonCodec.java`
- Create: `src/main/java/datura/areamusic/area/AreaStorage.java`

- [ ] **Step 1: Write failing codec and transactional-load tests**

Assert the documented JSON parses, missing optional playback fields receive defaults, unknown fields and invalid values fail with a filename-aware error, output round-trips, and a load containing one malformed file throws without returning a partial snapshot.

```java
AreaDefinition parsed = codec.read("square", validJson);
assertEquals(2000, parsed.fadeInMs());
assertEquals(parsed, codec.read("square", codec.write(parsed)));
assertThrows(AreaStorage.LoadException.class, () -> storage.load(library));
```

- [ ] **Step 2: Verify persistence tests fail**

Run: `.\gradlew.bat test --tests "datura.areamusic.area.AreaJsonCodecTest" --tests "datura.areamusic.area.AreaStorageTest"`

Expected: compilation fails because codec and storage classes do not exist.

- [ ] **Step 3: Implement the codec and storage contracts**

```java
public final class AreaJsonCodec {
    public AreaDefinition read(String areaId, Reader reader) throws JsonParseException;
    public String write(AreaDefinition area);
}

public final class AreaStorage {
    public static String saveId(String worldDirectoryName);
    public static Path directory(Path configRoot, String saveId);
    public List<AreaDefinition> load(MusicLibrary musicLibrary) throws IOException, LoadException;
    public void create(AreaDefinition area) throws IOException;
}
```

Parse through `JsonObject` so required and optional fields are explicit. Reject unknown keys, require `schemaVersion == 1`, and validate referenced MusicIDs against the candidate library. Write with `CREATE_NEW` to a sibling temporary file, then move using `ATOMIC_MOVE` with a replace-free fallback; delete the temporary file on failure.

- [ ] **Step 4: Run persistence tests and commit**

Run: `.\gradlew.bat test --tests "datura.areamusic.area.AreaJsonCodecTest" --tests "datura.areamusic.area.AreaStorageTest"`

Expected: all tests pass.

```powershell
git add src/main/java/datura/areamusic/area src/test/java/datura/areamusic/area
git commit --only -m "feat: persist areas per world" -- src/main/java/datura/areamusic/area src/test/java/datura/areamusic/area
```

### Task 5: Add Playback Messages, Server State, and Commands

**Files:**
- Create: `src/test/java/datura/areamusic/playback/PlaybackStateTest.java`
- Create: `src/main/java/datura/areamusic/playback/PlaybackState.java`
- Create: `src/main/java/datura/areamusic/network/AreaMusicNetwork.java`
- Create: `src/main/java/datura/areamusic/network/ClientboundPlaybackState.java`
- Create: `src/main/java/datura/areamusic/network/ClientboundReloadMusic.java`
- Create: `src/main/java/datura/areamusic/server/AreaMusicServer.java`
- Create: `src/main/java/datura/areamusic/server/AreaMusicCommands.java`
- Modify: `src/main/java/datura/areamusic/AreaMusic.java`

- [ ] **Step 1: Write failing playback-state validation and buffer round-trip tests**

```java
PlaybackState state = PlaybackState.playing("square", "village/day.mp3", 0.75f, true, 1000, 2500);
FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
ClientboundPlaybackState.encode(new ClientboundPlaybackState(7, state), buffer);
assertEquals(state, ClientboundPlaybackState.decode(buffer).state());
```

- [ ] **Step 2: Implement common playback values and client-bound packets**

```java
public record PlaybackState(boolean playing, String areaId, String musicId, float volume,
                            boolean loop, int fadeInMs, int fadeOutMs) {
    public static PlaybackState stopped();
    public static PlaybackState playing(String areaId, String musicId, float volume,
                                        boolean loop, int fadeInMs, int fadeOutMs);
}
```

Encode bounded UTF strings, floats, booleans, and VarInts. Register both packets as `PLAY_TO_CLIENT` on protocol `1`. Packet consumers enqueue work and use `DistExecutor` before touching client classes.

Register the channel from the mod constructor only after `AreaMusicNetwork` exists:

```java
public AreaMusic() {
    AreaMusicNetwork.register();
}
```

- [ ] **Step 3: Register server events and the command tree**

Build this tree with permission level 2:

```text
areamusic
  create <areaId:string> <pos1:block_pos> <pos2:block_pos> <musicId:string>
  reload
```

`musicId` suggestions come from the current immutable server `MusicLibrary`. `create` validates, writes asynchronously, then applies a new area snapshot on the server executor. `reload` constructs both candidate snapshots on a background executor and swaps them only when both succeed.

- [ ] **Step 4: Implement player change caching and sync**

Cache `(dimension, blockPos, revision, playbackState)` by player UUID. On end-phase server player ticks, skip unchanged inputs; otherwise resolve the active area. Send only changed `PlaybackState` values. Force checks on login, respawn, dimension change, successful reload, and area creation; remove cache entries on logout and server stop.

- [ ] **Step 5: Run unit tests and compile Forge integration**

Run: `.\gradlew.bat test --tests "datura.areamusic.playback.*" compileJava`

Expected: tests pass and Forge event/command/network APIs compile.

- [ ] **Step 6: Commit server integration**

```powershell
git add src/main/java/datura/areamusic/playback src/main/java/datura/areamusic/network src/main/java/datura/areamusic/server src/test/java/datura/areamusic/playback
git commit --only -m "feat: sync area playback from server" -- src/main/java/datura/areamusic/playback src/main/java/datura/areamusic/network src/main/java/datura/areamusic/server src/test/java/datura/areamusic/playback
```

### Task 6: Implement Deterministic Fade and PCM Math with TDD

**Files:**
- Create: `src/test/java/datura/areamusic/client/audio/FadeEnvelopeTest.java`
- Create: `src/test/java/datura/areamusic/client/audio/PcmMathTest.java`
- Create: `src/main/java/datura/areamusic/client/audio/FadeEnvelope.java`
- Create: `src/main/java/datura/areamusic/client/audio/PcmMath.java`

- [ ] **Step 1: Write failing sample-accurate tests**

At 1000 frames per second, a 1000 ms fade from 0 to 1 must return 0 at the start, 0.5 after 500 frames, and 1 after 1000 frames. PCM sums must saturate to `Short.MIN_VALUE` and `Short.MAX_VALUE` rather than wrap.

```java
FadeEnvelope fade = new FadeEnvelope(0.0f);
fade.fadeTo(1.0f, 1000, 1000);
fade.advance(500);
assertEquals(0.5f, fade.value(), 0.001f);
assertEquals(Short.MAX_VALUE, PcmMath.saturate(40000));
```

- [ ] **Step 2: Verify tests fail, implement, and rerun**

Run before implementation: `.\gradlew.bat test --tests "datura.areamusic.client.audio.*"`

Expected: compilation fails because the classes are absent.

Implement `FadeEnvelope` with start, target, total frames, and elapsed frames; implement `PcmMath.saturate(int)` and little-endian 16-bit sample helpers.

Run after implementation: `.\gradlew.bat test --tests "datura.areamusic.client.audio.*"`

Expected: all tests pass.

- [ ] **Step 3: Commit audio primitives**

```powershell
git add src/main/java/datura/areamusic/client/audio src/test/java/datura/areamusic/client/audio
git commit --only -m "feat: add deterministic audio fades" -- src/main/java/datura/areamusic/client/audio src/test/java/datura/areamusic/client/audio
```

### Task 7: Build the Streaming Client Mixer

**Files:**
- Create: `src/main/java/datura/areamusic/client/audio/AudioStreamFactory.java`
- Create: `src/main/java/datura/areamusic/client/audio/PcmAudioMixer.java`
- Create: `src/main/java/datura/areamusic/client/ClientAreaMusic.java`

- [ ] **Step 1: Implement normalized streaming decode**

Use one target format for every track:

```java
public static final AudioFormat MIX_FORMAT = new AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, 44_100.0f, 16, 2, 4, 44_100.0f, false);

public AudioInputStream open(Path path) throws UnsupportedAudioFileException, IOException {
    AudioInputStream encoded = AudioSystem.getAudioInputStream(path.toFile());
    if (!AudioSystem.isConversionSupported(MIX_FORMAT, encoded.getFormat())) {
        encoded.close();
        throw new UnsupportedAudioFileException("Cannot convert " + encoded.getFormat() + " to " + MIX_FORMAT);
    }
    return AudioSystem.getAudioInputStream(MIX_FORMAT, encoded);
}
```

- [ ] **Step 2: Implement the owned mixer thread and track lifecycle**

`PcmAudioMixer` owns a daemon thread, one `SourceDataLine`, an `AtomicReference<PlaybackState>` for latest-state coalescing, volatile master gain/pause values, and a bounded list of streaming tracks. Process 1024 frames per block. Reopen a stream at EOF only when `loop` is true. Remove silent completed tracks and close all resources during shutdown.

- [ ] **Step 3: Implement transition behavior**

When applying a different MusicID, fade every current track to zero using the previous state's `fadeOutMs`, open the new track at zero, and fade it to region volume using the new state's `fadeInMs`. For the same MusicID, retain the stream and adjust its target volume. For stopped state, fade current tracks to zero. A non-looping completed track remains marked complete until a different state is observed.

- [ ] **Step 4: Add client lifecycle and error delivery**

Client setup creates and scans `<gameDir>/AreaMusic`. Client ticks update `master * music` gain and real pause state. Login/reload rescans transactionally. Logout and game shutdown stop the mixer. Missing/invalid audio posts one translatable client message per error key per successful reload and logs the exception.

- [ ] **Step 5: Compile client-only integration and commit**

Run: `.\gradlew.bat compileJava test`

Expected: client classes compile without being referenced from dedicated-server execution paths; all unit tests pass.

```powershell
git add src/main/java/datura/areamusic/client
git commit --only -m "feat: stream and crossfade local music" -- src/main/java/datura/areamusic/client
```

### Task 8: Finish Metadata, Localization, and Runtime Safety

**Files:**
- Modify: `src/main/resources/META-INF/mods.toml`
- Create: `src/main/resources/assets/areamusic/lang/en_us.json`
- Create: `src/main/resources/assets/areamusic/lang/zh_cn.json`
- Modify: `src/main/java/datura/areamusic/AreaMusic.java`

- [ ] **Step 1: Add complete translatable feedback**

Define keys for directory creation, reload started/succeeded/failed, create succeeded/duplicate/invalid MusicID, missing client file, decode failure, and audio device failure. Use `Component.translatable` for all player-facing text and keep detailed exceptions in logs.

- [ ] **Step 2: Complete mod metadata**

Set author to `Datura`, describe external per-area music, and remove template commentary that no longer documents behavior. Keep dependencies on Forge 47+ and Minecraft 1.20.1.

- [ ] **Step 3: Verify the dedicated-server classpath**

Run: `.\gradlew.bat runServer --args "--help"`

Expected: Forge reaches server argument handling without `net.minecraft.client` or Java Sound client initialization errors. If the run task does not honor `--help`, use `.\gradlew.bat runGameTestServer` and confirm AreaMusic loads before the expected no-tests exit.

- [ ] **Step 4: Commit resources and metadata**

```powershell
git add src/main/resources src/main/java/datura/areamusic/AreaMusic.java
git commit --only -m "chore: finish AreaMusic metadata and messages" -- src/main/resources src/main/java/datura/areamusic/AreaMusic.java
```

### Task 9: Full Verification and Artifact Inspection

**Files:**
- Modify only files implicated by verification failures.

- [ ] **Step 1: Run the complete automated suite**

Run: `.\gradlew.bat clean test build --stacktrace`

Expected: `BUILD SUCCESSFUL` with all JUnit tests passing and a reobfuscated Jar-in-Jar artifact under `build/libs`.

- [ ] **Step 2: Inspect the built artifact**

Run: `jar tf build\libs\areamusic-0.0.1-all.jar`

Expected: `META-INF/jarjar/metadata.json` and nested codec jars are present. If ForgeGradle uses a different Jar-in-Jar filename, inspect the artifact produced by the `jarJar` task and use that exact path.

- [ ] **Step 3: Run a development-client smoke test**

Place short OGG, MP3, WAV, and FLAC fixtures under `run/AreaMusic`, launch `.\gradlew.bat runClient`, create two adjacent regions, and verify looping, exit fade, crossfade, same-ID continuity, priority, and reload. The client must also show a clear one-time message when a referenced local file is removed.

- [ ] **Step 4: Record final repository state**

Run: `git status --short` and `git log --oneline -10`.

Expected: only intentionally untracked local run artifacts remain; source, tests, resources, specification, and plan are represented in commits.
