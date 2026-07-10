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

- [ ] Assert the gain is `master × AreaMusic`, including zero and partial values.
- [ ] Assert the client config defaults to `1.0`, writes updates to its attached config data, and clamps programmatic input to `[0, 1]`.
- [ ] Run the focused tests and confirm they fail because production types do not exist.

### Task 2: Implement the config and gain core

**Files:**
- Create: `src/main/java/datura/areamusic/config/AreaMusicClientConfig.java`
- Create: `src/main/java/datura/areamusic/client/AreaMusicVolume.java`
- Modify: `src/main/java/datura/areamusic/AreaMusic.java`
- Modify: `src/main/java/datura/areamusic/client/ClientAreaMusic.java`

- [ ] Define and register `config/areamusic-client.toml` as a Forge `CLIENT` config.
- [ ] Implement bounded volume reads/writes and immediate saving.
- [ ] Replace `master × SoundSource.MUSIC` with `master × AreaMusicClientConfig.volume()` through the tested helper.
- [ ] Run focused tests and `compileJava` until green.

### Task 3: Add the sound-options slider

**Files:**
- Create: `src/main/java/datura/areamusic/client/AreaMusicSoundOptions.java`
- Modify: `src/main/resources/assets/areamusic/lang/en_us.json`
- Modify: `src/main/resources/assets/areamusic/lang/zh_cn.json`

- [ ] Create a vanilla-style 0–100% `OptionInstance<Double>` bound to the client config.
- [ ] On `SoundOptionsScreen` initialization, locate its `OptionsList` through the Forge event listener list.
- [ ] Fill the unused right-hand cell beside Voice when safe; otherwise append a new row.
- [ ] Add English and Simplified Chinese labels.
- [ ] Compile and rerun all unit tests.

### Task 4: Verify behavior and package the mod

- [ ] Run `clean test build reobfJarJar runGameTestServer`.
- [ ] Inspect the all-in-one JAR and confirm decoder dependencies remain embedded.
- [ ] Launch the development client and verify the slider visually.
- [ ] Verify persistence after reopening the screen/client.
- [ ] Verify vanilla Music at 0% leaves AreaMusic audible and Master at 0% silences it.
- [ ] Review the diff and deliver the updated `build/libs/areamusic-0.0.1-all.jar`.
