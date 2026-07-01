# 第一次使用必读

本项目**没有**自带 Gradle 和 Android SDK，按以下任一方式准备好环境：

## 方式 A：Android Studio（最省心）

1. 下载 [Android Studio Hedgehog+](https://developer.android.com/studio)
2. 安装时勾选：Android SDK、Android SDK Platform 34、Build Tools 34.0.0
3. 打开项目：`File → Open` → 选 `tvbox-simple` 目录
4. 等 Gradle 同步完成（约 5–10 分钟，首次）
5. 菜单：`Build → Build Bundle(s) / APK(s) → Build APK(s)`
6. 完成后右下角点 "locate" 跳到 APK

## 方式 B：命令行 + build.ps1（Windows 脚本）

1. 安装 [JDK 17](https://adoptium.net/)（勾选 Set JAVA_HOME）
2. 安装 [Android command-line tools](https://developer.android.com/studio#command-line-tools-only)
3. 用 `sdkmanager` 装齐：
   ```
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```
4. 设置环境变量：
   ```
   setx ANDROID_HOME "%LOCALAPPDATA%\Android\Sdk"
   setx PATH "%PATH%;%ANDROID_HOME%\platform-tools"
   ```
5. 在项目根目录执行 `.\build.ps1`

## 方式 C：纯命令行（Linux / macOS）

```bash
# 准备 SDK、JDK17
export ANDROID_HOME=$HOME/Android/Sdk

# 一次性生成 wrapper
gradle wrapper --gradle-version 8.7

# 编译
./gradlew assembleDebug
```

## 常见问题

### Q: Gradle 同步时 "Plugin not found"
A: 检查 `~/.gradle/init.d/` 下是否被全局镜像脚本覆盖；本项目使用 `google()` + `mavenCentral()`。

### Q: 编译报 `Could not download xxx`
A: 关闭 VPN / 代理后重试；或设置 `~/.gradle/gradle.properties` 里的代理。

### Q: 装到电视上闪退
A: 检查电视 Android 版本 ≥ 5.0（API 21）。在电视设置里打开"允许安装未知来源"。

### Q: Leanback 启动器看不到图标
A: 部分定制电视桌面不识别 Leanback 启动器类别，可改用普通桌面启动器看。
