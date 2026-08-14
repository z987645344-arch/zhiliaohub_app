# Changelog

## v0.3 - 2026-08-14

- 将 `versionCode` 从3递增为4，`versionName` 从 `0.2.0` 更新为 `0.3.0`；本轮以 `v0.3` 标签存档并推送到 [GitHub仓库](https://github.com/z987645344-arch/zhiliaohub_app)。

### 网络切换容错（本轮）

- 会话检查与健康检查两个只读GET请求新增500毫秒、1.5秒两级退避，网络传输失败时最多自动重试2次；配对、申请挑战和登录三个认证POST明确保持零自动重试。
- OkHttp客户端启用Fast Fallback并共享连接池；Application使用系统默认网络回调监听WiFi、移动网络的建立、断开与切换，网络变化时清理可能失效的旧连接。
- 网络提示细分DNS解析失败、连接超时、连接重置、一般连接中断及TLS/证书失败，并携带真实自动重试次数；401、429和常见HTTP状态使用清晰中文兜底文案。
- 两个变体各16项JVM测试全部通过，两个Debug APK构建成功，Lint均为0 errors；测试覆盖退避次数/间隔、只读与认证写请求分类、TLS和HTTP不重试及错误文案。
- Vivo V2405A真实覆盖安装后，从WiFi切换到移动网络时复现“连接被网络或代理线路重置”，最终APK完成2次自动重试并准确显示重试次数；当前移动数据链路最终仍失败。两轮WiFi恢复测试均无需杀死App：第一轮较快恢复，第二轮约等待40秒并手动重试后才恢复生产会话和健康状态“在线”，说明App恢复机制有效但底层线路稳定时间不可控。
- 未能强制复现此前代理软件香港节点内部DNS切换的完全相同过程；真实代理线路下的长期改善效果仍需后续使用中观察。当前移动数据到生产域名的底层线路问题不属于App重试逻辑能够修复的范围。

### 双构建变体（上一轮，保持未提交）

- 新增 `prod` 与 `qa` 两个Product Flavor，共用唯一的 `src/main` Kotlin/资源实现，不复制认证、网络或Keystore代码。AGP禁止flavor名称以 `test` 开头，因此构建期使用 `qa`，测试APK仍采用独立包名 `com.zhiliaohub.app.test`。
- 正式版桌面名称为“知了hub”并保留原蓝灰图标；测试版桌面名称为“知了hub·测试”，使用flavor专属橙色 `T` 图标，降低误操作生产环境的风险。
- 两个包名对应不同Android UID，DataStore服务器地址、配对标记、加密session Cookie和Android Keystore密钥由系统天然隔离；未修改认证与网络请求逻辑。
- 测试环境地址不写死，继续由用户按当前局域网/USB环境手动设置。
- CI改为显式构建、测试和Lint `prodDebug`、`qaDebug` 两个变体。
- 本机已真实完成两个Debug APK构建、每个变体9项JVM测试及Lint（0 errors），并在Vivo V2405A上并行安装；正式版覆盖保留既有数据，测试版以全新UID启动且未读取生产设置。测试版已在本地后台完成独立配对与登录；正式版生产会话最终恢复且健康状态在线，但过程中观察到一次可恢复的瞬时网络失败。完整结果见 `STATUS.md`。

## v0.2 - 2026-08-07

- 将 `versionCode` 从 2 递增为 3，`versionName` 从 `0.1.1` 更新为 `0.2.0`；本轮以 `v0.2` 标签存档并推送到 [GitHub仓库](https://github.com/z987645344-arch/zhiliaohub_app)。

- HTTP 开发地址新增 localhost、回环及 RFC1918 私有 IPv4 白名单，拒绝任意公网 HTTP 地址；OkHttp 唯一 origin 锁定和禁用重定向保持不变。
- 修改同一后台的服务器地址不再清除配对标记、session 存储或 Keystore 设备密钥；Cookie 仍按主机隔离，新地址必要时复用原密钥走生物识别挑战登录。
- 设置页新增同一 WiFi 与可信任家庭网络提示，README 补充局域网直连和 USB `adb reverse` 备选步骤。
- 9 项 JVM 单元测试、Debug APK 构建和 Android Lint（0 errors）通过。
- 在 Vivo V2405A 上清空全部 `adb reverse` 后，使用同一 WiFi 的电脑 RFC1918 地址完成真实挑战登录；地址切换保留配对和 Keystore 私钥，健康状态显示“在线”。
- 用户关闭 WiFi 后 App 明确显示请求超时，重新开启 WiFi 后网站健康状态恢复，无需重新配对。
- 首次联调曾因后台由 Codex 沙箱权限启动、真实 SQLite 写入受限而返回 HTTP 500；改用真实用户权限启动后 challenge 返回 `201`。该问题属于测试进程权限，不是 App 或认证协议缺陷。

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
