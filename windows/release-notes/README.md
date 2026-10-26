# Windows 版本说明

每次发布新增 `<version>.md` 中文说明，与 `Cargo.toml` 的版本一致。Windows 标签使用
`windows-v<version>`，例如 `windows-v0.0.1`；Android 继续使用 `v<version>`，禁止复用历史标签。

说明采用简短列表，只描述已实现的功能和已知限制。发布脚本校验标签、Cargo 版本、
Cargo.lock 和非空说明，再生成便携包、SHA-256 和 Windows 独立的 latest.json。
