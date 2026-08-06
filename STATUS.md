# 项目状态

> 最后更新：2026-08-06

## 当前进度

Android App 首轮骨架与设备认证流程已实现，当前正式存档版本（Git 标签）为 `v0.1`，APK 内部 `versionName` 为 `0.1.1`。已在 Vivo V2405A（Android 15 / API 35）上完成真实后台、USB 反向端口、生物识别参与的完整端到端验证，以及从 `0.1.0` 到 `0.1.1` 的不卸载覆盖安装验证。远程仓库为 [z987645344-arch/zhiliaohub_app](https://github.com/z987645344-arch/zhiliaohub_app)。

CI 已配置为在 push 和 pull request 到 `main` 时执行 Debug 编译、JVM 单元测试和 Android Lint。[CI #2](https://github.com/z987645344-arch/zhiliaohub_app/actions/runs/31090840332) 已在修正 runner 的 `sdkmanager` PATH 差异后真实运行成功；首次失败记录仍保留在 Actions 历史中。

## 已完成

- Kotlin 原生 Android 工程与 Gradle Wrapper。
- `minSdk 26`、`targetSdk 36`，原生 XML View 风格统一。
- 服务器地址设置、空值/格式校验、HTTP 开发警告与显式确认。
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
- `:app:testDebugUnitTest`：7 项通过，0 失败，覆盖服务器 URL/origin 与配对码格式逻辑。
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

## 尚未验证

- 未验证生产域名、真实 HTTPS 证书和反向代理环境。
- 未验证网络断开/切换、请求超时及 USB 连接中断后的真机恢复体验。
- 未验证系统生物识别锁定、取消、重新录入导致 Keystore 密钥失效的真机分支。
- 未在真机上专门压测配对/认证限流、挑战过期和并发请求。

## 已知协议限制

- 服务端对配对码错误、过期、已使用统一返回 `401` 和同一错误文本，App 只能如实显示组合原因；如需分别判断，必须由服务端未来增加稳定的机器可读错误码。
- 静态 `network_security_config.xml` 不能声明运行时输入的域名。当前通过设置页确认、唯一配置 origin、OkHttp origin 拦截及禁用重定向共同约束 HTTP 开发请求；HTTP 本身仍不安全。
- 服务端只有单个有效设备。另一设备完成新配对后，本机签名可能表现为被拒绝；App 会建议重试或重新配对。

## 下一步待办

1. 正式部署后切换 HTTPS，复核证书链、域名与代理行为。
2. 补测网络异常、生物识别取消/锁定/重新录入、限流和挑战过期分支。
3. 后续按真实接口增加备份状态、反馈提醒等监控卡片。
4. 仅在产品明确需要时增加二维码扫描配对；当前继续保持手动输入。
