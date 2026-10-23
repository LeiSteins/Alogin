# Alogin Windows

Rust + Win32 的 Windows 原生客户端，当前为首版开发原型。目标系统为 Windows 10/11 x64。

## 当前功能

- 单实例和系统托盘，关闭主窗口后销毁窗口及控件，保留后台监听；再次运行可打开已有实例。
- 多账号添加、修改、切换与删除。密码输入留空时保留已有密码；切换保存账号不会改变当前校园网认证身份。
- 使用当前 Windows 用户范围的 DPAPI 加密密码，配置位于 `%LOCALAPPDATA%\Alogin\settings.json`。
- 显示到校园网门户的路由接口 IPv4、可读取的 Wi-Fi 名称和手动刷新的认证状态。
- 自动探测并使用 Android 版现有 WLGN / Eportal 认证流程；兼容 JSONP、UTF-8 和 GBK 响应，以及动态门户的在线状态查询。
- 可配置目标 Wi-Fi 完整名称，自动登录默认关闭。启用后在启动、网络变化或恢复唤醒时检查，只有目标 Wi-Fi 且门户明确确认离线才提交认证；相同网络和账号只自动尝试一次，网络变化后重新获得机会。状态未知时可手动刷新或登录。

账号概览、设备下线、无感知绑定管理、附近 Wi-Fi 扫描、主题切换、开机启动设置及自动更新尚未移植。此原型不更改 Android 的版本或发布流程。

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
├── strings.rs        Windows 客户端中文文案
├── app.rs            会话状态、命令、后台串行工作线程
├── domain.rs         配置模型、自动登录准入、协议解析与编码
├── network.rs        校园网认证与门户状态流程
├── storage.rs        DPAPI、配置校验与原子持久化
└── platform/
    ├── http.rs       WinHTTP 请求、超时、响应上限与句柄释放
    └── network.rs    路由、适配器、SSID 和系统网络事件
```

UI 线程只处理消息，凭据初始化、加解密、磁盘读写及请求由一个后台线程串行执行。网络通过 IP 地址、接口、路由和 WLAN 通知驱动，事件用短期定时器合并；空闲不轮询、不持续渲染。可见窗口按需创建并在关闭时销毁，后台仍保留消息接收窗口、托盘和网络订阅。WinHTTP/Windows 可能创建自己的内部线程。

不承诺固定内存值。应在同一台机器上使用 Release 分别检查：启动并稳定后的托盘空闲、打开窗口、认证过程中和关闭窗口后的工作集、专用内存及 CPU 时间增量；不要只看累计 CPU 时间或可执行文件大小。

## 安全与使用边界

- 账号使用系统 DPAPI 加密，不提供明文回退。跨 Windows 用户或设备复制配置不保证可解密。
- 新配置先写同目录临时文件并刷盘，再执行写穿原子替换，成功后才提交内存状态。损坏或未知版本的配置不会被自动覆盖。
- 认证请求固定使用已实现的校园网主机和端口，不采用页面提供的任意 URL；关闭自动重定向和自动 Windows 身份认证，不记录请求 URL、密码或响应正文。
- WLGN 的证书链兼容例外只用于 `wlgn.bjut.edu.cn`，仍检查主机名和有效期。Eportal 沿用现有校园网 HTTP 协议。
- 使用 Windows 路由表访问固定门户，登录提交前后核对接口、IP 和可读取的 SSID；自动认证还会重新检查目标网络。VPN 和多网卡环境需要实网验证。
- Windows 位置权限可能影响 SSID 读取。无法读取 SSID 时不会自动登录；有线网络可手动认证。

## 验证

JVM/Android 构建不参与 Windows 客户端编译。Windows 修改至少运行 `test`、`fmt --check`、`clippy --all-targets -- -D warnings` 和 `build --release`；工作流 `.github/workflows/windows-client.yml` 在 Windows 执行这些检查并保留 EXE 构建产物，不发布到更新服务器。

单元测试覆盖响应解析、编码、自动登录准入、DPAPI 往返、损坏配置保护，以及持久化失败时保留旧磁盘和内存状态。DPAPI 测试需要当前 Windows 用户的正常加密权限；受限沙箱可能拒绝调用。

发布前还需在真实校园网验证两种门户的成功、密码错误、已在线、断网、切网、VPN/多网卡和位置权限拒绝场景，并检查托盘恢复、第二实例、休眠恢复及高 DPI 下的界面。账号和密码只在本机输入，不放入自动化测试夹具。
