# AreaMusic

AreaMusic 是一个由服务端定义长方体区域、在玩家进入区域时播放本地音乐的 Minecraft 模组。区域可以同时播放多条音轨，也可以按列表顺序循环播放；每个区域还支持淡入淡出、重进续播和重叠区域优先级。

> English summary: AreaMusic lets a server define cuboid zones that trigger local audio on players' clients. It supports parallel tracks and ordered looping playlists.

## 重要说明

- 模组必须同时安装在服务端和每一位玩家的客户端。
- 音频文件不会通过网络传输或自动同步。服务端与所有客户端必须在各自的 `<gameDir>/areamusic/` 中准备相同 MusicID（相同的相对路径、文件名、扩展名和大小写）；各客户端使用本地文件独立解码和播放，不保证听到相同内容或保持帧级同步。
- 专用服务器不输出声音，但仍会扫描本地音频目录，用于校验 MusicID 和命令补全。
- Fabric 版本必须另外安装下表对应的 Fabric API；Forge 与 NeoForge 版本不需要额外库模组。
- 项目采用类似 Architectury 的 `shared core/common + loader leaf` 多加载器结构，但没有 Architectury 运行时依赖，安装时也不需要 Architectury API。

## 支持版本

以下是当前已经完成并验证的版本；加载器与 API 测试版本来自 [`gradle.properties`](gradle.properties)。

| Minecraft | 加载器 | 测试版本 | Java | 额外依赖 |
| --- | --- | --- | --- | --- |
| 1.20.1 | Fabric | Fabric Loader 0.19.3 | 17 | Fabric API 0.92.11+1.20.1 |
| 1.20.1 | Forge | Forge 47.4.22 | 17 | 无 |
| 1.21.1 | Fabric | Fabric Loader 0.19.3 | 21 | Fabric API 0.116.14+1.21.1 |
| 1.21.1 | Forge | Forge 52.1.16 | 21 | 无 |
| 1.21.1 | NeoForge | NeoForge 21.1.244 | 21 | 无 |

## 安装

1. 根据 Minecraft 版本和加载器选择对应的 `areamusic-<loader>-<minecraft>-<mod>.jar`。
2. 把同一份 AreaMusic JAR 放入服务端和每个客户端的 `mods/`。
3. 使用 Fabric 时，还要在服务端和每个客户端安装对应版本的 Fabric API。
4. 启动一次游戏或服务器，让模组创建小写的 `areamusic/` 与配置目录。
5. 将音频放入每台机器各自的 `<gameDir>/areamusic/`；相同 MusicID 必须对应相同的相对路径。
6. 进入世界后执行 `/areamusic reload`，再创建或编辑区域。

支持 `.ogg`、`.mp3`、`.wav` 和 `.flac`。MusicID 是带扩展名的相对路径，嵌套目录统一使用 `/`，例如 `village/day.mp3`。

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

- `<gameDir>/areamusic/`：本机音频根目录。
- `config/areamusic/<saveId>/*.json`：当前存档的区域定义；文件名就是区域 ID。
- `config/areamusic-client.toml`：客户端 AreaMusic 独立音量配置。
- 旧的 `<gameDir>/AreaMusic/` 会在没有冲突时安全迁移为小写目录；如果大小写目录同时存在，请先备份并手动合并。

## 命令

```text
/areamusic create <areaId> <pos1> <pos2> <musicId>
/areamusic reload
```

`pos1` 与 `pos2` 均为 `x y z`，例如：

```text
/areamusic create spawn 0 64 0 10 80 10 ambient.ogg
```

`create` 会生成一个使用默认参数的单音轨区域。编辑生成的 JSON 后，执行 `/areamusic reload` 重新扫描音频并加载区域。命令需要权限等级 2；区域 ID 必须匹配 `[a-z0-9][a-z0-9_-]{0,63}`。

## 区域 JSON（schema v3）

AreaMusic 仍可读取 schema v1 和 v2；新建或重新写入的区域统一使用 schema v3。解析器会拒绝未知字段、重复字段和错误类型。

所有模式通用的区域字段：

| 字段 | 必填/默认值 | 说明 |
| --- | --- | --- |
| `schemaVersion` | 必填：`3` | 当前写入格式 |
| `dimension` | 必填 | 维度 ID，例如 `minecraft:overworld` |
| `pos1` / `pos2` | 必填 | 两个整数坐标端点 |
| `playbackMode` | 必填 | `parallel` 或 `playlist_loop` |
| `resumeOnReenter` | `false` | 再次进入时是否恢复该区域的播放状态 |
| `priority` | `0` | 重叠区域中数值更高者优先 |

### 同时播放：`parallel`

`tracks` 必须包含 1–16 条音轨。每条音轨的默认值是：`delaySeconds: 0`、`volume: 1.0`、`loop: true`、`fadeInMs: 2000`、`fadeOutMs: 2000`。

```json
{
  "schemaVersion": 3,
  "dimension": "minecraft:overworld",
  "pos1": { "x": 10, "y": 80, "z": 10 },
  "pos2": { "x": 0, "y": 60, "z": 0 },
  "playbackMode": "parallel",
  "tracks": [
    {
      "musicId": "ambient.ogg",
      "delaySeconds": 0,
      "volume": 1.0,
      "loop": true,
      "fadeInMs": 2000,
      "fadeOutMs": 2000
    },
    {
      "musicId": "voice/intro.mp3",
      "delaySeconds": 5,
      "volume": 0.8,
      "loop": false,
      "fadeInMs": 0,
      "fadeOutMs": 1000
    }
  ],
  "resumeOnReenter": true,
  "priority": 5
}
```

每条音轨独立计时、控制音量、循环和淡入淡出。`delaySeconds` 是进入区域后等待的非负整数秒；`volume` 范围为 0–1；淡入淡出范围为 0–60000 毫秒。

### 列表循环：`playlist_loop`

`playlist` 必须包含 1–256 个有序 MusicID，可以重复。模组按照第一首、第二首、第三首……的顺序播放，播完最后一首后回到第一首继续循环。

列表模式不使用 `tracks`，也不需要填写默认 `track` 或根级 `musicId`。整个列表统一使用 `volume`、`fadeInMs` 和 `fadeOutMs`；默认值分别为 `1.0`、`2000`、`2000`。

```json
{
  "schemaVersion": 3,
  "dimension": "minecraft:overworld",
  "pos1": { "x": -20, "y": 50, "z": -20 },
  "pos2": { "x": 20, "y": 100, "z": 20 },
  "playbackMode": "playlist_loop",
  "playlist": [
    "village/day.ogg",
    "village/evening.ogg",
    "village/day.ogg"
  ],
  "volume": 0.75,
  "fadeInMs": 1500,
  "fadeOutMs": 2500,
  "resumeOnReenter": false,
  "priority": 0
}
```

### `pos1` / `pos2` 保序

`pos1` 和 `pos2` 是用户选择的原始端点，不是自动排序后的最小点与最大点。AreaMusic 读取、保存和重新写入 JSON 时会保持两个端点各自的原值和顺序；只有判断玩家是否在区域内、计算区域体积和比较边界时，才会逐轴使用 `min` / `max`。因此坐标交叉或从大坐标选到小坐标时，JSON 也不会再出现两个端点被混合重组的问题。

## 重叠、续播与音量

- 区域重叠时先比较 `priority`，再选择体积更小的区域，最后按区域 ID 排序。
- `resumeOnReenter: false` 会在离开后淡出，再进入时从头开始并重新计算延迟。
- `resumeOnReenter: true` 会在当前客户端运行期间保留各音轨或列表的播放位置；重启客户端、成功重载音乐库或修改播放定义后不应依赖旧进度。
- 最终音量由 Minecraft 主音量、AreaMusic 独立音量和 JSON 中的 `volume` 共同决定；原版“音乐”音量不影响 AreaMusic。

## 构建

需要同时可用的 JDK 17 与 JDK 21 工具链。Windows：

```powershell
.\gradlew.bat test check assemble build
```

Linux/macOS：

```bash
./gradlew test check assemble build
```

可安装成品位于五个 loader leaf 的 `build/libs/`：

```text
platforms/1.20.1/fabric/build/libs/areamusic-fabric-1.20.1-<mod_version>.jar
platforms/1.20.1/forge/build/libs/areamusic-forge-1.20.1-<mod_version>.jar
platforms/1.21.1/fabric/build/libs/areamusic-fabric-1.21.1-<mod_version>.jar
platforms/1.21.1/forge/build/libs/areamusic-forge-1.21.1-<mod_version>.jar
platforms/1.21.1/neoforge/build/libs/areamusic-neoforge-1.21.1-<mod_version>.jar
```

`common`、`dev` 和 `sources` JAR 不是安装包。GitHub Release 由 `v<mod_version>` 标签或手动工作流触发，附带上述五个成品和 `SHA256SUMS`。

## 许可证

AreaMusic 使用 [MIT License](LICENSE)。发布 JAR 内嵌第三方音频解码运行库；其许可证与归属信息见 JAR 内的 `META-INF/AREA_MUSIC_THIRD_PARTY_NOTICES.txt`。
