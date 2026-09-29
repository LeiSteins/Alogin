<p align="center">
  <img src="docs/images/app-logo.webp" alt="Alogin Logo" width="160">
</p>

<h1 align="center">Alogin</h1>

一款 Android 网络认证助手，用于在已配置的目标 Wi-Fi 上完成认证，并查看网络与账号状态。

## 功能

- 一键认证：保存账号和密码后，可在目标 Wi-Fi 上发起认证。
- 认证门户适配：自动探测并使用项目已实现的认证门户流程。
- 目标 Wi-Fi 管理：支持手动添加、删除或扫描附近 Wi-Fi 后加入目标列表。
- 网络状态：显示当前连接的 Wi-Fi、设备 IP 地址和认证状态；网络切换后会自动刷新。
- 账号概览：认证成功后可查看账号、流量、余额及关联设备信息。
- 设备管理：可在服务端支持时使关联设备下线。
- 调试与更新：提供默认关闭的可选 HTTP 请求日志和应用内更新检查。

## 下载与安装

前往 [GitHub Releases](https://github.com/LeiSteins/alogin/releases) 下载最新版本的 `alogin-v<版本号>.apk`，然后在 Android 设备上打开 APK 并按提示完成安装。若系统阻止安装，请根据提示允许当前下载来源安装未知应用。

## 使用方法

1. 在主页面右上角的“账号管理”中填写并保存认证账号和密码。
2. 在“设置 → 目标网络”中添加可使用校园网登录的 Wi-Fi；也可以扫描附近网络后选择加入。
3. 连接已配置的目标 Wi-Fi，并在主页点击“登录”。
4. 认证完成后，主页会显示网络状态和可获取的账号信息。

> Android 需要位置权限才能读取 Wi-Fi 名称；扫描附近网络时，Android 13+ 使用「附近设备」权限，旧版本使用位置权限。仅在需要这些功能时授权即可。

## 构建与测试

环境要求：JDK 17、Android SDK，以及项目 Gradle Wrapper 所需的网络依赖。

在项目根目录执行：

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 运行本地单元测试
./gradlew test

# 在已连接的设备或模拟器上运行测试
./gradlew connectedAndroidTest

# 安装 Debug APK
./gradlew installDebug
```

Windows PowerShell 请将 `./gradlew` 替换为 `.\gradlew.bat`。

Debug APK 的默认输出位置为 `app/build/outputs/apk/debug/`。

推送 `v*` 标签会触发 Release 工作流，在测试和签名构建成功后创建 GitHub Release，并将 APK 上传到更新服务器。

发布前同步递增 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`，并新增
[`release-notes/<版本号>.md`](release-notes/README.md) 中文更新说明。标签需与 `versionName`
一致。工作流生成的 `latest.json` 和 APK 一同附加到 GitHub Release，更新说明作为 Release 正文。

应用从 `https://aloginupdate.steins.top/latest.json` 检查版本，并在下载前显示更新说明。
版本文件格式如下（数值仅为示例）：

```json
{
  "schemaVersion": 1,
  "version": "0.2.0",
  "versionCode": 20,
  "fileName": "alogin-v0.2.0.apk",
  "releaseNotes": "- 新增更新说明\n- 修复登录问题"
}
```

客户端仅在版本名称和版本代码均高于当前安装版本时提示更新；下载地址由可信服务器地址与
匹配版本的 APK 文件名组成。清单缺失或损坏时显示检查失败，允许重试。

服务器部署沿用现有 `production` 环境中的 `DEPLOY_HOST`、`DEPLOY_PATH`、`DEPLOY_PORT`、
`DEPLOY_USER` 和 SSH secrets，无需新增凭据。工作流先上传 APK 及校验文件，再上传临时版本文件，
最后通过同目录重命名原子替换 `latest.json`。部署串行执行，拒绝较小 `versionCode` 或不同版本复用
相同 `versionCode` 覆盖最新标识；相同版本允许重跑。服务器必须允许访问该 JSON 文件；建议为
`latest.json` 配置 `Cache-Control: no-cache`，使用 CDN 时同时配置缓存规则。

首次启用需要完成一次发布以创建服务器上的 `latest.json`。旧版应用仍可通过现有目录列表发现 APK，
因此迁移期间保留目录列表及原有 APK 文件。更新说明不会在 Release 正文被手工编辑后自动重新同步；
需要修改仓库中的对应说明并走发布流程。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- 单 Activity 与 Compose Navigation
- ViewModel、StateFlow 与 Kotlin Coroutines
- OkHttp 网络请求
- 最低支持 Android 8.0（API 26）

## 项目结构

```text
app/src/main/java/top/steins/autologin/
├── data/       # 本地设置与目标 Wi-Fi 配置
├── network/    # 认证、账号服务、网络信息与更新逻辑
├── navigation/ # Compose 导航定义
└── ui/         # Compose 页面、组件与主题
```

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
