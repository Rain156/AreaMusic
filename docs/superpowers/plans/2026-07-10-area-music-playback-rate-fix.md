# AreaMusic Playback Rate Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the original playback duration of compressed audio when its source sample rate differs from AreaMusic's 44.1 kHz mixer rate.

**Architecture:** Format-specific providers decode MP3, OGG, and FLAC only to native-rate signed PCM. Java Sound then performs the PCM-to-PCM channel and sample-rate conversion to the fixed mixer format. A real compressed 48 kHz regression stream verifies duration rather than trusting its output format label.

**Tech Stack:** Java 17, Forge 47.4.21, Java Sound SPI, mp3spi/vorbisspi/jFLAC, JUnit Jupiter 5.10.2, Gradle.

---

### Task 1: Add a duration-preservation regression test

**Files:**
- Modify: `src/test/java/datura/areamusic/client/audio/CompressedAudioFormatsTest.java`
- Create: `src/test/resources/datura/areamusic/audio/test-48000.mp3.b64`
- Create: `src/test/resources/datura/areamusic/audio/JCODEC-LICENSE.txt`
- Modify: `src/test/resources/datura/areamusic/audio/SOURCES.md`

- [x] **Step 1: Build a valid 48 kHz compressed fixture in the test**

Decode the BSD-licensed JCodec MP3 frame from Base64, repeat it 32 times in memory so the decoder emits enough PCM for a strong duration assertion, and write it to `@TempDir`. Assert the format-specific reader reports 48,000 Hz, stereo, and support for the faulty direct conversion path.

```java
byte[] frame = Base64.getMimeDecoder().decode(resource.readAllBytes());
byte[] mp3 = new byte[frame.length * 32];
for (int offset = 0; offset < mp3.length; offset += frame.length) {
    System.arraycopy(frame, 0, mp3, offset, frame.length);
}
Files.write(tempDir.resolve("source-48000.mp3"), mp3);
```

- [x] **Step 2: Compare native and mixer durations**

Decode the MP3 once through `MpegFormatConversionProvider` to 48 kHz signed 16-bit PCM and once through `AudioStreamFactory` to `MIX_FORMAT`. Count complete PCM frames and compare `nativeFrames / 48000.0` with `mixerFrames / 44100.0` using a `0.0001` second tolerance.

```java
double nativeDuration = readFrames(nativePcm) / 48_000.0;
double mixerDuration = readFrames(mixerPcm) / AudioStreamFactory.SAMPLE_RATE;
assertEquals(nativeDuration, mixerDuration, 0.0001);
```

- [x] **Step 3: Run the focused test and confirm RED**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests datura.areamusic.client.audio.CompressedAudioFormatsTest
```

Observed: the native duration was `0.384` seconds while the old direct compressed-to-44.1 kHz conversion returned `0.417959` seconds, about 8.84% too long.

### Task 2: Decode compressed streams at their native sample rate

**Files:**
- Modify: `src/main/java/datura/areamusic/client/audio/AudioStreamFactory.java`

- [x] **Step 1: Remove the incorrect direct conversion shortcut and select native PCM reliably**

Delete only this branch from `AudioStreamFactory.open`:

```java
if (decoder != null && decoder.converter().isConversionSupported(MIX_FORMAT, sourceFormat)) {
    return decoder.converter().getAudioInputStream(MIX_FORMAT, current);
}
```

Keep `chooseDecodedPcmFormat` as the sole compressed-decoder target selection. First construct signed 16-bit little-endian PCM from the known source sample rate and channel count and use it when supported; this handles MP3 SPI streams for which `getTargetFormats` incorrectly returns an empty array. Retain target enumeration as a fallback. Keep the subsequent `AudioSystem.getAudioInputStream(MIX_FORMAT, current)` call as the sole sample-rate conversion step.

- [x] **Step 2: Run the focused test and confirm GREEN**

Run the Task 1 command again. Expected: all `CompressedAudioFormatsTest` cases pass and the 48 kHz duration difference stays within `0.0001` seconds.

- [x] **Step 3: Run all unit tests**

Run `./gradlew.bat test`. Expected: zero failed tests.

### Task 3: Validate the reported MP3 files and package the fix

**Files:**
- Verify: `run/areamusic/Chace - Auto-Save.mp3`
- Verify: `run/areamusic/Warsic,壹勺籽糖 - 坠入星河的帷幕.mp3`
- Produce: `build/libs/areamusic-0.0.1-all.jar`

- [x] **Step 1: Measure both real 48 kHz MP3 paths**

For each MP3, count frames after native 48 kHz PCM decode and after `AudioStreamFactory` 44.1 kHz output. Assert their frame-derived durations differ by no more than `0.02` seconds. This is a local diagnostic and must not add either song to Git.

Observed: `Chace - Auto-Save.mp3` differed by `0.006504` seconds (`269.063995` vs. `269.070499`), and the second MP3 differed by `0.005344` seconds (`220.272003` vs. `220.277347`).

- [x] **Step 2: Run the complete verification pipeline**

Run:

```powershell
.\gradlew.bat clean test build reobfJarJar runGameTestServer
```

Expected: Gradle exits `0`, all unit tests pass, and Forge GameTest reports all tests successful.

- [x] **Step 3: Inspect and fingerprint the bundled JAR**

Confirm `build/libs/areamusic-0.0.1-all.jar` contains AreaMusic classes and the bundled MP3, OGG, and FLAC decoder libraries. Record its SHA-256 hash.

Observed: the all-in-one JAR is 557,995 bytes and has SHA-256 `18AB1B6BEFB3332A2275A60301B6E8BE66659ACEA390DD65037A7FD1A4FC1C21`.

- [x] **Step 4: Review and commit**

Run `git diff --check`, inspect the focused diff, then commit the test, production fix, and completed plan with message `fix: preserve compressed audio playback rate`.
