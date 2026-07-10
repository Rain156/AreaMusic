# AreaMusic 独立音量设计

## 目标

AreaMusic 在原版“音乐和声音选项”页面拥有自己的音量滑块，不再受原版“音乐”滑块控制，同时继续服从 Minecraft 主音量。

## 已批准行为

- 页面显示 `AreaMusic 音量`，英文为 `AreaMusic Volume`。
- 默认值为 100%，范围为 0% 到 100%。
- 最终增益只计算 `主音量 × AreaMusic 独立音量`。
- 原版“音乐”设为 0% 不会静音 AreaMusic。
- 主音量设为 0% 会静音 AreaMusic。
- 设置是客户端全局值，保存到 `config/areamusic-client.toml`，不会由服务器同步，也不按存档拆分。

## 结构

`AreaMusicClientConfig` 使用 Forge `CLIENT` 配置定义一个范围为 `[0.0, 1.0]` 的 `volume`。滑块更新该配置并立即保存，因此重启客户端后仍保留数值。

`AreaMusicSoundOptions` 监听 `ScreenEvent.Init.Post`。目标页面为 `SoundOptionsScreen`，事件监听器列表中已有原版 `OptionsList`，因此无需 Mixin、反射或访问转换器。它创建与原版声音滑块一致的 `OptionInstance<Double>`：0 显示“关”，其他值显示整数百分比。

为保持原版双栏布局，注入逻辑先寻找只包含“语音/旁白”滑块的单控件行，然后将该行安全替换为“语音/旁白 + AreaMusic”。如果该行已包含其他模组控件，则保留现有内容，并把 AreaMusic 作为新行追加到列表末尾。

`ClientAreaMusic.tick()` 只读取 `SoundSource.MASTER` 和 AreaMusic 客户端配置。原来的 `SoundSource.MUSIC` 读取会被删除。独立 Java Sound 混音器继续接收一个合成后的主增益，不改动淡入、淡出、交叉淡化或区域自身音量。

## 失败与兼容策略

- 配置越界值由 Forge 配置规范纠正，代码入口也会钳制到 `[0.0, 1.0]`。
- 若声音页面结构异常、找不到 `OptionsList`，记录警告并跳过滑块注入；音频仍使用已保存配置播放。
- 不扩展 `SoundSource` 固定枚举，不接管原版声音引擎，也不使用 Mixin。
- 专用服务端只注册通用 Forge 客户端配置定义，不加载任何 `net.minecraft.client` 类。

## 验证

- 单元测试覆盖默认值、配置写回、范围钳制和增益乘法。
- 编译验证客户端事件与界面 API，GameTest 验证专用服务端类加载安全。
- 客户端冒烟验证滑块显示、重启持久化、原版音乐 0% 不影响 AreaMusic、主音量 0% 能静音 AreaMusic。
