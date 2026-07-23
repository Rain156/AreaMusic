# AreaMusic NeoForge 1.21.1 Implementation Plan

> **Historical status (synchronized with the 2026-07-22 extension):** This unchecked plan remains the original NeoForge port plan and is not evidence that the port or its acceptance steps have completed. The [multitrack/resume design](../specs/2026-07-22-area-music-multitrack-resume-design.md) and [implementation plan](./2026-07-22-area-music-multitrack-resume.md) are authoritative for the current extension. The shared schema v2 uses 1–16 `tracks`, per-track `delaySeconds` defaults to `0`, and area-level `resumeOnReenter` defaults to `false`; schema v1 scalar fields remain compatible by mapping to one track. New runtime paths use `<gameDir>/areamusic`, and the former uppercase name is only a safe migration source. Scalar payload/API snippets below are historical; NeoForge continues to use its native custom-payload API rather than Forge `SimpleChannel`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the verified Forge 1.20.1 release and produce a feature- and data-compatible native NeoForge 1.21.1 release with raw, unquoted greedy Music ID completion.

**Architecture:** Maintain one complete branch per loader and Minecraft version. First add the loader-independent command behavior to `forge-1.20.1`, then branch `neoforge-1.21.1` and replace only build, event, configuration, networking, and changed Minecraft integration APIs; keep storage and the independent Java Sound mixer intact.

**Tech Stack:** Java 17 for Forge 1.20.1, Java 21 for NeoForge 1.21.1, NeoForge 21.1.235, ModDevGradle 2.0.141, Gradle 9.2.1, Brigadier, Java Sound, JUnit Jupiter 5.10.2.

---

### Task 1: Secure the repository baseline and establish the Forge branch

**Files:**
- Verify: repository state only; no source changes

- [ ] **Step 1: Verify the local baseline is clean**

Run:

```powershell
git status --short --branch
git log -5 --oneline --decorate
```

Expected: the working tree has no changes and `HEAD` contains the approved design and this plan. No branch, commit, tag, file, or artifact name may have an automated-tool prefix.

- [ ] **Step 2: Re-run the verified Forge baseline**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build reobfJarJar runGameTestServer --console=plain
```

Expected: 64 tests pass, the all-in-one Forge JAR builds, and the Forge GameTest server exits successfully.

- [ ] **Step 3: Inspect and configure the GitHub remote without overwriting it**

Run:

```powershell
git ls-remote git@github.com:Rain156/AreaMusic.git
if ((git remote) -contains 'origin') {
    git remote set-url origin git@github.com:Rain156/AreaMusic.git
} else {
    git remote add origin git@github.com:Rain156/AreaMusic.git
}
git fetch origin --prune
git remote -v
git branch -r
```

Expected: `origin` uses the supplied SSH URL. If `ls-remote` or `git fetch` reports missing credentials, stop before any push and report the SSH blocker. If existing remote branches have unrelated history, inspect them and stop instead of force-pushing or replacing them.

- [ ] **Step 4: Rename the maintained Forge branch**

Run:

```powershell
git branch -m forge-1.20.1
git status --short --branch
```

Expected: the current branch is exactly `forge-1.20.1` and the working tree remains clean.

### Task 2: Add raw greedy Music ID completion to Forge 1.20.1

**Files:**
- Create: `src/test/java/datura/areamusic/server/AreaMusicCommandsTest.java`
- Modify: `src/main/java/datura/areamusic/server/AreaMusicCommands.java`

- [ ] **Step 1: Write failing command structure and suggestion tests**

Create `AreaMusicCommandsTest.java`:

```java
package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicCommandsTest {
    @Test
    void usesGreedyStringForTheFinalMusicId() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        AreaMusicCommands.register(dispatcher);

        CommandNode<CommandSourceStack> node = dispatcher.getRoot()
                .getChild("areamusic")
                .getChild("create")
                .getChild("areaId")
                .getChild("pos1")
                .getChild("pos2")
                .getChild("musicId");
        ArgumentCommandNode<?, ?> argument = assertInstanceOf(ArgumentCommandNode.class, node);
        StringArgumentType type = assertInstanceOf(StringArgumentType.class, argument.getType());

        assertEquals(StringArgumentType.StringType.GREEDY_PHRASE, type.getType());
    }

    @Test
    void suggestsRawPathsWithSpacesWithoutQuotes() {
        Suggestions suggestions = AreaMusicCommands.suggestMusicIds(
                List.of("音乐 名称.mp3", "文件夹 名称/音乐 名称.ogg"),
                new SuggestionsBuilder("", 0)
        ).join();

        Set<String> texts = suggestions.getList().stream()
                .map(Suggestion::getText)
                .collect(Collectors.toSet());
        assertEquals(Set.of("音乐 名称.mp3", "文件夹 名称/音乐 名称.ogg"), texts);
        assertTrue(texts.stream().noneMatch(text -> text.startsWith("\"")));
    }
}
```

- [ ] **Step 2: Run the test and confirm RED**

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.server.AreaMusicCommandsTest --rerun-tasks --console=plain
```

Expected: test compilation fails because the iterable `suggestMusicIds` overload does not exist, and the old command uses `QUOTABLE_PHRASE` rather than `GREEDY_PHRASE`.

- [ ] **Step 3: Implement greedy raw completion**

In `AreaMusicCommands.java`, replace the final command node and suggestion helpers with:

```java
.then(Commands.argument("musicId", StringArgumentType.greedyString())
        .suggests((context, builder) -> suggestMusicIds(AreaMusicServer.musicIds(), builder))
        .executes(AreaMusicCommands::createArea))
```

```java
static CompletableFuture<Suggestions> suggestMusicIds(
        Iterable<String> musicIds,
        SuggestionsBuilder builder
) {
    return SharedSuggestionProvider.suggest(musicIds, builder);
}
```

Delete the old stream mapping through `StringArgumentType.escapeIfRequired`. Keep `StringArgumentType.getString(context, "musicId")`; a greedy string returns the entire unquoted remainder, including spaces and forward slashes.

- [ ] **Step 4: Verify GREEN and all Forge behavior**

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.server.AreaMusicCommandsTest --rerun-tasks --console=plain
.\gradlew.bat clean test build reobfJarJar runGameTestServer --console=plain
```

Expected: both new tests pass, the full suite increases from 64 to 66 passing tests, and the Forge production pipeline succeeds.

- [ ] **Step 5: Commit the cross-version command behavior**

Run:

```powershell
git add src/main/java/datura/areamusic/server/AreaMusicCommands.java src/test/java/datura/areamusic/server/AreaMusicCommandsTest.java
git commit -m "feat: add unquoted music path completion"
```

### Task 3: Create the NeoForge branch and migrate the build system

**Files:**
- Modify: `build.gradle`
- Modify: `settings.gradle`
- Modify: `gradle.properties`
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Delete: `src/main/resources/META-INF/mods.toml`
- Create: `src/main/templates/META-INF/neoforge.mods.toml`
- Modify: `src/main/resources/pack.mcmeta`

- [ ] **Step 1: Create the version branch from the verified Forge head**

Run:

```powershell
git switch -c neoforge-1.21.1
git merge-base --is-ancestor forge-1.20.1 neoforge-1.21.1
git status --short --branch
```

Expected: the branch is exactly `neoforge-1.21.1`, contains the command commit, and is clean.

- [ ] **Step 2: Select Java 21**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

Expected: Java 21.0.9 is active.

- [ ] **Step 3: Replace settings and version properties**

Replace `settings.gradle` with:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}

rootProject.name = 'areamusic'
```

Replace the version-specific portion of `gradle.properties` with these exact values while retaining AreaMusic metadata:

```properties
org.gradle.jvmargs=-Xmx3G
org.gradle.daemon=false
org.gradle.parallel=true
org.gradle.caching=true

parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17
minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.235
loader_version_range=[1,)

mod_id=areamusic
mod_name=AreaMusic
mod_license=All Rights Reserved
mod_version=0.0.1
mod_group_id=datura
mod_authors=Datura
mod_description=Play local music automatically inside server-defined areas.
```

- [ ] **Step 4: Replace ForgeGradle with ModDevGradle**

Use this structure in `build.gradle`:

```groovy
plugins {
    id 'java-library'
    id 'idea'
    id 'net.neoforged.moddev' version '2.0.141'
}

group = mod_group_id
version = mod_version

base {
    archivesName = "${mod_id}-neoforge-${minecraft_version}"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = project.neo_version
    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }
    runs {
        client {
            client()
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        gameTestServer {
            type = 'gameTestServer'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        data {
            data()
            programArguments.addAll '--mod', project.mod_id, '--all', '--output',
                    file('src/generated/resources/').absolutePath,
                    '--existing', file('src/main/resources/').absolutePath
        }
        configureEach {
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }
    mods {
        "${mod_id}" {
            sourceSet(sourceSets.main)
        }
    }
}

sourceSets.main.resources.srcDir 'src/generated/resources'
configurations.runtimeClasspath.extendsFrom configurations.localRuntime
configurations.configureEach {
    exclude group: 'junit', module: 'junit'
}

dependencies {
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'

    jarJar(implementation('com.googlecode.soundlibs:mp3spi:1.9.5.4')) {
        version { strictly '[1.9.5.4,1.9.5.5)'; prefer '1.9.5.4' }
    }
    jarJar(implementation('com.googlecode.soundlibs:jlayer:1.0.1.4')) {
        version { strictly '[1.0.1.4,1.0.1.5)'; prefer '1.0.1.4' }
    }
    jarJar(implementation('com.googlecode.soundlibs:vorbisspi:1.0.3.3')) {
        version { strictly '[1.0.3.3,1.0.3.4)'; prefer '1.0.3.3' }
    }
    jarJar(implementation('com.googlecode.soundlibs:jorbis:0.0.17.4')) {
        version { strictly '[0.0.17.4,0.0.17.5)'; prefer '0.0.17.4' }
    }
    jarJar(implementation('com.googlecode.soundlibs:tritonus-share:0.3.7.4')) {
        version { strictly '[0.3.7.4,0.3.7.5)'; prefer '0.3.7.4' }
    }
    jarJar(implementation('org.jflac:jflac-codec:1.5.2')) {
        version { strictly '[1.5.2,1.5.3)'; prefer '1.5.2' }
    }
}

def metadata = tasks.register('generateModMetadata', ProcessResources) {
    def properties = [
            minecraft_version: minecraft_version,
            minecraft_version_range: minecraft_version_range,
            neo_version: neo_version,
            loader_version_range: loader_version_range,
            mod_id: mod_id,
            mod_name: mod_name,
            mod_license: mod_license,
            mod_version: mod_version,
            mod_authors: mod_authors,
            mod_description: mod_description
    ]
    inputs.properties properties
    expand properties
    from 'src/main/templates'
    into 'build/generated/sources/modMetadata'
}
sourceSets.main.resources.srcDir metadata
neoForge.ideSyncTask metadata

tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }
tasks.named('test', Test).configure { useJUnitPlatform() }
tasks.named('jarJar').configure { archiveClassifier = 'all' }
tasks.named('build').configure { dependsOn tasks.named('jarJar') }
```

- [ ] **Step 5: Replace loader metadata and resource pack metadata**

Delete `src/main/resources/META-INF/mods.toml`. Create `src/main/templates/META-INF/neoforge.mods.toml`:

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''${mod_description}'''

[[dependencies.${mod_id}]]
modId="neoforge"
type="required"
versionRange="[${neo_version},)"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="minecraft"
type="required"
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"
```

Set `pack_format` to `34` in `src/main/resources/pack.mcmeta`.

- [ ] **Step 6: Upgrade the wrapper and confirm the intentional RED build**

Set `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip
```

Run:

```powershell
.\gradlew.bat wrapper --gradle-version 9.2.1 --distribution-type bin
.\gradlew.bat wrapper --gradle-version 9.2.1 --distribution-type bin
.\gradlew.bat compileJava --console=plain
```

Expected: Gradle and NeoForge resolve successfully; Java compilation fails only at remaining `net.minecraftforge` imports and APIs. Dependency resolution or metadata failures must be fixed before source migration begins.

### Task 4: Port the mod entry point, configuration, and events

**Files:**
- Modify: `src/main/java/datura/areamusic/AreaMusic.java`
- Modify: `src/main/java/datura/areamusic/config/AreaMusicClientConfig.java`
- Modify: `src/main/java/datura/areamusic/server/AreaMusicServer.java`
- Modify: `src/main/java/datura/areamusic/client/ClientAreaMusic.java`
- Modify: `src/main/java/datura/areamusic/client/AreaMusicSoundOptions.java`
- Modify: `src/test/java/datura/areamusic/config/AreaMusicClientConfigTest.java`

- [ ] **Step 1: Port the entry point and config registration**

Replace `AreaMusic` with the NeoForge constructor shape:

```java
@Mod(AreaMusic.MOD_ID)
public final class AreaMusic {
    public static final String MOD_ID = "areamusic";

    public AreaMusic(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(
                ModConfig.Type.CLIENT,
                AreaMusicClientConfig.INSTANCE.spec(),
                AreaMusicClientConfig.FILE_NAME
        );
        modEventBus.addListener(AreaMusicNetwork::register);
    }
}
```

Use imports from `net.neoforged.bus.api`, `net.neoforged.fml`, and `net.neoforged.fml.common`.

- [ ] **Step 2: Port `ForgeConfigSpec` without changing its file format**

In `AreaMusicClientConfig` and its test, replace `net.minecraftforge.common.ForgeConfigSpec` with `net.neoforged.neoforge.common.ModConfigSpec`, and rename the declared types accordingly:

```java
private final ModConfigSpec spec;
private final ModConfigSpec.DoubleValue volume;
```

Keep the path `volume`, default `1.0`, range `[0.0, 1.0]`, explicit file name `areamusic-client.toml`, clamp, and immediate `save()` behavior unchanged.

- [ ] **Step 3: Port server events**

Use `net.neoforged.fml.common.EventBusSubscriber`, `net.neoforged.bus.api.SubscribeEvent`, NeoForge server events, and `net.neoforged.neoforge.event.tick.PlayerTickEvent`.

Replace the phase-based player tick method with:

```java
@SubscribeEvent
public static void onPlayerTick(PlayerTickEvent.Post event) {
    if (event.getEntity() instanceof ServerPlayer player) {
        syncPlayer(player);
    }
}
```

Use `@EventBusSubscriber(modid = AreaMusic.MOD_ID)` on `AreaMusicServer`. Preserve command registration, login, logout, respawn, dimension change, server start/stop, atomic reload, and storage paths. Replace direct `player.server` comparisons with `player.getServer()` if required by 1.21.1 mappings.

- [ ] **Step 4: Port client event classes and paths**

Use these NeoForge types:

```text
net.neoforged.api.distmarker.Dist
net.neoforged.bus.api.SubscribeEvent
net.neoforged.fml.common.EventBusSubscriber
net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
net.neoforged.fml.loading.FMLPaths
net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
net.neoforged.neoforge.client.event.ClientTickEvent
net.neoforged.neoforge.client.event.ScreenEvent
net.neoforged.neoforge.event.GameShuttingDownEvent
```

Change client ticking to:

```java
@SubscribeEvent
public static void onClientTick(ClientTickEvent.Post event) {
    if (instance != null) {
        instance.tick();
    }
}
```

Annotate client subscriber classes with `@EventBusSubscriber(modid = AreaMusic.MOD_ID, value = Dist.CLIENT)`. Rename the nested `ForgeEvents` class to `GameEvents`. Keep initialization, connect/disconnect, shutdown, UI list identification, and independent volume behavior unchanged.

- [ ] **Step 5: Re-run compilation and isolate network failures**

Run:

```powershell
.\gradlew.bat compileJava --console=plain
```

Expected: remaining failures are confined to the old Forge `SimpleChannel`, `NetworkEvent.Context`, and message registration APIs. Fix any explicit 1.21.1 method rename listed in Steps 1-4, but do not change storage or playback behavior.

### Task 5: Replace SimpleChannel with NeoForge custom payloads

**Files:**
- Modify: `src/main/java/datura/areamusic/network/AreaMusicNetwork.java`
- Modify: `src/main/java/datura/areamusic/network/ClientboundPlaybackState.java`
- Modify: `src/main/java/datura/areamusic/network/ClientboundReloadMusic.java`
- Modify: `src/test/java/datura/areamusic/playback/PlaybackStateTest.java`

- [ ] **Step 1: Change codec tests to the desired payload API and verify RED**

Replace direct static codec calls in `PlaybackStateTest` with:

```java
ClientboundPlaybackState.STREAM_CODEC.encode(buffer, message);
ClientboundPlaybackState decoded = ClientboundPlaybackState.STREAM_CODEC.decode(buffer);
```

and:

```java
ClientboundReloadMusic.STREAM_CODEC.encode(buffer, new ClientboundReloadMusic(42L));
assertEquals(new ClientboundReloadMusic(42L), ClientboundReloadMusic.STREAM_CODEC.decode(buffer));
```

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.playback.PlaybackStateTest --rerun-tasks --console=plain
```

Expected: compilation fails because the payload records do not yet expose `STREAM_CODEC` or implement the 1.21.1 payload contract.

- [ ] **Step 2: Implement `ClientboundReloadMusic` as a payload**

Use this structure:

```java
public record ClientboundReloadMusic(long revision) implements CustomPacketPayload {
    public static final Type<ClientboundReloadMusic> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AreaMusic.MOD_ID, "reload_music")
    );
    public static final StreamCodec<FriendlyByteBuf, ClientboundReloadMusic> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ClientboundReloadMusic decode(FriendlyByteBuf buffer) {
                    return new ClientboundReloadMusic(buffer.readVarLong());
                }

                @Override
                public void encode(FriendlyByteBuf buffer, ClientboundReloadMusic value) {
                    buffer.writeVarLong(value.revision());
                }
            };

    public ClientboundReloadMusic {
        if (revision < 0) throw new IllegalArgumentException("Revision must not be negative");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

- [ ] **Step 3: Implement the playback-state payload**

Make `ClientboundPlaybackState` implement `CustomPacketPayload`, add type `areamusic:playback_state`, and expose an anonymous `StreamCodec<FriendlyByteBuf, ClientboundPlaybackState>`. Its `encode` and `decode` bodies must preserve the existing field order and limits:

```text
revision VarLong
playing boolean
areaId UTF(max 64) when playing
musicId UTF(max 1024) when playing
volume float when playing
loop boolean when playing
fadeInMs VarInt when playing
fadeOutMs VarInt when playing
```

Keep constructor validation and `PlaybackState.stopped()` handling unchanged.

- [ ] **Step 4: Register and send payloads natively**

Replace channel state in `AreaMusicNetwork` with:

```java
public static void register(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar("1");
    registrar.playToClient(
            ClientboundPlaybackState.TYPE,
            ClientboundPlaybackState.STREAM_CODEC,
            (message, context) -> handleClientPlayback(message)
    );
    registrar.playToClient(
            ClientboundReloadMusic.TYPE,
            ClientboundReloadMusic.STREAM_CODEC,
            (message, context) -> handleClientReload(message)
    );
}
```

Send with:

```java
PacketDistributor.sendToPlayer(player, new ClientboundPlaybackState(revision, state));
PacketDistributor.sendToPlayer(player, new ClientboundReloadMusic(revision));
```

Remove `SimpleChannel`, `NetworkRegistry`, `NetworkDirection`, numeric message IDs, `requireChannel`, and both old `NetworkEvent.Context` handlers. Payload handlers run on the main thread by default in NeoForge 21.1.235.

- [ ] **Step 5: Verify codecs and full compilation**

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.playback.PlaybackStateTest --rerun-tasks --console=plain
.\gradlew.bat compileJava compileTestJava --console=plain
```

Expected: all codec tests pass and no `net.minecraftforge` imports remain.

### Task 6: Complete Minecraft 1.21.1 integration and restore all tests

**Files:**
- Modify only as required by mapped 1.21.1 signatures:
  - `src/main/java/datura/areamusic/area/AreaDefinition.java`
  - `src/main/java/datura/areamusic/area/AreaJsonCodec.java`
  - `src/main/java/datura/areamusic/area/AreaResolver.java`
  - `src/main/java/datura/areamusic/client/AreaMusicSoundOptions.java`
  - `src/main/java/datura/areamusic/client/ClientAreaMusic.java`
  - `src/main/java/datura/areamusic/server/AreaMusicCommands.java`
  - `src/main/java/datura/areamusic/server/AreaMusicServer.java`
- Modify corresponding tests only for API type/package changes, never expected behavior

- [ ] **Step 1: Run all tests to obtain the exact compatibility list**

Run:

```powershell
.\gradlew.bat test --rerun-tasks --console=plain
```

Expected: either all 66 tests pass or failures identify concrete 1.21.1 signature changes. Preserve these behavioral expectations while applying the exact replacements below.

- [ ] **Step 2: Apply the bounded 1.21.1 replacements**

Use `ResourceLocation.fromNamespaceAndPath` for AreaMusic-owned identifiers and keep `ResourceLocation.tryParse` for user/JSON input. Use `ServerPlayer#getServer`, `Player#level`, and `Level#dimension` accessors rather than removed fields. Keep `BlockPosArgument.blockPos`, `BlockPosArgument.getBlockPos`, `Commands.literal`, and the approved greedy final `StringArgumentType`.

For the sound screen, continue using `ScreenEvent.Init.Post#getListenersList`, `OptionsList#findOption`, `OptionsList#addSmall`, and the inherited mutable `children()` list. Retain these verified invariants:

```java
candidate.findOption(masterVolume) != null && candidate.findOption(voiceVolume) != null
```

and only replace the one-widget Voice row before moving the new last row into its position.

- [ ] **Step 3: Verify no loader leakage and all tests GREEN**

Run:

```powershell
Get-ChildItem -Path src -Recurse -File -Filter '*.java' |
    Select-String -Pattern 'net\.minecraftforge'
.\gradlew.bat test --rerun-tasks --console=plain
```

Expected: the search returns nothing and all 66 tests pass with zero failures or errors.

- [ ] **Step 4: Commit the native port checkpoint**

Run:

```powershell
git add build.gradle settings.gradle gradle.properties gradle gradlew gradlew.bat src/main src/test
git diff --cached --check
git commit -m "feat: port AreaMusic to NeoForge 1.21.1"
```

### Task 7: Verify packaging and cross-version data compatibility

**Files:**
- Verify: `src/main/resources/META-INF/AREA_MUSIC_THIRD_PARTY_NOTICES.txt`
- Verify: existing JSON and config fixtures under `src/test`
- Produce: `build/libs/areamusic-neoforge-1.21.1-0.0.1-all.jar`

- [ ] **Step 1: Run the clean NeoForge production pipeline**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build jarJar runGameTestServer --console=plain
```

Expected: 66 tests pass, the NeoForge GameTest server starts and exits successfully, and Gradle exits `0`.

- [ ] **Step 2: Inspect the all-in-one JAR**

Run:

```powershell
$jar='build\libs\areamusic-neoforge-1.21.1-0.0.1-all.jar'
& "$env:JAVA_HOME\bin\jar.exe" tf $jar |
    Select-String -Pattern 'datura/areamusic/|META-INF/jarjar/|test-48000|JCODEC-LICENSE'
Get-FileHash -Algorithm SHA256 -LiteralPath $jar
Get-Item -LiteralPath $jar | Select-Object FullName,Length,LastWriteTime
```

Expected: AreaMusic classes and six decoder/runtime JARs are present under `META-INF/jarjar`; `test-48000.mp3.b64` and `JCODEC-LICENSE.txt` are absent because they are test resources.

- [ ] **Step 3: Verify existing Forge data without modifying it**

Launch against the existing `run` directory and confirm that `areamusic`, `config/areamusic-client.toml`, and `config/areamusic/<SaveID>/*.json` load without rewritten JSON keys. If the former uppercase audio directory exists, treat it only as the source of the safe one-time migration to `areamusic`. Run `/areamusic reload` and confirm the logged track/area counts match the Forge build.

- [ ] **Step 4: Commit any packaging correction before acceptance testing**

Run `git status --short`. If Task 7 required a change to `build.gradle`, metadata, or notices, stage only those files, run `git diff --cached --check`, and commit them with:

```powershell
git commit -m "fix: package NeoForge audio dependencies"
```

If the working tree is already clean, record that no packaging correction commit was necessary.

### Task 8: Perform client and dedicated-server acceptance checks

**Files:**
- Verify only: `run/areamusic`, `run/config`, existing development save

- [ ] **Step 1: Launch the NeoForge client**

Run:

```powershell
.\gradlew.bat runClient --console=plain
```

In a world with command permission, verify:

```text
/areamusic create test_area <pos1> <pos2> 音乐 名称.mp3
/areamusic create nested_area <pos1> <pos2> 文件夹 名称/音乐 名称.ogg
/areamusic reload
```

Completion must insert both Music IDs without quotes. Creating the areas must write the exact raw relative paths to JSON.

- [ ] **Step 2: Verify audio behavior in the client**

Confirm the AreaMusic slider is present, persists after restart, ignores vanilla Music volume, obeys Master volume, crossfades between adjacent regions, fades out on exit, pauses with the game, and plays both existing 48 kHz MP3 files at the original duration/speed.

- [ ] **Step 3: Verify dedicated-server safety**

Run:

```powershell
.\gradlew.bat runGameTestServer --console=plain
```

Expected: the server constructs AreaMusic without loading `net.minecraft.client` classes, reports the existing music/area counts, completes GameTest, and shuts down normally.

### Task 9: Final review, documentation, and GitHub publication

**Files:**
- Modify: `docs/superpowers/plans/2026-07-10-area-music-neoforge-1.21.1.md` (mark completed verification steps and record evidence)
- Verify: complete committed diff on both branches

- [ ] **Step 1: Request and act on a focused code review**

Review the NeoForge branch against the approved design, with emphasis on payload bounds, client-only class loading, config compatibility, command completion, stream closure, and Jar-in-Jar metadata. Resolve every Critical or Important finding and rerun the affected test before continuing.

- [ ] **Step 2: Run final fresh verification after the last source change**

Run:

```powershell
.\gradlew.bat clean test build jarJar runGameTestServer --console=plain
git diff --check
git status --short --branch
```

Expected: all tasks succeed and only the plan evidence update is uncommitted.

- [ ] **Step 3: Record the artifact evidence and commit**

Write the final unit-test count, client/server checks, artifact path, byte size, and SHA-256 into this plan. Then run:

```powershell
git add docs/superpowers/plans/2026-07-10-area-music-neoforge-1.21.1.md
git commit -m "docs: record NeoForge 1.21.1 verification"
git status --short --branch
```

- [ ] **Step 4: Reverify the Forge branch pointer before publication**

Run:

```powershell
git log -1 --oneline forge-1.20.1
git log -1 --oneline neoforge-1.21.1
git merge-base --is-ancestor forge-1.20.1 neoforge-1.21.1
```

Expected: `forge-1.20.1` ends at the tested greedy-completion commit and is an ancestor of the NeoForge branch.

- [ ] **Step 5: Push both verified branches without force**

Run:

```powershell
git push -u origin forge-1.20.1
git push -u origin neoforge-1.21.1
```

Expected: both branches appear in `git@github.com:Rain156/AreaMusic.git`. Stop on any non-fast-forward rejection; never use `--force` or overwrite an existing remote branch.
