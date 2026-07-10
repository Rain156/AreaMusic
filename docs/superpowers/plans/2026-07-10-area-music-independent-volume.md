# AreaMusic Independent Volume Implementation Plan

> **For agentic workers:** Follow test-driven development and verification-before-completion for every behavior change.

**Goal:** Add a persistent AreaMusic volume slider to Minecraft's sound options while removing all dependence on the vanilla music volume.

**Architecture:** A Forge client config owns the independent volume. A client screen event inserts a vanilla-style `OptionInstance` into the existing `SoundOptionsScreen` list. A pure gain helper combines only Minecraft master volume and AreaMusic volume, keeping the mixer unchanged.

**Tech Stack:** Java 17, Forge 47.4.21, Minecraft 1.20.1, ForgeConfigSpec, JUnit Jupiter 5.10.2.

---

### Task 1: Specify independent gain and config behavior with failing tests

**Files:**
- Create: `src/test/java/datura/areamusic/client/AreaMusicVolumeTest.java`
- Create: `src/test/java/datura/areamusic/config/AreaMusicClientConfigTest.java`

- [x] Assert the gain is `master × AreaMusic`, including zero and partial values.
- [x] Assert the client config defaults to `1.0`, writes updates to its attached config data, and clamps programmatic input to `[0, 1]`.
- [x] Run the focused tests and confirm they fail because production types do not exist.

### Task 2: Implement the config and gain core

**Files:**
- Create: `src/main/java/datura/areamusic/config/AreaMusicClientConfig.java`
- Create: `src/main/java/datura/areamusic/client/AreaMusicVolume.java`
- Modify: `src/main/java/datura/areamusic/AreaMusic.java`
- Modify: `src/main/java/datura/areamusic/client/ClientAreaMusic.java`

- [x] Define and register `config/areamusic-client.toml` as a Forge `CLIENT` config.
- [x] Implement bounded volume reads/writes and immediate saving.
- [x] Replace `master × SoundSource.MUSIC` with `master × AreaMusicClientConfig.volume()` through the tested helper.
- [x] Run focused tests and `compileJava` until green.

### Task 3: Add the sound-options slider

**Files:**
- Create: `src/main/java/datura/areamusic/client/AreaMusicSoundOptions.java`
- Modify: `src/main/resources/assets/areamusic/lang/en_us.json`
- Modify: `src/main/resources/assets/areamusic/lang/zh_cn.json`

- [x] Create a vanilla-style 0–100% `OptionInstance<Double>` bound to the client config.
- [x] On `SoundOptionsScreen` initialization, locate its `OptionsList` through the Forge event listener list.
- [x] Fill the unused right-hand cell beside Voice when safe; otherwise append a new row.
- [x] Add English and Simplified Chinese labels.
- [x] Compile and rerun all unit tests.

### Task 4: Verify behavior and package the mod

- [x] Run `clean test build reobfJarJar runGameTestServer`.
- [x] Inspect the all-in-one JAR and confirm decoder dependencies remain embedded.
- [x] Launch the development client and verify the slider visually.
- [x] Verify persistence after reopening the screen/client.
- [x] Verify vanilla Music at 0% leaves AreaMusic audible and Master at 0% silences it.
- [ ] Review the diff and deliver the updated `build/libs/areamusic-0.0.1-all.jar`.
