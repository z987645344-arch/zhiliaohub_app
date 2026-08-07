# 项目状态

> 最后更新：2026-08-07

## 当前进度

Android App 当前正式存档版本（Git 标签）为 `v0.2`，APK 内部 `versionName` 为 `0.2.0`。同一 WiFi 局域网直连所需的 HTTP 私网地址约束、地址切换不重置配对和设置页提示已实现，并在 Vivo V2405A（Android 15 / API 35）上完成无 `adb reverse` 的真实验证。此前的 USB 反向端口端到端验证和不卸载覆盖安装验证也已通过。远程仓库为 [z987645344-arch/zhiliaohub_app](https://github.com/z987645344-arch/zhiliaohub_app)。

CI 已配置为在 push 和 pull request 到 `main` 时执行 Debug 编译、JVM 单元测试和 Android Lint。[CI #2](https://github.com/z987645344-arch/zhiliaohub_app/actions/runs/31090840332) 已在修正 runner 的 `sdkmanager` PATH 差异后真实运行成功；首次失败记录仍保留在 Actions 历史中。

## 已完成

- Kotlin 原生 Android 工程与 Gradle Wrapper。
- `minSdk 26`、`targetSdk 36`，原生 XML View 风格统一。
- 服务器地址设置、空值/格式校验、HTTP 开发警告与显式确认。
- HTTP 开发地址仅允许 localhost、回环地址与 RFC1918 私有 IPv4；公网地址必须使用 HTTPS。
- 修改服务器地址保留配对标记与 Keystore 私钥，Cookie 继续按主机隔离，必要时走生物识别挑战登录。
- 设置页提示同一 WiFi 与可信任家庭网络边界。
- DataStore 保存服务器地址和非敏感配对标记。
- Keystore ECDSA P-256 密钥生成；私钥不可导出并要求强生物识别后才能签名。
- 手动配对、挑战应答登录、DER/Base64 签名提交。
- Keystore AES-GCM 加密 session Cookie 持久化。
- 启动时优先探测 Cookie 会话；失效后才进入生物识别挑战登录。
- 设备吊销后的明确提示、凭据清理与重新配对入口。
- `/health` 在线/离线状态和最近检查时间；仅预留未来卡片位置，不生成虚假指标。

## 已验证

- `:app:assembleDebug` 构建成功。
- Debug APK 已生成于 `app/build/outputs/apk/debug/app-debug.apk`。
- `:app:testDebugUnitTest`：9 项通过，0 失败，覆盖服务器 URL/origin、HTTP 私网范围与配对码格式逻辑。
- `:app:lintDebug`：0 errors；已检查 Android API、资源和安全配置。本轮保留开发 HTTP 与固定兼容版本产生的说明性 warning。
- `:app:installDebug` 已将 `0.1.0` 干净安装到 Vivo V2405A（Android 15 / API 35）。
- 本地真实 `admin-server` 在 `127.0.0.1:3001` 启动，`/health` 返回 `ok`；真机通过 `adb reverse tcp:3001 tcp:3001` 访问 `http://localhost:3001`。
- `0.1.1` 构建、7 项 JVM 单元测试和 Android Lint 回归通过，并通过 `:app:installDebug` 在不卸载、不清数据的情况下覆盖现有 `0.1.0`。

### 真机端到端验证（2026-08-06）

| 项目 | 真实结果 |
|---|---|
| a. 网页管理员密码登录 | 通过 |
| b. 首次 TOTP 扫码绑定并输入动态码登录 | 通过 |
| c. 网页设备管理生成一次性配对码 | 通过 |
| d. App 设置开发地址并手动输入配对码 | 通过 |
| e. 首次配对后生物识别、进入主界面及健康状态“在线” | 通过 |
| f. 从最近任务划掉并重启，Cookie 有效时免生物识别登录 | 通过 |
| g. 仅清除 session Cookie 后触发挑战应答和生物识别登录 | 通过 |
| h. 网页吊销设备后，App 明确提示并引导重新配对 | 通过 |

验证过程中曾把开发地址误填为 `https://localhost:3001`，App 正确显示 HTTPS 安全连接失败。改为实际协议 `http://localhost:3001`、确认开发风险并重新生成五分钟配对码后，后续流程全部通过；该现象属于本地协议配置不一致，不是设备认证缺陷。

### 覆盖安装验证（0.1.0 → 0.1.1，2026-08-06）

结论：**通过，覆盖安装完整保留配对状态、Keystore 私钥和登录 session。**

| 检查项 | 真实结果 |
|---|---|
| 测试起点 | `0.1.0` 已配对、已有有效 session，启动时免生物识别且健康状态在线 |
| 安装方式 | `:app:installDebug` 直接安装 `0.1.1`，未卸载旧包、未清除 App 数据、无签名冲突 |
| 包更新时间证据 | `firstInstallTime` 保持 `2026-08-06 16:39:46`；`lastUpdateTime` 更新为 `2026-08-06 17:21:17` |
| 配对状态 | 覆盖后没有进入配对页，仍识别为已配对 |
| session Cookie | 覆盖后直接显示“现有会话仍有效，已免生物识别登录” |
| Keystore 私钥 | 用户仅清除 session Cookie 后成功触发生物识别挑战登录，无需重新配对，证明原签名密钥仍可用 |
| 健康状态 | 覆盖后直接登录及清 Cookie 后挑战登录两种场景均显示“在线” |
| 可见版本 | 主界面显示“版本 0.1.1” |

### 同一 WiFi 局域网直连验证（2026-08-07）

结论：**通过，修改可达地址不丢失配对状态或 Keystore 私钥，断网与恢复反馈符合预期。**

| 检查项 | 真实结果 |
|---|---|
| 网络路径 | 手机与电脑处于同一 WiFi；`adb reverse --list` 为空，App 使用电脑的 RFC1918 WLAN 地址和端口连接后台 |
| 后台监听 | 真实 `.env` 使用 `HOST=0.0.0.0`；回环地址和 WLAN 地址访问 `/health` 均返回 `ok` |
| 地址切换 | 从 `localhost` 切换到 WLAN IP 后没有进入配对页，已有配对标记和 P-256 Keystore 私钥保留 |
| 登录恢复 | 旧 host-only Cookie 没有跨主机发送，App 正常触发生物识别挑战登录并成功进入主界面，无需重新配对 |
| 健康状态 | 用户确认网站健康状态显示“在线” |
| WiFi 断开 | 用户关闭 WiFi 后，App 明确显示请求超时，没有无声卡死 |
| WiFi 恢复 | 用户重新开启 WiFi 后，网站健康状态恢复正常，无需重新配对 |

联调中首次用 Codex 沙箱权限启动真实后台时，SQLite 主库写入被环境限制为只读，challenge 因此返回 HTTP 500；改用真实用户权限启动同一代码后，接口返回 `201` 并完成上述验证。该现象属于测试进程权限配置，不是认证协议或局域网实现缺陷。

## 尚未验证

- 未验证生产域名、真实 HTTPS 证书和反向代理环境。
- 未验证系统生物识别锁定、取消、重新录入导致 Keystore 密钥失效的真机分支。
- 未在真机上专门压测配对/认证限流、挑战过期和并发请求。

## 已知协议限制

- 服务端对配对码错误、过期、已使用统一返回 `401` 和同一错误文本，App 只能如实显示组合原因；如需分别判断，必须由服务端未来增加稳定的机器可读错误码。
- 静态 `network_security_config.xml` 不能声明运行时输入的 CIDR。平台层允许开发期明文流量，App 层把 HTTP 配置限制到 localhost、回环与 RFC1918 私有 IPv4，并继续使用唯一 origin 拦截和禁用重定向；HTTP 本身仍不安全。
- 服务端只有单个有效设备。另一设备完成新配对后，本机签名可能表现为被拒绝；App 会建议重试或重新配对。

## 下一步待办

1. 正式部署后切换 HTTPS，复核证书链、域名与代理行为。
2. 补测生物识别取消/锁定/重新录入、限流和挑战过期分支。
3. 后续按真实接口增加备份状态、反馈提醒等监控卡片。
4. 仅在产品明确需要时增加二维码扫描配对；当前继续保持手动输入。
