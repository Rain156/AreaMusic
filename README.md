# AreaMusic — Forge 1.20.1

*Server-defined local music areas with multitrack, delayed playback, and resume support.*

AreaMusic 允许服务器用长方体区域控制玩家客户端上的本地音乐。玩家进入区域后，可同时播放多条音轨，并为每条音轨分别设置延迟、音量、循环和淡入淡出；离开后再次进入时，也可以选择从上次进度继续。

## 功能

- 每个区域可配置 1–16 条音轨。
- 每条音轨可独立设置进入区域后的播放延迟，默认立即播放。
- 支持 OGG、MP3、WAV 和 FLAC。
- 支持循环、一次性播放、淡入、淡出和区域切换时的交叉淡化。
- `resumeOnReenter` 可控制再次进入同一区域时是否继续播放进度。
- 重叠区域按优先级确定当前区域。
- 服务端只同步区域和播放状态，不传输音频文件。
- AreaMusic 音量独立于原版“音乐”音量，并受“主音量”控制。

## 运行要求

| 项目 | 要求 |
| --- | --- |
| Minecraft | 1.20.1 |
| 模组加载器 | Forge 47.4.21 |
| Java | 17 |
| 安装位置 | 服务端与每一位玩家的客户端 |

服务端和所有客户端必须拥有相同的本地 MusicID，也就是 `areamusic` 下相同的相对路径与文件名。音频内容不会通过网络发送；专用服务器虽然不播放声音，仍会扫描本地文件来校验 MusicID 和提供命令补全。

## 安装

1. 将 `areamusic-0.0.1.jar` 放入服务端和每个客户端的 `mods/`。
2. 启动一次游戏或服务器，让模组创建配置和小写的 `areamusic/` 目录。
3. 把需要的音频放进服务端及所有客户端各自的 `<gameDir>/areamusic/`；相对路径、扩展名和大小写应保持一致。
4. 进入世界后执行 `/areamusic reload`，然后创建区域。

## 目录结构

```text
<gameDir>/
├─ areamusic/
│  ├─ ambient.ogg
│  └─ village/
│     └─ day.mp3
└─ config/
   ├─ areamusic-client.toml
   └─ areamusic/
      └─ <saveId>/
         └─ village.json
```

- `<gameDir>/areamusic`：服务端或客户端的本地音频根目录。
- `config/areamusic/<saveId>/*.json`：当前存档的区域定义；JSON 文件名就是区域 ID。
- `config/areamusic-client.toml`：客户端 AreaMusic 独立音量配置。
- MusicID 是带扩展名的相对路径，嵌套目录统一使用 `/`，例如 `village/day.mp3`。

## 快速开始

先在服务端和每个客户端的 `areamusic/` 中放入 `ambient.ogg`，执行重载，然后在游戏中运行：

```text
/areamusic create spawn 0 64 0 10 80 10 ambient.ogg
```

该命令会立即创建一个使用默认参数的单音轨区域。要添加更多音轨或调整延迟，请编辑当前存档目录下生成的 `spawn.json`，保存后执行：

```text
/areamusic reload
```

区域 ID 必须匹配 `[a-z0-9][a-z0-9_-]{0,63}`。MusicID 是 `create` 的最后一个参数，因此可以包含空格，也必须包含文件扩展名。

## 命令与权限

| 命令 | 作用 |
| --- | --- |
| `/areamusic create <areaId> <pos1> <pos2> <musicId>` | 在当前维度创建区域；`pos1` 和 `pos2` 均为 `x y z` |
| `/areamusic reload` | 重新扫描服务端/客户端音乐目录并重新加载当前存档的区域 JSON |

两个命令都要求权限等级 2（单人游戏启用作弊，或多人服务器管理员权限）。

## 区域 JSON（schema v2）

下面是完整的多音轨示例。第一条音轨省略了可选字段，因此会在进入区域时立即按默认参数播放；第二条音轨会在进入 5 秒后开始播放。

```json
{
  "schemaVersion": 2,
  "dimension": "minecraft:overworld",
  "pos1": { "x": 0, "y": 64, "z": 0 },
  "pos2": { "x": 10, "y": 80, "z": 10 },
  "tracks": [
    { "musicId": "ambient.ogg" },
    {
      "musicId": "voice.mp3",
      "delaySeconds": 5,
      "volume": 0.8,
      "loop": false,
      "fadeInMs": 0,
      "fadeOutMs": 1000
    }
  ],
  "resumeOnReenter": true,
  "priority": 0
}
```

### 字段与默认值

| 范围 | 字段 | 必填/默认值 | 说明 |
| --- | --- | --- | --- |
| 区域 | `schemaVersion` | 必填：`2` | 当前写入格式 |
| 区域 | `dimension` | 必填 | 维度 ID，例如 `minecraft:overworld` |
| 区域 | `pos1` / `pos2` | 必填 | 两个端点，`x`、`y`、`z` 都必须是整数；顺序不限 |
| 区域 | `tracks` | 必填 | 包含 1–16 个音轨对象 |
| 区域 | `resumeOnReenter` | `false` | 离开后再次进入是否恢复该区域的播放状态 |
| 区域 | `priority` | `0` | 重叠区域中数值更高者优先 |
| 音轨 | `musicId` | 必填 | `areamusic/` 下带扩展名的相对路径 |
| 音轨 | `delaySeconds` | `0` | 进入区域后等待多少秒开始播放；必须是非负整数 |
| 音轨 | `volume` | `1.0` | 单轨音量，范围 0.0–1.0 |
| 音轨 | `loop` | `true` | 播放结束后是否循环 |
| 音轨 | `fadeInMs` | `2000` | 淡入毫秒数，范围 0–60000 |
| 音轨 | `fadeOutMs` | `2000` | 淡出毫秒数，范围 0–60000 |

当多个区域重叠时，先选择 `priority` 更高的区域；优先级相同时选择体积更小的区域；仍相同时按区域 ID 排序。解析器会拒绝未知字段和错误类型，以便尽早发现拼写错误。

旧的 schema v1 单音轨 JSON 仍可读取，并会在内存中映射为一条无延迟音轨；新建和重新写入的区域使用 schema v2。

## 延迟与再次进入

- 每条音轨的 `delaySeconds` 都从进入区域时开始独立计时；`0` 表示立即播放。
- `resumeOnReenter: false`（默认）：离开时按 `fadeOutMs` 淡出，再次进入会从音频开头重新开始，并重新计算完整延迟。
- `resumeOnReenter: true`：再次进入同一区域时恢复各音轨的播放游标；尚未播放的音轨会保留剩余延迟，离开期间不会继续倒计时。
- 对于 `loop: false` 的音轨，若它已经播放完毕，开启续播后再次进入仍保持完成状态，不会自动重播。
- 续播状态按区域分别保存，只存在于当前客户端运行期间；重启客户端、成功重载音乐库或修改区域播放定义后，不应依赖旧进度继续。

## 音量

实际输出音量由以下三项共同决定：

```text
Minecraft 主音量 × AreaMusic 独立音量 × 单轨 volume
```

原版“音乐”音量不会影响 AreaMusic。可以在声音设置中的“AreaMusic 音量”调节，也可以修改 `config/areamusic-client.toml`；“主音量”仍会影响最终输出。

## 小写目录与旧版本迁移

规范目录固定为 `<gameDir>/areamusic`，模组不会再创建大写的 `AreaMusic`。

如果只存在旧的 `<gameDir>/AreaMusic`，模组会在扫描时安全地将整个目录迁移为小写名称，不覆盖已有目录。如果大小写两个目录同时存在且实际不是同一个目录，模组会停止迁移并报告冲突，避免静默覆盖文件。此时请先备份并手动合并内容，只保留小写的 `areamusic`。音乐根目录不能是符号链接。

## 常见问题

- **进入区域没有声音**：确认客户端已安装模组，MusicID 对应文件位于客户端小写的 `areamusic/`，主音量和 AreaMusic 音量都不为 0，并执行 `/areamusic reload`。
- **创建区域提示未知 MusicID**：确认服务端也有该文件，路径大小写和扩展名完全一致，然后重载。
- **重载 JSON 失败**：检查 `schemaVersion`、字段类型、1–16 条音轨限制，以及 JSON 中是否存在拼错或不支持的字段；日志会指出失败文件。
- **目录迁移冲突**：不要直接删除任何一侧；先备份、合并 `AreaMusic` 与 `areamusic`，再只保留小写目录。
- **某种音频没有被识别**：仅支持 `.ogg`、`.mp3`、`.wav` 和 `.flac`。避免仅大小写不同的重复 MusicID。

## 构建

需要 JDK 17。Windows：

```powershell
.\gradlew.bat clean build
```

Linux/macOS 使用 `./gradlew clean build`。发布 JAR 位于 `build/libs/areamusic-0.0.1.jar`。

## 许可与第三方解码器

AreaMusic 本体采用 **All Rights Reserved**。发布 JAR 内包含音频解码运行库；其许可与归属请查看 [`AREA_MUSIC_THIRD_PARTY_NOTICES.txt`](src/main/resources/META-INF/AREA_MUSIC_THIRD_PARTY_NOTICES.txt)。

## English quick summary

AreaMusic lets a server define cuboid zones that trigger 1–16 local audio tracks per zone. Each track supports an independent delay, volume, loop flag, and fades; `resumeOnReenter` optionally restores playback after leaving and returning.

Install the mod on the server and every client, and keep matching MusicIDs under lowercase `<gameDir>/areamusic`. Audio bytes are never sent over the network. This branch targets Minecraft 1.20.1, Forge 47.4.21, and Java 17.
