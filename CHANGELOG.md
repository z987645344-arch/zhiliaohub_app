# Changelog

## v0.1 首次存档 - 2026-08-06

- 已将当前 Kotlin Android 工程以创世提交 `74b89b1` 存档到 [GitHub 仓库](https://github.com/z987645344-arch/zhiliaohub_app)。
- 新增 GitHub Actions CI，对 `main` 的 push 和 pull request 执行 `assembleDebug`、`testDebugUnitTest` 与 `lintDebug`。
- 首次存档包含设备配对、挑战应答登录、session 持久化、吊销处理、健康状态卡片，以及已通过的 8 项真机端到端验证和覆盖安装验证。
- 使用 `v0.1` 标签，明确生产 HTTPS、网络异常和生物识别锁定等边界场景仍待补测。
- GitHub Actions 首次运行因 runner 中的 `sdkmanager` 不在 PATH 而以退出码 127 失败；删除多余的 SDK 安装步骤后，[CI #2](https://github.com/z987645344-arch/zhiliaohub_app/actions/runs/31090840332) 成功完成 Debug 编译、JVM 单元测试和 Android Lint。

## 0.1.1 - 2026-08-06

- 将 `versionCode` 从 1 递增为 2，`versionName` 从 `0.1.0` 更新为 `0.1.1`。
- 在主界面增加当前版本号展示，便于确认覆盖安装后的实际运行版本。
- `0.1.1` Debug 构建、7 项 JVM 单元测试和 Android Lint 回归通过。
- 使用 `:app:installDebug` 在 Vivo V2405A（Android 15 / API 35）上直接覆盖现有 `0.1.0`；安装过程没有卸载、清数据或签名冲突。
- 覆盖后 `firstInstallTime` 保持不变、`lastUpdateTime` 更新，包版本变为 `0.1.1`。
- 用户实际确认覆盖安装后直接使用保留的 session Cookie 免生物识别进入主界面，且健康状态“在线”。
- 用户实际确认仅清除 session Cookie 后，原 Android Keystore P-256 私钥仍能经生物识别完成挑战登录，无需重新配对。
- 覆盖安装结论：配对状态、加密 session Cookie 和 Keystore 私钥均完整保留。

## 真机验证 - 2026-08-06

- 在 Vivo V2405A（Android 15 / API 35）上实际安装并启动 App `0.1.0`。
- 使用真实本地 `admin-server`、`http://localhost:3001` 和 `adb reverse tcp:3001 tcp:3001` 完成 USB 联调。
- 用户实际确认网页密码登录、首次 TOTP 绑定、一次性配对码生成和手动设备配对均通过。
- 用户实际确认首次生物识别挑战登录及 `/health`“在线”状态通过。
- 用户实际确认从最近任务彻底关闭后，持久 session Cookie 可免生物识别恢复登录。
- 用户实际确认仅清除 session Cookie 后会重新触发生物识别挑战应答登录。
- 用户实际确认网页吊销设备后，App 能明确提示设备已吊销并引导重新配对。
- 本轮 a–h 共 8 项真机验证全部通过。
- 联调中曾误填 `https://localhost:3001` 并得到预期的 HTTPS 连接失败提示；修正为实际 HTTP 开发地址后验证通过。

## 0.1.0 - 2026-08-04

- 初始化独立 Kotlin Android 项目：`minSdk 26`、`targetSdk 36`、原生 XML View。
- 新增可修改的后台服务根地址设置和显式 HTTP 开发风险确认。
- 新增严格 origin 校验、禁用重定向的 OkHttp 网络层。
- 新增 Android Keystore ECDSA P-256 生物识别保护密钥与 SPKI PEM 公钥导出。
- 新增手动一次性配对码流程及格式、认证、限流、网络错误提示。
- 新增 challenge → `BiometricPrompt` → `SHA256withECDSA` DER/Base64 → login 流程。
- 新增 AES-GCM 加密的持久 `zhiliaohub.admin.sid` CookieJar，实现有效 Cookie 下的启动免生物识别登录。
- 新增设备吊销识别、本机凭据清理和重新配对引导。
- 新增只读取真实 `/health` 接口的网站健康状态卡片，并预留未来卡片容器。
- 新增 URL/origin 与配对码格式 JVM 单元测试、README 和状态文档。
- 本轮未实现二维码扫描、内容编辑、备份状态、反馈提醒或其他未有服务端接口的数据。
