# TVBox 简版

基于 [NewOrin/TVBox](https://github.com/NewOrin/TVBox) 思路的极简电视盒子 App：
**用户在设置里粘贴一个视频源链接，App 自动加载、分类、搜索、播放。**

## 特性

- 🪶 极简：原生 Android TV + Kotlin，轻量安装包
- 🔗 **两种源**：
  - **JSON 源**：TVBox 标准协议，粘贴 `https://xxx.json` 即可
  - **HTML 源**（实验性）：直接粘贴影视站根 URL，App 自动识别模板并抓取
- 🏠 丰富首页：快捷入口、热门影视卡片、无源状态也可扫码设置
- 🧱 网格浏览：分类、搜索结果、剧集列表更适合遥控器操作
- 🔍 聚合搜索：一次输入，同时搜所有已添加的源，并按精确度排序
- 🕘 观看历史：播放页自动保存最近观看记录，独立“最近观看”入口
- ⏯️ 断点续看：再次打开同一集时自动跳转到上次观看位置
- 📱 局域网扫码：支持扫码搜索、扫码传文件、扫码添加视频源
- 📺 Leanback 电视 UI：遥控器即可操作

## 怎么用

1. 安装 APK 到电视 / 电视盒子
2. 启动后进入 **设置** 页
3. 选择源类型 + 粘贴链接 + 点击对应按钮添加
4. 播放视频后会自动保存历史；下次从首页 **最近观看** 或同一集进入播放页，会自动断点续看

### 局域网扫码功能

首页快捷入口提供：

- **扫码搜索**：电视展示二维码，手机扫码输入关键词，电视端自动打开搜索结果。
- **扫码传文件**：手机和电视在同一局域网，扫码后可上传文件到电视 App 专属目录。
- **扫码设置**：手机扫码后输入 JSON 源或 HTML 站点根 URL，同步添加到电视端。

二维码链接带随机 token，仅用于同一局域网临时访问；不要在公网暴露电视盒子的局域网端口。

### 方式 A：JSON 源（推荐）

粘贴 TVBox 标准的 base64-JSON 源 URL：
```
https://example.com/tvbox.json
```
点击 **添加 JSON 源**。

### 方式 B：HTML 源（实验性）

直接粘贴影视站根 URL：
```
https://www.icaiqi.com
```
点击 **添加 HTML 源**。

App 会自动识别模板（canghai / stui / maccms 等），从首页抓分类 → 列表页抓视频 → 详情页抓剧集 → 播放页抓 m3u8。

**支持的模板/站**（基于真实页面验证）：
- ✅ maccms_canghai（icaiqi.com / 一起看 / 555yy / tiantang / 飞速 等）
- ✅ maccms_stui（stui_block 模板）
- ✅ maccms_default（/index.php/vod/ 形式）
- ✅ maccms_mx（/vodtype/{id}.html + /voddetail/{id}.html + /vodplay/{id}-{line}-{ep}.html，例如 baiu.cc 这类结构）
- ✅ 通用退路：任意 `<a href="/{shipin|detail|show|vod}/数字.html" data-original="..." title="片名">` 结构

**不支持**：
- ❌ 纯 SPA 站（首页 HTML 是空壳）
- ❌ DRM 加密视频
- ❌ 视频号/抖音类客户端内置视频

## 怎么打包

### 方法一：Android Studio（推荐）

1. 打开 Android Studio Hedgehog 或更新版本
2. `File → Open` 选择 `tvbox-simple` 目录
3. 等待 Gradle 同步（首次需要下载约 1 GB 依赖）
4. 菜单 `Build → Build Bundle(s)/APK(s) → Build APK(s)`
5. 产物在 `app/build/outputs/apk/debug/app-debug.apk`

### 方法二：命令行

```bash
# 1. 安装 JDK 17、Android SDK、配置 ANDROID_HOME 环境变量
# 2. 下载 gradle-wrapper.jar 放到 gradle/wrapper/ 下
#    (Android Studio 同步项目时自动生成)
# 3. 执行：

cd tvbox-simple
gradle wrapper           # 初始化 wrapper（需本机有 gradle）
./gradlew assembleDebug  # 编译 debug 包

# 产物在 app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/simple/tvbox/
│   ├── TvBoxApp.kt              # Application 入口
│   ├── model/                    # 数据模型
│   │   ├── Source.kt
│   │   ├── SpiderSite.kt
│   │   └── VideoCategory.kt
│   ├── source/                   # Spider 协议解析
│   │   └── SpiderClient.kt
│   ├── data/                     # 数据仓库
│   │   └── SourceRepository.kt
│   ├── util/
│   │   └── HttpUtil.kt          # OkHttp 工具
│   └── ui/
│       ├── MainActivity.kt
│       ├── home/                # 主页 + 分类页
│       │   ├── HomeFragment.kt
│       │   ├── CategoryActivity.kt
│       │   ├── CategoryFragment.kt
│       │   └── CardPresenter.kt
│       ├── detail/              # 视频详情
│       │   └── DetailActivity.kt
│       ├── search/              # 搜索
│       │   └── SearchActivity.kt
│       ├── player/              # 播放器
│       │   └── PlayerActivity.kt
│       └── settings/            # 视频源设置
│           └── SettingsActivity.kt
└── res/
    ├── layout/
    ├── values/
    └── drawable/
```

## 与原版 TVBox 的差异

| 项目 | 原版 TVBox | 简版 |
| --- | --- | --- |
| 语言 | Java | Kotlin |
| 播放器 | IJK / Exo 多选 | Media3 ExoPlayer |
| 源类型 | XML / JSON / Spider jar | **JSON (type=1) + 通用 HTML** |
| HTML 站支持 | 通过 jar 爬虫 | **启发式模板识别**（实验性） |
| 嗅探播放 | ✅ | ❌（直链才能播） |
| 多源聚合搜索 | ✅ | ✅ |
| 设置 UI | 完整设置中心 | **一个 URL 输入框 + 类型选择** |
| 包大小 | ~30 MB | < 10 MB |

## License

仅供学习使用。请遵守当地法律法规。
