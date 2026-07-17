# OGG Zero-Read Continuity Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent temporary zero-length Vorbis decoder reads from becoming audible silent mixer blocks while confirming FLAC and WAV do not exhibit the same continuity fault.

**Architecture:** Keep all decoder selection and sample-rate conversion in `AudioStreamFactory` unchanged. Make `PcmMixerEngine.Track.readFrames` guarantee progress with a bounded consecutive-zero retry policy, preserving its existing EOF and loop handling. Prove the behavior first with deterministic scripted streams and the real bundled OGG fixture, then audit full-stream FLAC and WAV reads.

**Tech Stack:** Java 17/21, Java Sound `AudioInputStream`, VorbisSPI 1.0.3.3, JOrbis 0.0.17 supplied by Minecraft, JUnit Jupiter 5.10.2, ForgeGradle 6, NeoForge ModDevGradle 2.0.141, Gradle.

---

## File Structure

- `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java`: owns block filling, EOF, looping, and the new bounded zero-read retry rule.
- `src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java`: deterministic zero-read tests plus a real OGG rendered-duration regression.
- `src/test/java/datura/areamusic/client/audio/CompressedAudioFormatsTest.java`: full-stream FLAC continuity audit; existing MP3 tests remain unchanged.
- `src/test/java/datura/areamusic/client/audio/AudioStreamFactoryTest.java`: full-stream WAV continuity audit.
- `docs/superpowers/specs/2026-07-17-ogg-zero-read-continuity-design.md`: approved behavior and scope.

No new production type or decoder dependency is required.

### Task 1: Add failing mixer regressions for temporary zero reads

**Files:**
- Modify: `src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java`
- Test resource: `src/test/resources/datura/areamusic/audio/test.ogg`

- [ ] **Step 1: Add imports for scripted streams, the real fixture, and exception assertions**

Add these imports to `PcmMixerEngineTest`:

```java
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
```

- [ ] **Step 2: Add a failing test that requires temporary zero reads to be retried**

Add this test to `PcmMixerEngineTest`:

```java
@Test
void temporaryZeroReadsDoNotInsertSilentMixerBlocks() throws Exception {
    Path root = tempDir.resolve("zero-read-music");
    Path path = root.resolve("track.wav");
    Files.createDirectories(root);
    Files.write(path, new byte[]{0});

    byte[] pcm = new byte[2 * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
    PcmMath.writeLittleEndian(pcm, 0, 1234);
    PcmMath.writeLittleEndian(pcm, 2, 1234);
    PcmMath.writeLittleEndian(pcm, 4, -2345);
    PcmMath.writeLittleEndian(pcm, 6, -2345);
    AudioInputStream stream = new ZeroReadAudioInputStream(3, false, pcm);

    try (PcmMixerEngine engine = new PcmMixerEngine(
            new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
        engine.apply(PlaybackState.playing("area", "track.wav", 1.0f, false, 0, 0));

        byte[] rendered = engine.renderFrames(2, 1.0f);

        assertEquals(1234, leftSample(rendered, 0));
        assertEquals(-2345, leftSample(rendered, 1));
    }
}
```

- [ ] **Step 3: Add a failing test that bounds a permanently stalled decoder**

Add this test:

```java
@Test
void permanentlyZeroReadingStreamFailsInsteadOfSpinningForever() throws Exception {
    Path root = tempDir.resolve("stalled-music");
    Path path = root.resolve("track.wav");
    Files.createDirectories(root);
    Files.write(path, new byte[]{0});
    AudioInputStream stream = new ZeroReadAudioInputStream(0, true, new byte[0]);

    try (PcmMixerEngine engine = new PcmMixerEngine(
            new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
        engine.apply(PlaybackState.playing("area", "track.wav", 1.0f, false, 0, 0));

        PcmMixerEngine.AudioPlaybackException error = assertThrows(
                PcmMixerEngine.AudioPlaybackException.class,
                () -> engine.renderFrames(1, 1.0f)
        );

        assertEquals(AudioFailure.Kind.DECODE, error.failure().kind());
        assertTrue(error.getCause() instanceof IOException);
    }
}
```

- [ ] **Step 4: Add a real OGG regression that detects duration inflation from silent blocks**

Add this test:

```java
@Test
void temporaryVorbisZeroReadsDoNotExtendRenderedDuration() throws Exception {
    URL resource = getClass().getResource("/datura/areamusic/audio/test.ogg");
    assertNotNull(resource);
    Path root = tempDir.resolve("ogg-music");
    Path path = root.resolve("track.ogg");
    Files.createDirectories(root);
    try (InputStream input = resource.openStream()) {
        Files.copy(input, path);
    }

    long decodedFrames = countDecodedFrames(path);
    long expectedBlocks = (decodedFrames + 1023L) / 1024L;
    long renderedBlocks = 0;
    try (PcmMixerEngine engine = new PcmMixerEngine(
            new AudioStreamFactory(), MusicLibrary.scan(root))) {
        engine.apply(PlaybackState.playing("area", "track.ogg", 1.0f, false, 0, 0));
        while (engine.hasTracks()) {
            engine.renderFrames(1024, 1.0f);
            renderedBlocks++;
            assertTrue(renderedBlocks <= expectedBlocks + 64L);
        }
    }

    assertEquals(expectedBlocks, renderedBlocks);
}
```

- [ ] **Step 5: Add deterministic test helpers**

Add the following helpers near the bottom of `PcmMixerEngineTest`:

```java
private static long countDecodedFrames(Path path) throws Exception {
    try (AudioInputStream stream = new AudioStreamFactory().open(path)) {
        byte[] buffer = new byte[4096];
        long bytes = 0;
        int consecutiveZeroReads = 0;
        while (true) {
            int read = stream.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                consecutiveZeroReads++;
                assertTrue(consecutiveZeroReads <= 64);
                continue;
            }
            consecutiveZeroReads = 0;
            assertEquals(0, read % AudioStreamFactory.MIX_FORMAT.getFrameSize());
            bytes += read;
        }
        return bytes / AudioStreamFactory.MIX_FORMAT.getFrameSize();
    }
}

private static final class FixedAudioStreamFactory extends AudioStreamFactory {
    private final AudioInputStream stream;

    private FixedAudioStreamFactory(AudioInputStream stream) {
        this.stream = stream;
    }

    @Override
    public AudioInputStream open(Path ignored) {
        return stream;
    }
}

private static final class ZeroReadAudioInputStream extends AudioInputStream {
    private int zeroReadsRemaining;
    private final boolean alwaysZero;

    private ZeroReadAudioInputStream(int zeroReadsRemaining, boolean alwaysZero, byte[] pcm) {
        super(
                new ByteArrayInputStream(pcm),
                AudioStreamFactory.MIX_FORMAT,
                pcm.length / AudioStreamFactory.MIX_FORMAT.getFrameSize()
        );
        this.zeroReadsRemaining = zeroReadsRemaining;
        this.alwaysZero = alwaysZero;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        if (alwaysZero || zeroReadsRemaining-- > 0) {
            return 0;
        }
        return super.read(destination, offset, length);
    }
}
```

- [ ] **Step 6: Run the focused test and verify RED**

From `F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-1.20.1`, run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests datura.areamusic.client.audio.PcmMixerEngineTest
```

Expected: the task fails. The temporary-zero test receives zero samples, the permanently-zero test reports that no exception was thrown, and the real OGG test observes more rendered blocks than the decoded frame count requires.

### Task 2: Implement bounded zero-read retries

**Files:**
- Modify: `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java`
- Test: `src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java`

- [ ] **Step 1: Add the retry bound beside the mixer constants**

Add this constant after `OVERFLOW_FADE_MS`:

```java
private static final int MAX_CONSECUTIVE_ZERO_READS = 64;
```

- [ ] **Step 2: Replace the zero-read break with bounded retry behavior**

Change `Track.readFrames` to this implementation:

```java
private int readFrames(byte[] destination, int requestedFrames) throws Exception {
    int requestedBytes = requestedFrames * FRAME_SIZE;
    int totalBytes = 0;
    int consecutiveZeroReads = 0;
    boolean reopenedWithoutData = false;
    while (totalBytes < requestedBytes) {
        int read = stream.read(destination, totalBytes, requestedBytes - totalBytes);
        if (read > 0) {
            totalBytes += read;
            consecutiveZeroReads = 0;
            reopenedWithoutData = false;
            continue;
        }
        if (read == 0) {
            consecutiveZeroReads++;
            if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                throw new IOException(
                        "Decoder made no progress for " + musicId + " after "
                                + consecutiveZeroReads + " consecutive reads"
                );
            }
            continue;
        }
        if (!loop || reopenedWithoutData) {
            exhausted = true;
            break;
        }
        reopen();
        consecutiveZeroReads = 0;
        reopenedWithoutData = true;
    }
    return totalBytes / FRAME_SIZE;
}
```

- [ ] **Step 3: Run the focused test and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.client.audio.PcmMixerEngineTest
```

Expected: all `PcmMixerEngineTest` cases pass. The real OGG test completes without extra rendered blocks and the permanently stalled stream produces a bounded decode error.

- [ ] **Step 4: Commit the regression and minimal implementation**

```powershell
git add -- src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java
git commit -m "fix: retry temporary zero-length audio reads"
```

### Task 3: Audit FLAC and WAV full-stream continuity

**Files:**
- Modify: `src/test/java/datura/areamusic/client/audio/CompressedAudioFormatsTest.java`
- Modify: `src/test/java/datura/areamusic/client/audio/AudioStreamFactoryTest.java`
- Test resource: `src/test/resources/datura/areamusic/audio/test.flac`

- [ ] **Step 1: Add a full-stream FLAC progress test**

Add `assertNotEquals` to the static imports in `CompressedAudioFormatsTest`, then add:

```java
@Test
void flacDecoderMakesContinuousFrameAlignedProgress() throws Exception {
    URL resource = getClass().getResource("/datura/areamusic/audio/test.flac");
    assertNotNull(resource);
    Path source = tempDir.resolve("continuity.flac");
    try (InputStream input = resource.openStream()) {
        Files.copy(input, source);
    }

    long bytes = 0;
    try (AudioInputStream decoded = new AudioStreamFactory().open(source)) {
        byte[] buffer = new byte[4096];
        while (true) {
            int read = decoded.read(buffer);
            if (read < 0) {
                break;
            }
            assertNotEquals(0, read);
            assertEquals(0, read % decoded.getFormat().getFrameSize());
            bytes += read;
        }
    }
    assertTrue(bytes > 0);
}
```

- [ ] **Step 2: Upgrade the WAV test from one frame to a full-stream continuity assertion**

Add `assertNotEquals` to the static imports in `AudioStreamFactoryTest`. Replace the final decode block in `convertsMonoLowRateWavToTheMixerFormat` with:

```java
try (AudioInputStream decoded = new AudioStreamFactory().open(wav)) {
    assertEquals(AudioStreamFactory.MIX_FORMAT, decoded.getFormat());
    byte[] buffer = new byte[4096];
    long bytes = 0;
    while (true) {
        int read = decoded.read(buffer);
        if (read < 0) {
            break;
        }
        assertNotEquals(0, read);
        assertEquals(0, read % decoded.getFormat().getFrameSize());
        bytes += read;
    }
    assertTrue(bytes > 0);
}
```

- [ ] **Step 3: Run all focused non-MP3 continuity tests**

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.client.audio.PcmMixerEngineTest --tests datura.areamusic.client.audio.CompressedAudioFormatsTest --tests datura.areamusic.client.audio.AudioStreamFactoryTest
```

Expected: all focused tests pass. OGG no longer gains silent blocks; FLAC and WAV make positive, frame-aligned progress to EOF. Existing MP3 tests also run unchanged inside `CompressedAudioFormatsTest`.

- [ ] **Step 4: Commit the other-format audit**

```powershell
git add -- src/test/java/datura/areamusic/client/audio/CompressedAudioFormatsTest.java src/test/java/datura/areamusic/client/audio/AudioStreamFactoryTest.java
git commit -m "test: audit non-MP3 audio continuity"
```

### Task 4: Verify the complete NeoForge build and artifact

**Files:**
- Verify: `build/libs/areamusic-neoforge-1.21.1-0.0.1.jar`

- [ ] **Step 1: Run a clean NeoForge build**

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build
```

Expected: `BUILD SUCCESSFUL`, all JUnit tests pass, and `verifyBundledAudioCodecs` passes.

- [ ] **Step 2: Confirm the production decoder path was not changed**

```powershell
git diff 9b4b2ba -- src/main/java/datura/areamusic/client/audio/AudioStreamFactory.java
```

Expected: no output. MP3 reader selection and conversion code remain byte-for-byte unchanged.

- [ ] **Step 3: Inspect the distributable and record its hash**

```powershell
$jar='build\libs\areamusic-neoforge-1.21.1-0.0.1.jar'
& 'F:\Dev\Java\jdk-21.0.9\bin\jar.exe' tf $jar | Select-String 'PcmMixerEngine|META-INF/jarjar/.+\.jar|com/jcraft/jorbis'
Get-FileHash -Algorithm SHA256 -LiteralPath $jar
```

Expected: `PcmMixerEngine` classes are present, no nested JAR entry is reported, no bundled JOrbis class is reported, and a SHA-256 hash is printed.

### Task 5: Apply the verified commits to Forge 1.20.1

**Files:**
- Modify via cherry-pick: `src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java`
- Modify via cherry-pick: `src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java`
- Modify via cherry-pick: `src/test/java/datura/areamusic/client/audio/CompressedAudioFormatsTest.java`
- Modify via cherry-pick: `src/test/java/datura/areamusic/client/audio/AudioStreamFactoryTest.java`

- [ ] **Step 1: Resolve the two implementation commits by their exact messages**

From `F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-final-verify`, run:

```powershell
$neo='F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-1.20.1'
$fixCommit=git -C $neo log -1 --format=%H --grep='^fix: retry temporary zero-length audio reads$'
$auditCommit=git -C $neo log -1 --format=%H --grep='^test: audit non-MP3 audio continuity$'
if (-not $fixCommit -or -not $auditCommit) { throw 'Expected NeoForge implementation commits were not found' }
Write-Output $fixCommit
Write-Output $auditCommit
```

Expected: two full commit hashes are printed.

- [ ] **Step 2: Cherry-pick the focused commits onto the clean Forge fix branch**

```powershell
git cherry-pick $fixCommit
git cherry-pick $auditCommit
```

Expected: both cherry-picks complete without conflicts. Do not switch to or modify the separate dirty `forge-1.20.1-audio-fix` worktree.

- [ ] **Step 3: Run focused Forge tests**

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests datura.areamusic.client.audio.PcmMixerEngineTest --tests datura.areamusic.client.audio.CompressedAudioFormatsTest --tests datura.areamusic.client.audio.AudioStreamFactoryTest
```

Expected: all focused audio tests pass on Java 17.

- [ ] **Step 4: Run a clean Forge build**

```powershell
.\gradlew.bat clean build
```

Expected: `BUILD SUCCESSFUL`, all JUnit tests pass, and `verifyBundledAudioCodecs` passes.

- [ ] **Step 5: Inspect the Forge distributable and record its hash**

```powershell
$jar='build\libs\areamusic-0.0.1.jar'
& 'F:\Dev\Java\jdk-17.0.12\bin\jar.exe' tf $jar | Select-String 'PcmMixerEngine|META-INF/jarjar/.+\.jar|com/jcraft/jorbis'
Get-FileHash -Algorithm SHA256 -LiteralPath $jar
```

Expected: the mixer classes are present, no nested JAR or bundled JOrbis class is reported, and a SHA-256 hash is printed.

### Task 6: Final cross-version review and handoff

**Files:**
- Verify: NeoForge and Forge source/test files listed above
- Verify: both local distributable JARs

- [ ] **Step 1: Confirm the committed behavioral changes match across branches**

From either worktree, compare the loader-neutral committed blobs and the stable patch IDs for both focused changes:

```powershell
$neo='F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-1.20.1'
$forge='F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-final-verify'
$paths=@(
  'src/main/java/datura/areamusic/client/audio/PcmMixerEngine.java',
  'src/test/java/datura/areamusic/client/audio/PcmMixerEngineTest.java',
  'src/test/java/datura/areamusic/client/audio/AudioStreamFactoryTest.java'
)
foreach ($path in $paths) {
  $neoBlob=git -C $neo rev-parse "HEAD:$path"
  if ($LASTEXITCODE -ne 0) { throw "Could not resolve NeoForge blob: $path" }
  $forgeBlob=git -C $forge rev-parse "HEAD:$path"
  if ($LASTEXITCODE -ne 0) { throw "Could not resolve Forge blob: $path" }
  if ($neoBlob -ne $forgeBlob) { throw "Cross-version committed blob mismatch: $path" }
}

function Get-StablePatchId([string]$repo, [string]$commit) {
  $result=git -C $repo show --pretty=format: --no-ext-diff $commit | git patch-id --stable
  if ($LASTEXITCODE -ne 0 -or -not $result) { throw "Could not calculate stable patch ID for $commit" }
  return ($result -split '\s+')[0]
}

$patchPairs=@(
  @{ Label='OGG zero-read fix'; Neo='594ca967'; Forge='68dcc6e' },
  @{ Label='non-MP3 continuity audit'; Neo='6c1e5c0'; Forge='6bf641e' }
)
foreach ($pair in $patchPairs) {
  $neoPatchId=Get-StablePatchId $neo $pair.Neo
  $forgePatchId=Get-StablePatchId $forge $pair.Forge
  if ($neoPatchId -ne $forgePatchId) { throw "Cross-version patch mismatch: $($pair.Label)" }
}
```

Expected: the loader-neutral implementation/test blobs match, and the NeoForge and Forge versions of both focused patches are equivalent. The pre-existing `CompressedAudioFormatsTest` fixture-loading difference remains intentionally untouched to avoid MP3 test scope creep.

- [ ] **Step 2: Run final Git checks**

Run in both worktrees:

```powershell
git diff --check
git status --short --branch
```

Expected: no whitespace errors and no uncommitted source changes.

- [ ] **Step 3: Hand off local artifacts for audible verification**

Provide these exact files and their recorded SHA-256 values:

```text
F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-1.20.1\build\libs\areamusic-neoforge-1.21.1-0.0.1.jar
F:\Dev\Minecraft Mods\AreaMusic\.worktrees\forge-final-verify\build\libs\areamusic-0.0.1.jar
```

Ask the user to replace the previous JAR, replay the same OGG files, and confirm normal playback plus loop boundaries. Do not push until the user requests it after listening verification.
