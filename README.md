# AreaMusic

在世界里画出区域，玩家走进去就播放你指定的音乐。区域在游戏内直接创建和调整，音乐由服务器自动分发给玩家。

> English summary: AreaMusic plays music inside areas you draw in the world. Areas are created and tuned with an in-game editor, and the server distributes the audio files to players automatically. Supports looping tracks, ordered playlists and layered tracks.

## 三步建好一个区域

1. 按 `F7` 打开编辑器，点“新建区域”。面板会让开，让你看清世界。
2. 左键点一个角，右键点另一个角。点完面板自己回来。
3. 选一首音乐，或者直接把音频文件拖进游戏窗口。

改动立刻生效并自动保存。不需要 JSON，不需要重载，玩家也不需要手动放文件。

## 支持的版本

| Minecraft | Fabric | Forge | NeoForge | Java |
| --- | --- | --- | --- | --- |
| 1.20.1 | Loader 0.19.3 + Fabric API 0.92.11 | 47.4.22 | 47.1.x（使用 Forge 的 JAR） | 17 |
| 1.21.1 | Loader 0.19.3 + Fabric API 0.116.14 | 52.1.16 | 21.1.244 | 21 |
| 26.3 | Loader 0.20.1 + Fabric API 0.166.2 | 66.1.8 | 26.3.0.103 | 25 |

表中是构建和测试所用的版本，具体数值见 [`gradle.properties`](gradle.properties)。

## 安装

1. 按 Minecraft 版本和加载器选择 `areamusic-<加载器>-<版本>-<模组版本>.jar`。
2. 放进服务端和每个客户端的 `mods/`。单人游戏只需要放进客户端。
3. Fabric 还需要对应版本的 Fabric API；Forge 和 NeoForge 不需要其他前置。

服务端和客户端必须是同一个模组版本。

## 编辑器

按 `F7` 打开，再按一次、按 `Esc` 或点右上角的 `×` 关闭（按键可在按键设置里修改）。编辑需要权限，默认是管理员等级 2；单人世界的房主始终可以编辑。没有权限时面板会直接告诉你。

编辑器只有两个状态：

- **面板开着**：右上角一张悬浮卡片，世界照常显示并继续运行，所有区域的盒子都画出来。这期间游戏自己的快捷栏、血条、准星和聊天会隐藏，关闭编辑器后恢复。面板的大小跟着窗口走，不受“界面尺寸”设置影响。
  面板开着时你仍然可以活动：移动、跳跃、潜行、疾跑的按键照常起作用（创造模式下照常飞行），在面板以外的地方按住鼠标左键、右键或中键拖动就能转视角。选完两个角、面板回来之后也一样；回来的那一刻如果正按着移动键，人物不会停下。正在输入框里打字时，按键归输入框。
- **在世界里选角**：只在你点了“新建区域”或“重选范围”之后出现。面板让开，左键点方块是第一个角，右键是第二个角，鼠标中键选中准星指向的区域；这期间左右键不会破坏或放置方块。新区域的两个角点完后面板自动回来，也可以随时按 `F7` 回到面板。

关闭编辑器后区域盒子随之消失，鼠标恢复正常用途。

**面板里的日常操作**

- “新建区域”：按顶部的三步进度条走——第一个角、第二个角、音乐。
- “以我为中心创建”：不用点角，直接得到一个以你为中心的区域，只差选音乐。
- 区域列表：每行是颜色、名称和它播的音乐；点一下选中，再点一下取消。
- 选中后可以改名称、音乐、音量和播放方式：单曲循环、歌单（按顺序循环）、多轨叠加（同时播放）。
- 改名称：点一下名称框直接输入，支持中文。按回车、按 `Esc`，或者直接去点别的地方，输入的内容都会保存到你正在改的那个区域上；想反悔用撤销。
- 传送、重选范围、显示/隐藏、复制、删除。
- 撤销 `Ctrl+Z`、重做 `Ctrl+Y`，也可以点面板顶部的“撤销”“重做”。删除不弹确认，靠撤销恢复。

**“高级”里的内容**（默认折叠）

- 淡入、淡出时长。
- 优先级：区域重叠时数值高的播放；相同则体积小的优先。
- 离开后再进来是否接着播。
- 启用开关、颜色。
- 两个角的精确坐标，以及各面整体扩大或缩小一格。
- 多轨模式下每条音轨各自的音量、延迟和是否循环。

## Axiom

在 Fabric 上同时装了 [Axiom](https://axiom.moulberry.com/) 时，Axiom 的工具列表里会多一个 AreaMusic 工具。它的工具选项面板与内置面板内容相同：

- 右键点方块放置角点（依次是角 1、角 2）；右键点空处选中视线上的区域。
- `Delete` 删除选中的区域。
- 撤销和重做用面板里的按钮（`Ctrl+Z` 归 Axiom 自己的方块历史）。
- 在 1.20.1 和 1.21.1 上可以把音频文件拖进窗口上传；26.3 上需要打开内置面板（`F7`）再拖。

Axiom 只有 Fabric 版，所以 Forge 和 NeoForge 上没有这个工具，内置编辑器的功能是一样的。不装 Axiom 时这部分代码不会加载。

> Axiom 工具已针对 Axiom 客户端 API 编译通过，但尚未在真实的 Axiom 安装上运行验证。如果它没有出现，日志里会有一条警告，内置编辑器不受影响。

## 音乐

支持 `.ogg`、`.mp3`、`.wav`、`.flac`。

- **添加音乐**：把文件拖进游戏窗口（需要编辑权限），或者放进服务器的 `<gameDir>/areamusic/`。放进文件夹的文件几秒内会自动出现在音乐列表里。
- **分发**：玩家登录后，客户端自动下载各区域用到的音乐，缓存在 `<gameDir>/areamusic-cache/`，按内容哈希命名，同一个文件只下载一次。
- **已有本地文件**：如果玩家的 `<gameDir>/areamusic/` 里已经有内容完全相同的同名文件，直接使用，不下载。
- **还没下完时**：走进区域会在快捷栏上方显示下载进度，下完后自动开始播放。
- **音量**：最终音量 = 游戏主音量 × 声音设置里的 AreaMusic 音量 × 区域音量。原版“音乐”滑条不影响它。

服务器对自己分发的音乐负有版权责任。

## 命令

所有命令需要权限等级 2。`<区域>` 是区域 ID，可以 Tab 补全。

```text
/areamusic create <区域> <角1> <角2> <音乐>
/areamusic delete <区域>
/areamusic list
/areamusic info <区域>
/areamusic tp <区域>
/areamusic set <区域> name <名称>
/areamusic set <区域> volume <0-100>
/areamusic set <区域> mode <loop|playlist|layers>
/areamusic set <区域> priority <整数>
/areamusic set <区域> resume <true|false>
/areamusic set <区域> enabled <true|false>
/areamusic set <区域> fadein <毫秒>
/areamusic set <区域> fadeout <毫秒>
/areamusic set <区域> color <#RRGGBB|auto>
/areamusic set <区域> pos1 <坐标>
/areamusic set <区域> pos2 <坐标>
/areamusic music add <区域> <音乐>
/areamusic music remove <区域> <音乐>
/areamusic reload
```

`reload` 只在你手动改了区域文件之后才需要。

## 服务器配置

`config/areamusic-server.properties` 在首次启动时生成，默认值适合大多数服务器：

| 选项 | 默认 | 说明 |
| --- | --- | --- |
| `distributeMusic` | `true` | 自动向玩家分发音乐。设为 `false` 则每个玩家要自己放文件 |
| `maxUploadMiB` | `32` | 单个上传文件的大小上限 |
| `maxLibraryMiB` | `1024` | 通过上传可达到的音乐库总大小 |
| `downloadKiBPerSecondPerPlayer` | `2560` | 每个玩家的下载限速 |
| `editPermissionLevel` | `2` | 使用编辑器所需的权限等级（0–4） |

## 文件位置

```text
<gameDir>/
├─ areamusic/                      音乐库（服务端）；玩家自备的同名文件（客户端）
├─ areamusic-cache/                客户端下载缓存
├─ config/areamusic-server.properties
└─ <存档>/areamusic/areas/<区域>.json   区域定义，一个区域一个文件
```

区域跟着存档走，所以地图可以连同音乐区域一起打包分发（记得带上 `areamusic/` 里的音乐）。

## 从 0.x 升级

- 首次启动时，旧的 `config/areamusic/<saveId>/*.json` 会被复制转换到存档目录。旧文件保持原样，并多出一个 `MIGRATED.txt` 说明去向。
- 读不出来的旧文件会被跳过并在日志和 `reload` 的输出里逐个列出，其余区域照常加载。
- 引用了已不存在的音乐的区域仍会加载，`/areamusic list` 会标出“有音乐文件缺失”。
- 0.x 的客户端和 2.x 的服务端互不兼容，需要一起升级。

## 区域文件格式（schema v4）

一般不需要手写。格式在 v3 的基础上增加了三个可选字段：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `displayName` | 空 | 显示名，最多 64 个字符，可以是中文 |
| `color` | 自动 | `#RRGGBB`，编辑器里盒子的颜色 |
| `enabled` | `true` | `false` 时区域保留但不播放 |

其余字段（`dimension`、`pos1`、`pos2`、`playbackMode`、`tracks` 或 `playlist`、`resumeOnReenter`、`priority`）与 v3 相同。v1、v2、v3 文件仍可读取，重新保存时写成 v4。

## 已知限制

- 区域只能是长方体。
- 调整正在播放的区域的音量或曲目时，音乐会带淡入淡出地重新开始，而不是原地变化。
- 面板里没有试听按钮；要听效果请走进区域。
- 多人游戏下各玩家各自解码播放，彼此之间不保证帧级同步。

## 构建

需要能下载 JDK 17、21、25 工具链（Gradle 会自动处理）。

```bash
./gradlew test check assemble build
```

8 个可安装的成品位于各加载器模块的 `build/libs/`：

```text
platforms/1.20.1/fabric/build/libs/areamusic-fabric-1.20.1-<版本>.jar
platforms/1.20.1/forge/build/libs/areamusic-forge-1.20.1-<版本>.jar      （也用于 NeoForge 47.1）
platforms/1.21.1/fabric/build/libs/areamusic-fabric-1.21.1-<版本>.jar
platforms/1.21.1/forge/build/libs/areamusic-forge-1.21.1-<版本>.jar
platforms/1.21.1/neoforge/build/libs/areamusic-neoforge-1.21.1-<版本>.jar
platforms/26.3/fabric/build/libs/areamusic-fabric-26.3-<版本>.jar
platforms/26.3/forge/build/libs/areamusic-forge-26.3-<版本>.jar
platforms/26.3/neoforge/build/libs/areamusic-neoforge-26.3-<版本>.jar
```

`common`、`dev` 和 `sources` JAR 不是安装包。

代码结构：`core` 是不依赖 Minecraft 的纯 Java（音频引擎、区域与编辑逻辑、同步协议、面板），`platforms/<版本>/common` 是各版本的 Minecraft 适配，`platforms/<版本>/<加载器>` 只做事件接线和网络注册。

## 许可证

AreaMusic 使用 [MIT License](LICENSE)。发布 JAR 内嵌第三方音频解码运行库；其许可证与归属信息见 JAR 内的 `META-INF/AREA_MUSIC_THIRD_PARTY_NOTICES.txt`。
