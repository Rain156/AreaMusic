# AreaMusic OGG Zero-Read Continuity Fix Design

## Problem

AreaMusic renders audio in fixed blocks of 1,024 stereo PCM frames. `PcmMixerEngine.Track.readFrames` currently stops filling a block whenever `AudioInputStream.read` returns `0`. The unfilled bytes remain zero and are written to the audio device as silence.

VorbisSPI 1.0.3.3 uses Tritonus' asynchronous filtered stream. A read can advance the OGG decoder state without producing PCM and therefore return `0` temporarily. This occurs throughout normal playback, not only at loop boundaries.

Measurements using the current final NeoForge runtime and the three OGG files in `run/AreaMusic` found:

- `jsw(1).ogg`: 1,150 zero-length reads, with up to 7 consecutive zero reads.
- `ogg/1.ogg`: 859 zero-length reads, with up to 9 consecutive zero reads.
- `ogg/2.ogg`: 1,219 zero-length reads, with up to 8 consecutive zero reads.

Each prematurely ended block can insert up to 1,024 silent frames, or about 23.22 milliseconds at 44.1 kHz. Consecutive zero reads therefore create the audible periodic dropouts.

The bundled FLAC fixture completed with no zero-length reads. WAV uses the JDK Java Sound stream and has no evidence of the same behavior. MP3 playback is already accepted by the user and is outside the behavioral change.

## Requirements

- Temporary zero-length decoder reads must not insert silence into an otherwise continuous track.
- A decoder that never makes progress must not block the audio thread forever.
- EOF and looping behavior must remain unchanged.
- MP3 reader selection, conversion, resampling, and playback behavior must remain unchanged.
- OGG, FLAC, and WAV continuity must be covered by automated tests.
- Forge 1.20.1 and NeoForge 1.21.1 must receive equivalent source changes.

## Considered Approaches

### 1. Bounded retry in the mixer read loop

When a positive-length request returns `0`, retry the same stream until it produces data, reaches EOF, or exceeds a consecutive-zero limit. This directly fixes the point that currently converts decoder underflow into silence and protects any future decoder with the same behavior.

This is the selected approach because it is small, format-neutral, and leaves all decoder and sample-rate selection unchanged.

### 2. OGG-specific stream wrapper

Wrap only Vorbis streams in `AudioStreamFactory` and hide zero reads from callers. This isolates the workaround but adds another `AudioInputStream` layer with frame-length, closing, and exception semantics. It would also leave the mixer vulnerable to the same behavior from another provider.

### 3. Replace VorbisSPI or add a decode-ahead subsystem

A different OGG decoder or a dedicated producer buffer could avoid the provider behavior. Both options expand dependencies, memory use, concurrency, and maintenance without being necessary for the confirmed failure.

## Selected Design

`PcmMixerEngine.Track.readFrames` continues accumulating bytes until the requested PCM block is full or true EOF is reached.

- `read > 0`: append the bytes and reset the consecutive-zero counter.
- `read == 0`: increment the consecutive-zero counter and retry without advancing output or gain state.
- `read < 0`: preserve the existing EOF behavior. Non-looping tracks become exhausted; looping tracks reopen once and continue filling the same output block.
- More than 64 consecutive zero reads without data or EOF: throw an `IOException`. The existing playback boundary converts this into a structured `AudioFailure.Kind.DECODE` error.

The limit of 64 is deterministic and more than seven times the largest observed run of 9 zero reads. It prevents an invalid third-party stream from spinning forever while allowing normal VorbisSPI state transitions to complete. No sleep is introduced because these are synchronous file decodes and each retry advances decoder state; sleeping would unnecessarily risk starving the output device.

## Testing

Testing follows red-green-refactor.

1. Add a deterministic mixer test whose stream returns zero multiple times before returning known PCM. The first rendered block must contain the known samples without an inserted silent block. This test must fail against the current implementation.
2. Add a stream that always returns zero. Rendering must terminate with a decode error after the configured bound rather than hanging.
3. Exercise the existing real OGG fixture through the mixer and compare its decoded frame count with rendered progress so temporary zero reads cannot inflate playback duration with extra blocks.
4. Fully decode the existing FLAC fixture and a generated WAV fixture, asserting frame alignment and forward progress. These checks audit the other supported non-MP3 formats for the same class of fault.
5. Keep the existing MP3 tests as regression coverage but do not modify MP3 production behavior.
6. Run the focused audio tests, then the complete Forge and NeoForge builds and bundled-codec verification tasks.

## Version Integration

The behavioral Java sources are currently identical between the two loader branches. Implementation begins on `neoforge-1.21.1`, then the focused source and test changes are applied to the Forge fix branch. Loader-specific build scripts are not part of this fix unless verification exposes a loader-only issue.

## Non-Goals

- Changing the 44.1 kHz mixer format or 1,024-frame output block size.
- Replacing VorbisSPI, JOrbis, or Java Sound.
- Altering fades, area transitions, volume controls, or MP3 behavior.
- Treating true EOF as temporary decoder underflow.
