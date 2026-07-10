# AreaMusic Playback Rate Fix Design

## Goal

Preserve the original duration and playback speed of compressed audio whose source sample rate differs from AreaMusic's 44.1 kHz mixer rate. The reported case is 48 kHz MP3 playback running about 8.8% slow.

## Root cause

`AudioStreamFactory` currently asks a format-specific decoder to convert compressed audio directly to `MIX_FORMAT`. The MP3 provider accepts that request but does not resample the decoded 48 kHz PCM. It returns the original PCM frame count while labeling the stream as 44.1 kHz, so one source second takes `48000 / 44100` seconds to play.

## Approved behavior

- Decode MP3, OGG, and FLAC to signed PCM at the source sample rate first.
- When a decoder does not enumerate targets correctly, construct the canonical signed 16-bit PCM format from the source sample rate and channel count and verify that the decoder accepts it.
- If that decoded PCM already matches `MIX_FORMAT`, return it unchanged.
- Otherwise let Java Sound convert the decoded PCM to `MIX_FORMAT`, which performs real sample-rate conversion.
- Keep WAV handling, the mixer, crossfades, area selection, and independent volume behavior unchanged.
- Continue reporting unsupported conversion as `UnsupportedAudioFileException` and close intermediate streams on failure.

## Regression strategy

The test uses a 672-byte, 48 kHz stereo MP3 frame from JCodec's BSD-licensed test resources, stored as Base64 text with its source and SHA-256 recorded. It repeats the frame in memory to give the decoder enough input, independently decodes the stream at its native rate, then compares native PCM duration with the duration returned by `AudioStreamFactory` at 44.1 kHz.

The old implementation must fail because it returns nearly the same frame count under a different sample-rate label. The fixed implementation must preserve duration within a small tolerance. Existing MP3, OGG, FLAC, and WAV decoding tests remain in the full suite.

## Verification

- Focused regression test demonstrates RED before the production change and GREEN afterward.
- Both user-provided 48 kHz MP3 files produce 44.1 kHz PCM whose frame-derived duration matches native decoded duration.
- The complete unit test, build, reobfuscation, bundled JAR, and Forge GameTest pipeline succeeds.
