# Alogin Windows

Rust + Win32 的 Windows 原生客户端，当前为首版开发原型。目标系统为 Windows 10/11 x64。

## 当前功能

- 单实例和系统托盘，关闭主窗口后销毁窗口及控件，保留后台监听；再次运行可打开已有实例。
- 程序文件、窗口、任务栏及托盘使用与 Android 端相同的启动图标。
- 界面跟随 Windows 应用浅色/暗色设置，响应运行中的系统主题切换；窗口背景、标题栏、文本、输入框、按钮、复选框、账号下拉框与托盘菜单同步适配。高对比度模式优先使用系统配色，不提供独立的手动主题开关。
- 多账号添加、修改、切换与删除。密码输入留空时保留已有密码；切换保存账号不会改变当前校园网认证身份。
- 使用当前 Windows 用户范围的 DPAPI 加密密码，配置位于 `%LOCALAPPDATA%\Alogin\settings.json`。
- 显示到校园网门户的路由接口 IPv4、可读取的 Wi-Fi 名称和手动刷新的认证状态。
- 登录成功、启动或网络变化后的状态检查，以及点击“刷新状态”时，读取当前在线账号、已用流量、剩余流量和账户余额。在线账号由门户确认，与下次登录使用的本地保存账号独立；自助服务读取失败时保留已认证状态和已确认的账号，未返回的数值显示“—”。
- 自动探测并使用 Android 版现有 WLGN / Eportal 认证流程；兼容 JSONP、UTF-8 和 GBK 响应，以及动态门户的在线状态查询。
- 可配置目标 Wi-Fi 完整名称，自动登录默认关闭。启用后在启动、网络变化或恢复唤醒时检查，只有目标 Wi-Fi 且门户明确确认离线才提交认证；相同网络和账号只自动尝试一次，网络变化后重新获得机会。状态未知时可手动刷新或登录。

设备下线、无感知绑定管理、附近 Wi-Fi 扫描、开机启动设置及自动更新尚未移植。此原型不更改 Android 的版本或发布流程。

## 构建和运行

安装 Rust stable 的 `x86_64-pc-windows-msvc` 工具链、Visual Studio 的“使用 C++ 的桌面开发”组件和 Windows SDK。无需安装 WebView2、Java 或 .NET。

在仓库根目录执行：

```powershell
# 标准 Rust 安装与本仓库内 .tools 工具链都可以使用此入口
.\windows\scripts\cargo.ps1 test --locked
.\windows\scripts\cargo.ps1 fmt --check
.\windows\scripts\cargo.ps1 clippy --locked --all-targets '--' -D warnings
.\windows\scripts\cargo.ps1 build --locked --release

# 显示管理窗口
.\windows\target\release\alogin-windows.exe

# 仅启动托盘
.\windows\target\release\alogin-windows.exe --background
```

也可以在 `windows` 目录直接执行标准 `cargo` 命令。`Cargo.lock` 纳入版本控制，Release 使用 LTO、体积优化和符号剥离。当前开发环境的工具链安装在被 Git 忽略的 `.tools` 下，不修改系统 PATH；上述脚本自动识别它。

通过 PowerShell 包装脚本传递 Clippy 参数时将 `'--'` 加引号，防止 PowerShell 提前消耗参数分隔符；直接调用 `cargo` 时使用普通的 `--` 即可。

推荐使用 VS Code + rust-analyzer 或 RustRover，并打开 `windows/Cargo.toml` 对应的项目。使用仓库内工具链时，IDE 的 `CARGO_HOME` 和 `RUSTUP_HOME` 需分别指向仓库的 `.tools/cargo` 和 `.tools/rustup`；可先用标准 rustup 安装系统用户级工具链来简化 IDE 配置。

## 架构和资源占用

```text
src/
├── ui.rs             Win32 消息循环、窗口、控件、DPI 与托盘
├── ui/theme.rs       系统主题检测、原生控件配色与绘制
├── strings.rs        Windows 客户端中文文案
├── app.rs            会话状态、命令、后台串行工作线程
├── domain.rs         配置模型、自动登录准入、协议解析与编码
├── network.rs        校园网认证与门户状态流程
├── account.rs        门户账号确认、SSO 会话及只读账号概览
├── storage.rs        DPAPI、配置校验与原子持久化
└── platform/
    ├── http.rs       WinHTTP 请求、超时、响应上限与句柄释放
    ├── icon.rs       嵌入图标资源加载
    └── network.rs    路由、适配器、SSID 和系统网络事件
```

UI 线程只处理消息，凭据初始化、加解密、磁盘读写及请求由一个后台线程串行执行。网络通过 IP 地址、接口、路由和 WLAN 通知驱动，事件用短期定时器合并；空闲不轮询、不持续渲染。可见窗口按需创建并在关闭时销毁，后台仍保留消息接收窗口、托盘和网络订阅。WinHTTP/Windows 可能创建自己的内部线程。

主题检测仅在窗口创建及系统主题变化通知时执行，不轮询。主题绘制保留原生控件的键盘、焦点与无障碍语义；GDI 画刷随窗口销毁释放。标题栏暗色使用 DWM 提供的接口，较旧 Windows 版本不支持时保留系统标题栏。

`resources/alogin.ico` 从 Android 的 `app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp` 转换，包含 16–256 像素的多种尺寸。图标由 `build.rs` 使用 `embed-resource` 和 Windows SDK 资源编译器嵌入，构建时无需 Python。更新 Android 图标后，安装 Pillow 并运行 `python windows/scripts/generate_icon.py` 重新生成 ICO；仅转换格式，不重新绘制图案。

不承诺固定内存值。应在同一台机器上使用 Release 分别检查：启动并稳定后的托盘空闲、打开窗口、认证过程中和关闭窗口后的工作集、专用内存及 CPU 时间增量；不要只看累计 CPU 时间或可执行文件大小。

## 安全与使用边界

- 账号使用系统 DPAPI 加密，不提供明文回退。跨 Windows 用户或设备复制配置不保证可解密。
- 新配置先写同目录临时文件并刷盘，再执行写穿原子替换，成功后才提交内存状态。损坏或未知版本的配置不会被自动覆盖。
- 请求固定使用已实现的校园网主机和端口；关闭自动重定向和自动 Windows 身份认证，不记录请求 URL、密码或响应正文。自助服务跳转只接受 `jfself.bjut.edu.cn` 的 HTTPS 443 端口和 `/Self/` 路径，限制跳转次数。
- 校园网证书链兼容例外只用于 `wlgn.bjut.edu.cn`、`lgn.bjut.edu.cn`、`jfself.bjut.edu.cn`，仍检查主机名和有效期。Eportal 沿用现有校园网 HTTP 协议。
- 每次账号概览读取独立创建短期 WinHTTP Cookie 会话，完成后释放，Cookie 不写磁盘；SSO 请求使用门户确认的账号，不读取本地密码。每次请求前后复核网络，切网后丢弃旧结果；自助服务返回其他账号时拒绝显示其流量和余额。
- 使用 Windows 路由表访问固定门户，登录提交前后核对接口、IP 和可读取的 SSID；自动认证还会重新检查目标网络。VPN 和多网卡环境需要实网验证。
- Windows 位置权限可能影响 SSID 读取。无法读取 SSID 时不会自动登录；有线网络可手动认证。

## 验证

JVM/Android 构建不参与 Windows 客户端编译。Windows 修改至少运行 `test`、`fmt --check`、`clippy --all-targets -- -D warnings` 和 `build --release`；工作流 `.github/workflows/windows-client.yml` 在 Windows 执行这些检查并保留 EXE 构建产物，不发布到更新服务器。

单元测试覆盖响应解析、编码、自动登录准入、DPAPI 往返、损坏配置保护、持久化失败时保留旧磁盘和内存状态，以及账号确认、SSO 跳转校验、概览解析与格式化、会话过期和切网结果隔离。DPAPI 测试需要当前 Windows 用户的正常加密权限；受限沙箱可能拒绝调用。

发布前还需在真实校园网验证两种门户的成功、密码错误、已在线、断网、切网、VPN/多网卡和位置权限拒绝场景，并检查托盘恢复、第二实例、休眠恢复及高 DPI 下的界面。账号和密码只在本机输入，不放入自动化测试夹具。
