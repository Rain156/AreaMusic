# AreaMusic Playback Rate Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the original playback duration of compressed audio when its source sample rate differs from AreaMusic's 44.1 kHz mixer rate.

**Architecture:** Format-specific providers decode MP3, OGG, and FLAC only to native-rate signed PCM. Java Sound then performs the PCM-to-PCM channel and sample-rate conversion to the fixed mixer format. A real compressed 48 kHz regression stream verifies duration rather than trusting its output format label.

**Tech Stack:** Java 17, Forge 47.4.21, Java Sound SPI, mp3spi/vorbisspi/jFLAC, JUnit Jupiter 5.10.2, Gradle.

---

### Task 1: Add a duration-preservation regression test

**Files:**
- Modify: `src/test/java/datura/areamusic/client/audio/CompressedAudioFormatsTest.java`
- Reuse: `src/test/resources/datura/areamusic/audio/test.ogg`

- [ ] **Step 1: Build a valid 48 kHz compressed fixture in the test**

Read `test.ogg`, replace the Vorbis identification packet's 44,100 Hz little-endian field with 48,000 Hz, zero the first Ogg page checksum, recompute it with polynomial `0x04C11DB7`, and write the result to `@TempDir`. Assert the format-specific reader reports 48,000 Hz so a malformed transformation cannot produce a misleading test result.

```java
byte[] ogg = resource.readAllBytes();
int identification = indexOf(ogg, new byte[]{1, 'v', 'o', 'r', 'b', 'i', 's'});
writeLittleEndianInt(ogg, identification + 12, 48_000);
rewriteFirstPageChecksum(ogg);
Files.write(tempDir.resolve("source-48000.ogg"), ogg);
```

- [ ] **Step 2: Compare native and mixer durations**

Decode the transformed OGG once through `VorbisFormatConversionProvider` to 48 kHz signed 16-bit PCM and once through `AudioStreamFactory` to `MIX_FORMAT`. Count complete PCM frames and compare `nativeFrames / 48000.0` with `mixerFrames / 44100.0` using a `0.01` second tolerance.

```java
double nativeDuration = readFrames(nativePcm) / 48_000.0;
double mixerDuration = readFrames(mixerPcm) / AudioStreamFactory.SAMPLE_RATE;
assertEquals(nativeDuration, mixerDuration, 0.01);
```

- [ ] **Step 3: Run the focused test and confirm RED**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests datura.areamusic.client.audio.CompressedAudioFormatsTest
```

Expected: the new duration assertion fails on the old direct compressed-to-44.1 kHz conversion because the returned duration is about `48000 / 44100` times too long.

### Task 2: Decode compressed streams at their native sample rate

**Files:**
- Modify: `src/main/java/datura/areamusic/client/audio/AudioStreamFactory.java`

- [ ] **Step 1: Remove the incorrect direct conversion shortcut**

Delete only this branch from `AudioStreamFactory.open`:

```java
if (decoder != null && decoder.converter().isConversionSupported(MIX_FORMAT, sourceFormat)) {
    return decoder.converter().getAudioInputStream(MIX_FORMAT, current);
}
```

Keep `chooseDecodedPcmFormat` as the sole compressed-decoder target selection. It preserves the source sample rate exposed by each provider. Keep the subsequent `AudioSystem.getAudioInputStream(MIX_FORMAT, current)` call as the sole sample-rate conversion step.

- [ ] **Step 2: Run the focused test and confirm GREEN**

Run the Task 1 command again. Expected: all `CompressedAudioFormatsTest` cases pass and the 48 kHz duration difference stays within `0.01` seconds.

- [ ] **Step 3: Run all unit tests**

Run `./gradlew.bat test`. Expected: zero failed tests.

### Task 3: Validate the reported MP3 files and package the fix

**Files:**
- Verify: `run/AreaMusic/Chace - Auto-Save.mp3`
- Verify: `run/AreaMusic/Warsic,壹勺籽糖 - 坠入星河的帷幕.mp3`
- Produce: `build/libs/areamusic-0.0.1-all.jar`

- [ ] **Step 1: Measure both real 48 kHz MP3 paths**

For each MP3, count frames after native 48 kHz PCM decode and after `AudioStreamFactory` 44.1 kHz output. Assert their frame-derived durations differ by no more than `0.02` seconds. This is a local diagnostic and must not add either song to Git.

- [ ] **Step 2: Run the complete verification pipeline**

Run:

```powershell
.\gradlew.bat clean test build reobfJarJar runGameTestServer
```

Expected: Gradle exits `0`, all unit tests pass, and Forge GameTest reports all tests successful.

- [ ] **Step 3: Inspect and fingerprint the bundled JAR**

Confirm `build/libs/areamusic-0.0.1-all.jar` contains AreaMusic classes and the bundled MP3, OGG, and FLAC decoder libraries. Record its SHA-256 hash.

- [ ] **Step 4: Review and commit**

Run `git diff --check`, inspect the focused diff, then commit the test, production fix, and completed plan with message `fix: preserve compressed audio playback rate`.
