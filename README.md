<p align="center">
  <img src="docs/images/app-logo.webp" alt="Alogin Logo" width="160">
</p>

<h1 align="center">Alogin</h1>

一款 Android 网络认证助手，用于在已配置的目标 Wi-Fi 上完成认证，并查看网络与账号状态。

官网：[https://steins.top/alogin/](https://steins.top/alogin/)

## 功能

- 一键认证：保存账号和密码后，可在目标 Wi-Fi 上发起认证。
- 多账号管理：支持保存多个账号、切换登录账号、修改密码和删除已保存账号。
- 启动时自动登录：可在软件设置中开启，启动应用后在满足登录条件时尝试认证，默认关闭。
- 认证门户适配：自动探测并使用项目已实现的认证门户流程。
- 目标 Wi-Fi 管理：支持手动添加、删除或扫描附近 Wi-Fi 后加入目标列表。
- 网络状态：显示当前连接的 Wi-Fi、设备 IP 地址和认证状态；网络切换后会自动刷新。
- 账号概览：认证成功后可查看账号、流量、余额及关联设备信息。
- 设备管理：可在服务端支持时使关联设备下线。
- 外观设置：支持跟随系统、浅色和深色主题。
- 调试与更新：提供默认关闭的可选 HTTP 请求日志，可在“关于应用”页检查更新并查看更新说明。

## 下载与安装

前往 [GitHub Releases](https://github.com/LeiSteins/alogin/releases) 下载最新版本的 `alogin-v<版本号>.apk`，然后在 Android 设备上打开 APK 并按提示完成安装。若系统阻止安装，请根据提示允许当前下载来源安装未知应用。

## 使用方法

1. 打开主页面右上角的“账号管理”，点击“添加账号”，填写认证账号和密码后选择“保存并使用”。
2. 在“设置 → 目标网络”中添加可使用校园网登录的 Wi-Fi；也可以扫描附近网络后选择加入。
3. 连接已配置的目标 Wi-Fi，并在主页点击“登录”。
4. 认证完成后，主页会显示网络状态和可获取的账号信息。

账号管理页支持保存多个账号。点击账号行可切换下次登录使用的账号，当前网络连接不会因此改变；点击账号右侧的编辑图标可修改密码，或在编辑面板中删除账号。保存新账号或更新密码后，该账号会被设为当前登录账号。

如需启动时自动登录，在“设置 → 软件设置”中开启“启动软件后尝试自动登录”。该功能默认关闭；启用后，启动应用并连接目标 Wi-Fi、已保存账号密码且尚未认证时，会尝试登录。

检查更新的入口为“设置 → 关于应用 → 检查更新”，发现新版本后可查看更新说明并下载 APK。

### Wi-Fi 权限

本应用读取 Wi-Fi 名称和扫描附近网络时需要精确位置权限，Android 13 及以上版本也使用该权限。若系统提供“大致位置”和“精确位置”选项，请选择精确位置。扫描附近网络还需要开启系统定位服务和 Wi-Fi；未开启时应用会提示。

## 构建与测试

环境准备：

- 安装 JDK 17，并确认 `java -version` 使用该版本。
- 安装 Android SDK。当前编译平台为 API 37，请通过 Android Studio 的 SDK Manager 安装对应 SDK Platform 和构建所需的 Build-Tools；设备测试和安装还需要 Platform-Tools。编译平台版本以 [`app/build.gradle.kts`](app/build.gradle.kts) 中的 `compileSdk` 为准。
- 使用 Android Studio 配置本地 SDK 路径，或在命令行环境设置 `ANDROID_HOME`，并接受所需 SDK 的许可。本地 `local.properties` 不应提交。
- 运行发布脚本测试需要 Python 3，仅使用标准库。
- 首次构建需要网络，以下载 Gradle Wrapper 分发包和项目依赖。

在项目根目录执行：

```bash
# 构建 Debug APK 并运行 Android Lint
./gradlew assembleDebug lintDebug

# 运行本地单元测试
./gradlew test

# 运行发布脚本测试
python3 -m unittest discover -s scripts -p "test_*.py"

# 在已连接的设备或模拟器上运行测试
./gradlew connectedAndroidTest

# 安装 Debug APK
./gradlew installDebug
```

Windows PowerShell 请将 `./gradlew` 替换为 `.\gradlew.bat`，将 `python3` 替换为本机 Python 3 的启动命令，例如 `python` 或 `py -3`。

Debug APK 的默认输出位置为 `app/build/outputs/apk/debug/app-debug.apk`。设备测试和安装命令需连接设备或启动模拟器。

## 发布

发布前在 `app/build.gradle.kts` 中同时递增 `versionName` 和 `versionCode`，新增 `release-notes/<versionName>.md` 中文版本说明，再推送与 `versionName` 一致的 `v<版本号>` 标签；不要复用已发布的版本。[Release 工作流](.github/workflows/release.yml)会运行测试、构建签名 APK、创建 GitHub Release，并部署更新服务器。

版本说明格式见 [release-notes/README.md](release-notes/README.md)。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- 单 Activity 与 Compose Navigation
- ViewModel、StateFlow 与 Kotlin Coroutines
- OkHttp 网络请求
- 最低支持 Android 8.0（API 26）

## 项目结构

```text
app/src/main/java/top/steins/autologin/
├── data/       # 凭据加密、多账号持久化、本地设置与目标 Wi-Fi 配置
├── network/    # 认证、账号服务、网络信息与更新逻辑
├── navigation/ # Compose 导航定义
└── ui/         # Compose 页面、组件与主题
```

JVM 测试位于 `app/src/test`，设备测试位于 `app/src/androidTest`，发布脚本及其测试位于 `scripts`。

## 隐私与安全

- 账号和密码仅保存在设备本地，优先使用 Android Keystore 托管密钥并通过 Tink
  AES-256-GCM 加密；Keystore 不可用时改用应用私有目录中的本地 Tink 密钥集，
  且凭据及密钥集不会参与系统云备份或设备迁移；若两种加密路径均不可用，应用会拒绝保存并提示，
  不会降级为明文存储。请妥善保管设备。
- HTTP 日志默认关闭且不会采集请求；如需排查认证问题，可在“关于应用”页连续点击版本五次开启。
  开启后可点击日志页右上角的关闭图标停止并清空日志。登录密码等敏感查询参数会自动脱敏，响应体过大时会截断；
  排查完成后仍请避免向他人分享日志内容。
- 本项目仅适用于其内置认证流程所兼容的网络环境。使用前请确认你有权使用对应的网络和账号服务。

## 许可证

除另有注明的第三方组件外，本项目采用 GNU General Public License v3.0
（SPDX 标识：`GPL-3.0`）发布，完整条款请见 [LICENSE](LICENSE)。
