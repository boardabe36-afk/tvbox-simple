# TVBox Simple / TVBox 简版

一个面向 Android TV / 电视盒子的极简影视源播放器。

> 本项目基于 [NewOrin/TVBox](https://github.com/NewOrin/TVBox) 的设计思路与 TVBox JSON/Spider 协议生态进行简化实现。  
> 项目本身不内置、不分发任何影视内容、源地址、接口地址或爬虫包，仅提供客户端能力。请仅添加你有权访问的合法内容源。

## 软件功能

- **TVBox JSON 源支持**：支持常见 TVBox JSON 配置，解析站点、分类、搜索、详情、播放。
- **实验性 HTML 源支持**：可直接添加部分影视站根 URL，自动识别 maccms/canghai/stui/mx 等模板结构。
- **Android TV 遥控器 UI**：基于 Leanback，适合电视盒子、投影、Android TV 使用。
- **丰富首页**：快捷入口 + 热门影视卡片 + 无源引导；即使没有配置源，也能进入扫码设置等功能。
- **网格浏览**：分类页、搜索结果、剧集列表均改为更适合电视遥控器的网格布局。
- **聚合搜索与排序**：跨源搜索，并按精确匹配、前缀匹配、包含匹配、紧凑匹配、子序列匹配等规则排序。
- **观看历史**：播放后自动保存最近观看记录。
- **断点续看**：再次进入同一集时自动从上次位置继续播放；接近片尾时自动清除断点。
- **局域网扫码功能**：
  - 扫码搜索：手机扫码输入关键词，电视端自动搜索。
  - 扫码传文件：手机上传文件到电视 App 专属目录。
  - 扫码设置：手机输入 JSON 源或 HTML 源地址，同步到电视端。
- **轻量海报加载**：内置简单图片加载与内存缓存，不强依赖大型图片框架。

## 与原版 TVBox 的关系

本项目不是原版 TVBox 的完整替代品，也不是官方版本。它更像一个“轻量重写/简化实验版”：

- 借鉴 TVBox 的核心使用方式：用户配置源 → 分类/搜索/详情/播放。
- 兼容常见 TVBox JSON 配置里的 Spider 站点协议。
- 删除大量复杂设置，尽量降低新手使用成本。
- 使用 Kotlin + AndroidX + Media3 重新组织代码。

## 与原版 TVBox 的主要差异

| 项目 | 原版 TVBox | TVBox Simple |
| --- | --- | --- |
| 定位 | 功能完整的 TVBox 客户端 | 极简、轻量、易改造的 Android TV 客户端 |
| 语言/结构 | Java 为主，历史包袱较多 | Kotlin，代码结构更简洁 |
| 播放器 | 多播放器/嗅探能力更完整 | Media3 ExoPlayer，重点播放直链 m3u8/mp4 |
| 源配置 | XML / JSON / Spider jar 等生态完整 | TVBox JSON + 实验性 HTML 模板解析 |
| HTML 站支持 | 多依赖 jar/spider 扩展 | 内置启发式 HTML 模板解析：canghai/stui/maccms/mx |
| 设置中心 | 功能多、选项多 | URL 输入 + 扫码设置，尽量简单 |
| 首页 | 传统分类/推荐 | 快捷入口 + 热门影视卡片 + 最近观看入口 |
| 历史与续看 | 视版本/配置而定 | 内置观看历史与断点续看 |
| 手机辅助 | 通常依赖外部配置 | 内置局域网扫码搜索/传文件/设置 |
| 包大小 | 相对更大 | 轻量化目标 |

## 本项目相对普通 TVBox 使用体验的优化

1. **首次使用更顺手**  
   无源状态下不再只显示“设置”，而是保留搜索、最近观看、扫码搜索、扫码传文件、扫码设置、刷新等快捷入口。

2. **适合电视遥控器操作**  
   分类、剧集、搜索结果都改成网格布局，减少上下翻找成本。

3. **更强的搜索排序**  
   搜索结果按标题精确度排序：完全匹配 > 前缀匹配 > 包含匹配 > 去空格/符号匹配 > 子序列匹配。

4. **自动保存播放进度**  
   播放中、暂停、退出、异常等生命周期都会尝试保存观看进度，下次进入同一集自动续看。

5. **局域网扫码配置**  
   电视上打字很麻烦，所以增加手机扫码输入源地址、关键词、上传文件。

6. **HTML 源实验支持**  
   对部分 maccms 模板站点可直接添加根 URL，不一定需要现成 JSON 配置。

## 当前支持的 HTML 模板/路径

实验性 HTML 源目前主要支持：

- `maccms_canghai`：如 canghai/stui 派生模板
- `maccms_stui`：stui-vodlist / stui-content 结构
- `maccms_default`：`/index.php/vod/...` 形式
- `maccms_mx`：
  - `/vodtype/{id}.html`
  - `/voddetail/{id}.html`
  - `/vodplay/{id}-{line}-{episode}.html`
  - `/vodsearch/{keyword}-------------.html`
- 通用退路：`/{shipin|detail|show|vod}/数字.html` 类型链接

注意：HTML 源是启发式解析，站点一改版就可能失效。

## 不支持 / 限制

- 不内置任何源、片库、账号、API Key。
- 不支持 DRM 加密视频。
- 不保证所有 HTML 站点可用。
- 不支持纯 SPA 空壳页面，除非页面源码里直接包含可解析数据。
- 当前不做完整浏览器嗅探，播放页需要能提取到 m3u8/mp4 等直链。

## 使用方法

1. 下载并安装 APK 到 Android TV / 电视盒子。
2. 打开 App。
3. 进入 **设置** 或使用首页 **扫码设置**。
4. 添加：
   - TVBox JSON 配置 URL；或
   - 实验性 HTML 站点根 URL。
5. 返回首页刷新，即可浏览、搜索、播放。

### 局域网扫码

电视和手机必须在同一局域网：

- **扫码搜索**：手机扫码输入关键词，电视端自动打开搜索结果。
- **扫码传文件**：手机扫码上传文件到电视 App 专属目录。
- **扫码设置**：手机扫码输入源名称、URL、类型，电视端自动保存。

二维码 URL 带随机 token，仅用于临时局域网访问；不要将电视盒子端口暴露到公网。

## 构建

### 环境要求

- JDK 17
- Android SDK
- Gradle Wrapper（仓库已包含）

### Debug 包

```bash
./gradlew assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 测试

```bash
./gradlew testDebugUnitTest
```

### Release 签名

仓库不包含签名文件。你可以使用环境变量：

```bash
TVBOX_KEYSTORE_PATH=/path/to/release.keystore
TVBOX_KEYSTORE_PASS=******
TVBOX_KEY_ALIAS=tvboxsimple
TVBOX_KEY_PASS=******
```

或者复制 `keystore.properties.example` 为本地 `keystore.properties` 后自行填写。该文件已被 `.gitignore` 排除，不应提交。

## 项目结构

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/simple/tvbox/
│   ├── TvBoxApp.kt
│   ├── data/                 # 源仓库、观看历史
│   ├── model/                # 数据模型
│   ├── source/               # JSON / Spider / HTML 源客户端
│   ├── ui/                   # 首页、分类、详情、搜索、播放、扫码、设置
│   └── util/                 # HTTP、图片加载、局域网服务等
└── res/
    ├── drawable/
    ├── layout/
    └── values/
```

## 合规说明

本项目仅用于学习 Android TV 客户端开发、TVBox 协议解析、媒体播放与局域网交互。

- 仓库不包含任何视频内容。
- 仓库不包含任何默认影视源。
- 仓库不鼓励、不协助访问侵权内容。
- 使用者应自行确保所添加内容源合法合规。

## License

MIT License. See [LICENSE](LICENSE).
