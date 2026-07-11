# AreaMusic NeoForge 1.21.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the verified Forge 1.20.1 release and produce a feature- and data-compatible native NeoForge 1.21.1 release with raw, unquoted greedy Music ID completion.

**Architecture:** Maintain one complete branch per loader and Minecraft version. First add the loader-independent command behavior to `forge-1.20.1`, then branch `neoforge-1.21.1` and replace only build, event, configuration, networking, and changed Minecraft integration APIs; keep storage and the independent Java Sound mixer intact.

**Tech Stack:** Java 17 for Forge 1.20.1, Java 21 for NeoForge 1.21.1, NeoForge 21.1.235, ModDevGradle 2.0.141, Gradle 9.2.1, Brigadier, Java Sound, JUnit Jupiter 5.10.2.

---

### Task 1: Secure the repository baseline and establish the Forge branch

**Files:**
- Verify: repository state only; no source changes

- [x] **Step 1: Verify the local baseline is clean**

Run:

```powershell
git status --short --branch
git log -5 --oneline --decorate
```

Expected: the working tree has no changes and `HEAD` contains the approved design and this plan. No branch, commit, tag, file, or artifact name may have an automated-tool prefix.

- [x] **Step 2: Re-run the verified Forge baseline**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build reobfJarJar runGameTestServer --console=plain
```

Expected: 64 tests pass, the all-in-one Forge JAR builds, and the Forge GameTest server exits successfully.

- [x] **Step 3: Inspect and configure the GitHub remote without overwriting it**

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

- [x] **Step 4: Rename the maintained Forge branch**

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

- [x] **Step 1: Write failing command structure and suggestion tests**

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

- [x] **Step 2: Run the test and confirm RED**

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.server.AreaMusicCommandsTest --rerun-tasks --console=plain
```

Expected: test compilation fails because the iterable `suggestMusicIds` overload does not exist, and the old command uses `QUOTABLE_PHRASE` rather than `GREEDY_PHRASE`.

- [x] **Step 3: Implement greedy raw completion**

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

- [x] **Step 4: Verify GREEN and all Forge behavior**

Run:

```powershell
.\gradlew.bat test --tests datura.areamusic.server.AreaMusicCommandsTest --rerun-tasks --console=plain
.\gradlew.bat clean test build reobfJarJar runGameTestServer --console=plain
```

Expected: both new tests pass, the full suite increases from 64 to 66 passing tests, and the Forge production pipeline succeeds.

- [x] **Step 5: Commit the cross-version command behavior**

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

- [x] **Step 1: Create the version branch from the verified Forge head**

Run:

```powershell
git switch -c neoforge-1.21.1
git merge-base --is-ancestor forge-1.20.1 neoforge-1.21.1
git status --short --branch
```

Expected: the branch is exactly `neoforge-1.21.1`, contains the command commit, and is clean.

- [x] **Step 2: Select Java 21**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

Expected: Java 21.0.9 is active.

- [x] **Step 3: Replace settings and version properties**

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

- [x] **Step 4: Replace ForgeGradle with ModDevGradle**

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

- [x] **Step 5: Replace loader metadata and resource pack metadata**

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

- [x] **Step 6: Upgrade the wrapper and confirm the intentional RED build**

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

- [x] **Step 1: Port the entry point and config registration**

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

- [x] **Step 2: Port `ForgeConfigSpec` without changing its file format**

In `AreaMusicClientConfig` and its test, replace `net.minecraftforge.common.ForgeConfigSpec` with `net.neoforged.neoforge.common.ModConfigSpec`, and rename the declared types accordingly:

```java
private final ModConfigSpec spec;
private final ModConfigSpec.DoubleValue volume;
```

Keep the path `volume`, default `1.0`, range `[0.0, 1.0]`, explicit file name `areamusic-client.toml`, clamp, and immediate `save()` behavior unchanged.

- [x] **Step 3: Port server events**

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

- [x] **Step 4: Port client event classes and paths**

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

- [x] **Step 5: Re-run compilation and isolate network failures**

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

- [x] **Step 1: Change codec tests to the desired payload API and verify RED**

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

- [x] **Step 2: Implement `ClientboundReloadMusic` as a payload**

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

- [x] **Step 3: Implement the playback-state payload**

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

- [x] **Step 4: Register and send payloads natively**

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

- [x] **Step 5: Verify codecs and full compilation**

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

- [x] **Step 1: Run all tests to obtain the exact compatibility list**

Run:

```powershell
.\gradlew.bat test --rerun-tasks --console=plain
```

Expected: the original port suite has 66 tests; after the two server reload-status tests added later, the final verified suite has 68 tests. Any failures identify concrete 1.21.1 signature changes. Preserve these behavioral expectations while applying the exact replacements below.

- [x] **Step 2: Apply the bounded 1.21.1 replacements**

Use `ResourceLocation.fromNamespaceAndPath` for AreaMusic-owned identifiers and keep `ResourceLocation.tryParse` for user/JSON input. Use `ServerPlayer#getServer`, `Player#level`, and `Level#dimension` accessors rather than removed fields. Keep `BlockPosArgument.blockPos`, `BlockPosArgument.getBlockPos`, `Commands.literal`, and the approved greedy final `StringArgumentType`.

For the sound screen, continue using `ScreenEvent.Init.Post#getListenersList`, `OptionsList#findOption`, `OptionsList#addSmall`, and the inherited mutable `children()` list. Retain these verified invariants:

```java
candidate.findOption(masterVolume) != null && candidate.findOption(voiceVolume) != null
```

and only replace the one-widget Voice row before moving the new last row into its position.

- [x] **Step 3: Verify no loader leakage and all tests GREEN**

Run:

```powershell
Get-ChildItem -Path src -Recurse -File -Filter '*.java' |
    Select-String -Pattern 'net\.minecraftforge'
.\gradlew.bat test --rerun-tasks --console=plain
```

Expected: the search returns nothing and the final 68-test suite passes with zero failures or errors (the original 66 plus two server reload-status tests added later).

- [x] **Step 4: Commit the native port checkpoint**

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

- [x] **Step 1: Run the clean NeoForge production pipeline**

Run:

```powershell
$env:JAVA_HOME='F:\Dev\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build jarJar runGameTestServer --console=plain
```

Expected: the final 68 tests pass (the original plan's 66 plus two server reload-status tests added later), the NeoForge GameTest server starts and exits successfully, and Gradle exits `0`.

- [x] **Step 2: Inspect the all-in-one JAR**

Run:

```powershell
$jar='build\libs\areamusic-neoforge-1.21.1-0.0.1-all.jar'
& "$env:JAVA_HOME\bin\jar.exe" tf $jar |
    Select-String -Pattern 'datura/areamusic/|META-INF/jarjar/|test-48000|JCODEC-LICENSE'
Get-FileHash -Algorithm SHA256 -LiteralPath $jar
Get-Item -LiteralPath $jar | Select-Object FullName,Length,LastWriteTime
```

Expected: AreaMusic classes and six decoder/runtime JARs are present under `META-INF/jarjar`; `test-48000.mp3.b64` and `JCODEC-LICENSE.txt` are absent because they are test resources.

- [x] **Step 3: Verify existing Forge data without modifying it**

Launch against the existing `run` directory and confirm that `AreaMusic`, `config/areamusic-client.toml`, and `config/areamusic/<SaveID>/*.json` load without migration or rewritten keys. Run `/areamusic reload` and confirm the logged track/area counts match the Forge build.

- [x] **Step 4: Commit any packaging correction before acceptance testing**

Run `git status --short`. If Task 7 required a change to `build.gradle`, metadata, or notices, stage only those files, run `git diff --cached --check`, and commit them with:

```powershell
git commit -m "fix: package NeoForge audio dependencies"
```

If the working tree is already clean, record that no packaging correction commit was necessary.

### Task 8: Perform client and dedicated-server acceptance checks

**Files:**
- Verify only: `run/AreaMusic`, `run/config`, existing development save

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

- [x] **Step 3: Verify dedicated-server safety**

Run:

```powershell
.\gradlew.bat runGameTestServer --console=plain
```

Expected: the server constructs AreaMusic without loading `net.minecraft.client` classes, reports the existing music/area counts, completes GameTest, and shuts down normally.

Verification status (2026-07-11): automated and runtime-objective checks passed, while the two client steps above remain open for human confirmation. The retained RED log records `ClassNotFoundException: javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider`; the GREEN client logs record Java 21.0.9, 7 local tracks, 7 server tracks and 2 areas, with no AreaMusic `ERROR` or `ClassNotFoundException`. The F3 screenshot at block `(-10, -58, -7)` is inside area 1's bounds, and the thread dump reaches the Java Sound `DirectDL.write` implementation and `PcmAudioMixer$JavaSoundOutput.write`. This is objective playback-path evidence only: slider/completion GUI behavior and audible volume, crossfade, fade-out, pause, and original-speed behavior still require human observation/listening and are not claimed as heard.

### Task 9: Final review, documentation, and GitHub publication

**Files:**
- Modify: `docs/superpowers/plans/2026-07-10-area-music-neoforge-1.21.1.md` (mark completed verification steps and record evidence)
- Verify: complete committed diff on both branches

- [x] **Step 1: Request and act on a focused code review**

Review the NeoForge branch against the approved design, with emphasis on payload bounds, client-only class loading, config compatibility, command completion, stream closure, and Jar-in-Jar metadata. Resolve every Critical or Important finding and rerun the affected test before continuing.

- [x] **Step 2: Run final fresh verification after the last source change**

Run:

```powershell
.\gradlew.bat clean test build jarJar runGameTestServer --console=plain
git diff --check
git status --short --branch
```

Expected: all tasks succeed and only the plan evidence update is uncommitted.

- [x] **Step 3: Record the artifact evidence and commit**

Write the final unit-test count, client/server checks, artifact path, byte size, and SHA-256 into this plan. Then run:

```powershell
git add docs/superpowers/plans/2026-07-10-area-music-neoforge-1.21.1.md
git commit -m "docs: record NeoForge 1.21.1 verification"
git status --short --branch
```

- [x] **Step 4: Reverify the Forge branch pointer before publication**

Run:

```powershell
git log -1 --oneline forge-1.20.1
git log -1 --oneline neoforge-1.21.1
git merge-base --is-ancestor forge-1.20.1 neoforge-1.21.1
```

Expected: `forge-1.20.1` ends at the tested greedy-completion commit and is an ancestor of the NeoForge branch.

- [x] **Step 5: Push both verified branches without force**

Run:

```powershell
git push -u origin forge-1.20.1
git push -u origin neoforge-1.21.1
```

Expected: both branches appear in `git@github.com:Rain156/AreaMusic.git`. Stop on any non-fast-forward rejection; never use `--force` or overwrite an existing remote branch.

#### Final verification evidence

Recorded on 2026-07-11:

- Review fixes: client development audio providers were repaired in `b04bf2a3196602de5005da7a11b48976a117bf46`; configuration-cache-safe path handling and the narrowed `Exception | LinkageError` provider boundary were committed in `dab9813a56aa51669358c95ff72c418852cd07dd`. The verifier's actual RED failed on execution-time `file(it)`; two GREEN runs exited 0, and the second reused the configuration cache. Client classpaths contain each of the six audio artifacts once; server and GameTest classpaths contain none.
- Forge 1.20.1: Java 17 ran `.\gradlew.bat clean test build reobfJarJar runGameTestServer --console=plain` from detached `e9058ddcfc088d7e129b837befdcbc16e6dd7f35`, exit 0. JUnit XML reports 19 suites, 66 tests, 0 failures, 0 errors, and 0 skipped. This Forge baseline registers 0 GameTests; all 0 required tests passed, it loaded 0 tracks and 0 areas in the fresh isolated run, and shut down normally. The first isolated launch logged expected first-start `ERROR` noise and `NoSuchFileException` because `server.properties` did not yet exist; Forge then generated the file, exited 0, and shut down normally.
- Forge artifact: `build/libs/areamusic-0.0.1-all.jar` on `forge-1.20.1`, 557,934 bytes, SHA-256 `F0B046F95526D13BD9FE87275A2335BF05BE3EFF7E2B9F4F1AF63D8B7D7C123D`. This digest corresponds to the final local clean build on 2026-07-11; an earlier clean build produced `bf5c1865f282cf97a26d25f663c86176388ea2bd9fb133a0789245e6ecb66666`, so the ForgeGradle archive hash is not stable across clean rebuilds. The JAR contains AreaMusic classes, the expected `META-INF` files, exactly six nested audio JARs with matching Jar-in-Jar metadata, and no compressed-audio test fixture.
- NeoForge 1.21.1: Java 21 ran `.\gradlew.bat clean test build jarJar runGameTestServer --console=plain`, exit 0. JUnit XML reports 20 suites, 68 tests, 0 failures, 0 errors, and 0 skipped. GameTest passed 1/1, logged `Loaded 7 AreaMusic tracks and 2 areas`, saved all dimensions, and shut down normally.
- NeoForge artifact: `build/libs/areamusic-neoforge-1.21.1-0.0.1-all.jar` on `neoforge-1.21.1`, 563,832 bytes, SHA-256 `66d461b1f7ba4484a9b5679799e6b3c19f1e2fafb6328ba2416290424ba3d5a2`. It is the only `*-all.jar`, contains 55 AreaMusic classes plus the expected `META-INF` files, exactly six nested audio JARs and six matching metadata entries, and no compressed-audio test fixture.
- Server/data isolation: both server runs used linked-worktree-local `run` directories. The seven source Forge audio files and five area JSON files match the isolated NeoForge copies byte-for-byte, the 98-byte source client TOML matches the retained isolated backup, and all 13 source hashes remained unchanged across final verification. Only the isolated save was allowed to change.
- Remote publication: on 2026-07-11 both verified branches were published without force to `git@github.com:Rain156/AreaMusic.git`: `origin/forge-1.20.1` is `e9058ddcfc088d7e129b837befdcbc16e6dd7f35`, and `origin/neoforge-1.21.1` is `accc88af89f659e8f9d0d3bbac5827e1a7b52f60`.
- Remaining manual acceptance: Task 8 client Steps 1 and 2 stay unchecked. Human GUI and listening confirmation is still required; the retained logs, screenshot, and thread dump are not represented as audible proof.
