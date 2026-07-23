# AreaMusic NeoForge 1.21.1 Port Design

## Goal

Create a native NeoForge build of AreaMusic for Minecraft 1.21.1 while preserving the completed Forge 1.20.1 build, all existing data, and all playback behavior. The same GitHub repository will hold independent loader-and-version branches so Fabric and other Minecraft versions can be added later without turning the project into a multi-loader build.

## Repository and release structure

- Configure `origin` as `git@github.com:Rain156/AreaMusic.git`, fetch its state first, and never overwrite remote work or force-push.
- Preserve the current Forge implementation on branch `forge-1.20.1`.
- Develop this port on branch `neoforge-1.21.1`.
- Future ports use the same `<loader>-<minecraft-version>` convention, for example `fabric-1.21.1`.
- Branch names, commits, tags, files, and artifacts must not use automated-tool prefixes.
- Every version branch remains a complete project that can be opened, tested, built, and released independently. Loader-independent fixes are synchronized with normal commits or cherry-picks.
- Push `forge-1.20.1` and `neoforge-1.21.1` only after their respective verification gates pass.

## Build baseline

- Minecraft: 1.21.1.
- NeoForge: 21.1.235.
- ModDevGradle: 2.0.141.
- Java toolchain: Java 21.
- Metadata: `META-INF/neoforge.mods.toml` generated from project properties.
- Preserve mod ID `areamusic`, package root `datura.areamusic`, and mod version `0.0.1`.
- Produce `areamusic-neoforge-1.21.1-0.0.1-all.jar`.
- Continue bundling mp3spi, vorbisspi, jFLAC, and their runtime dependencies through NeoForge-compatible Jar-in-Jar packaging. Test-only fixtures and licenses must not enter the production artifact.

## Porting strategy

The area model, JSON codec, storage rules, resolver, playback state, music library, client playback session, PCM mixer, fades, independent gain calculation, and sample-rate fix keep their current responsibilities and behavior. The port changes only loader integration and Minecraft 1.21.1 API adaptations.

The loader-facing work consists of:

- replacing ForgeGradle and Forge metadata with the official NeoForge build layout;
- changing the mod entry point to NeoForge constructor injection and config registration;
- migrating server lifecycle, player, command, client tick, connection, shutdown, and screen events to NeoForge APIs;
- migrating the client configuration to NeoForge `ModConfigSpec` while retaining the same file name and value semantics;
- replacing `SimpleChannel` messages with native 1.21.1 custom payload types, stream codecs, payload registration, client handlers, and player delivery;
- adapting changed Minecraft methods and types without changing gameplay behavior;
- keeping all client-only classes inaccessible to dedicated-server class loading.

## Command and completion behavior

The command remains:

```text
/areamusic create <areaId> <pos1> <pos2> <musicId...>
```

The final `musicId` argument uses `StringArgumentType.greedyString()` because it is the last argument. Suggestions use the raw Music IDs returned by `MusicLibrary` and no longer call `StringArgumentType.escapeIfRequired`.

Consequences:

- `音乐名称.mp3` is inserted without quotation marks;
- names containing spaces are inserted directly and are consumed by the greedy final argument;
- nested files are inserted as forward-slash relative paths such as `文件夹名称/音乐名称.ogg`;
- the real file extension and its dot remain part of the Music ID;
- Music IDs remain case-sensitive and exactly match the stored relative path;
- the existing recursive scan, supported formats, symbolic-link protections, and case-conflict validation remain unchanged.

This command improvement is applied first to `forge-1.20.1`, then carried into `neoforge-1.21.1`, so both maintained versions expose the same command behavior.

## Data compatibility

The NeoForge build reads and writes the same data as Forge 1.20.1 without migration:

- local audio root: `<game-directory>/AreaMusic`;
- client volume config: `config/areamusic-client.toml`;
- per-save area files: `config/areamusic/<SaveID>/*.json`;
- area JSON field names, defaults, dimensions, priorities, volumes, looping flags, and fade durations;
- Music IDs, including extensions and nested relative paths.

A user can move the same game directory, config directory, and world between the two supported branches without editing JSON or renaming audio files.

## Runtime behavior

Server flow remains: load the music index and area JSON atomically, resolve the active area for each player, and send a revisioned playback state only when the effective state changes. `/areamusic reload` rescans both server and client libraries and preserves the previous valid snapshot if loading fails.

Client flow remains: receive playback state through the NeoForge payload API, resolve the Music ID against the local library, crossfade independent Java Sound streams, apply `Minecraft master volume x AreaMusic volume`, pause with the game, and stop cleanly on disconnect or shutdown. AreaMusic never uses the vanilla music volume category.

Compressed audio always decodes at its native sample rate before Java Sound resamples it to the 44.1 kHz mixer format, preserving the playback-rate fix for MP3, OGG, and FLAC.

## Failure handling

- Invalid or unreadable audio and JSON produce the existing localized error paths without replacing the last valid server snapshot.
- Unsupported sample or channel conversion closes intermediate streams and reports an audio failure once per deduplication key.
- Network handlers enqueue client work on the correct game thread and reject incompatible payload registration rather than touching client state from a network thread.
- Missing or ambiguous sound-options UI structures log a warning and skip slider insertion while playback continues with the saved value.
- Remote setup and pushes stop on non-fast-forward or unrelated-history conditions; no destructive Git operation is allowed.

## Verification

1. Add a failing Forge 1.20.1 regression test for raw, unquoted greedy Music ID suggestions, then implement and verify it before branching the NeoForge port.
2. Port and run all existing unit tests on Java 21, including recursive Music IDs, area JSON/storage, transitions, independent volume, decoder formats, crossfades, and 48 kHz duration preservation.
3. Add focused NeoForge tests for payload codec round trips, client handler dispatch, config persistence, and command completion with spaces and nested paths.
4. Run clean unit tests, the full NeoForge build, reobfuscation/production packaging, and the NeoForge GameTest server.
5. Inspect the all-in-one JAR for AreaMusic classes and all decoder libraries, and confirm test fixtures are absent.
6. Launch a NeoForge 1.21.1 client and verify command completion, creation, reload, nested Music IDs, sound-options placement, independent volume, persistence, area transitions, crossfades, and real 48 kHz MP3 speed.
7. Launch a dedicated server to verify client classes are not loaded and an existing Forge-created save/config is accepted unchanged.
8. Record artifact size and SHA-256, verify both version branches are clean, then push the verified branches to the configured GitHub repository.

## Out of scope

- Implementing Fabric or another Minecraft version in this port.
- Converting the repository into a shared multi-module or conditional-source build.
- Changing the JSON schema, directory layout, area resolution rules, or audio mixing design.
- Adding unrelated commands or gameplay features.
