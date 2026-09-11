# 知了hub 管理助手（Android）

知了hub 管理助手是 `zhiliaohub` 管理后台的配套原生 Android App。当前版本以仓库 Git 标签现查为准，APK 构建版本以 `app/build.gradle.kts` 为准。当前功能聚焦单设备安全配对、持久会话、P-256 挑战应答登录，以及网站健康、两项目备份状态和本机TOTP三张真实卡片；不包含内容编辑、二维码扫描或虚构的监控数据。

本仓库与主站仓库相互独立。App 只对接服务端公开接口；服务端配套改动仍在独立的 `zhiliaohub` 仓库维护。

兼容性：设备认证依赖 `zhiliaohub admin-server v1.2+`；备份状态卡片依赖 `v3.5+` 提供的认证聚合接口。

## 构建要求

- Android Studio（本项目初始化时使用的本机版本为 AI-253 系列）
- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 或兼容版本
- Gradle Wrapper 8.14（仓库已包含 Wrapper）

在 Android Studio 中打开仓库根目录并等待 Gradle Sync 完成即可运行。项目提供两个共用 `src/main` 源码的构建变体：

| 用途 | Gradle flavor | applicationId | 桌面名称 |
|---|---|---|---|
| 正式版 | `prod` | `com.zhiliaohub.app` | 知了hub |
| 测试版 | `qa` | `com.zhiliaohub.app.test` | 知了hub·测试 |

Android Gradle Plugin保留了以 `test` 开头的flavor名称，因此构建期使用 `qa`；它产出的仍是明确标识为“测试”的独立App。两个包可以同时安装，Android按不同包名/UID隔离DataStore、加密Cookie和Keystore密钥。测试版使用橙色 `T` 图标，正式版继续使用原蓝灰图标。

测试环境地址不会写死在APK中。首次打开测试版仍需在设置页手动填写当前本地地址，避免局域网IP变化后继续误连旧环境。

命令行构建示例：

确保 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向本机 Android SDK 后执行：

```powershell
.\gradlew.bat :app:assembleProdDebug :app:assembleQaDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/prod/debug/app-prod-debug.apk
app/build/outputs/apk/qa/debug/app-qa-debug.apk
```

连接真机后可分别安装；两条命令不会互相覆盖：

```powershell
.\gradlew.bat :app:installProdDebug
.\gradlew.bat :app:installQaDebug
```

运行本地测试与 Lint：

```powershell
.\gradlew.bat :app:testProdDebugUnitTest :app:testQaDebugUnitTest
.\gradlew.bat :app:lintProdDebug :app:lintQaDebug
```

## 首次使用

1. 在设置页输入后台服务根地址，例如 `http://192.168.1.20:3001` 或正式 HTTPS 地址。不要附加 `/api/...` 路径。
2. 使用 HTTP 时必须在醒目警告下显式确认。这只适合可信局域网内的临时开发；正式部署必须改用 HTTPS。
3. 在网页后台先通过密码 + TOTP 登录，再到 `/admin/device` 生成一次性配对码。
4. 在 App 中手动输入 `XXXXX-XXXXX` 配对码并提交。
5. 后续启动会优先使用持久化 session Cookie。Cookie 失效时，App 才申请新挑战并弹出生物识别窗口。

## 开发期连接方式

推荐在可信任的家庭网络内使用同一 WiFi 局域网直连：

1. 手机与电脑连接同一 WiFi。
2. 后台 `.env` 将 `HOST` 设置为 `0.0.0.0` 后重启；启动日志会列出全部检测到的非虚拟 RFC1918 IPv4 局域网访问地址。
3. 只在 Windows 防火墙的“专用网络”范围允许后台端口，不要开放到公用网络或公网。
4. 在 App 设置页手动填写日志中的地址，例如 `http://192.168.1.20:3001`。

仅修改服务器地址不会清除配对标记或 Android Keystore 私钥。Cookie 仍按原始主机隔离，不会从 `localhost` 跨主机发送给局域网 IP；新地址没有有效 Cookie 时，App 会复用原设备私钥正常触发生物识别挑战登录，无需重新配对。

USB 调试场景仍可使用 `adb reverse tcp:3001 tcp:3001` 和 `http://localhost:3001` 作为备选。Android 模拟器访问宿主机通常使用 `http://10.0.2.2:3001`。局域网 IP 可能随路由器分配变化，地址失效时需手动填写新地址；本版本不提供 mDNS、自动发现或 IP 自动更新。

## HTTP 开发模式的边界

`network_security_config.xml` 是静态资源，Android 无法在其中声明运行时由用户输入的域名。因此本项目采用两层限制：

- Android 平台层允许开发期明文流量；
- App 层只接受 `localhost`、`127.0.0.0/8` 和 RFC1918 私有 IPv4（`10/8`、`172.16/12`、`192.168/16`）作为 HTTP 地址，公网地址必须使用 HTTPS；
- App 只根据用户明确保存的根地址构造请求，OkHttp 拦截器校验 scheme、host、port 必须与该地址完全一致，并禁用 HTTP/HTTPS 重定向。

这可以把 App 自身的请求限制到用户指定的 origin，但不能让 HTTP 变得安全。HTTP 下 session Cookie 和响应内容仍可能被窃听或篡改。

## 认证与本地存储

- 设备签名密钥：Android Keystore 内的 ECDSA P-256 (`secp256r1`) 密钥；私钥不可导出，并要求每次签名使用强生物识别授权。
- 公钥：以 PEM 编码的 SPKI `PUBLIC KEY` 形式提交到配对接口。
- 挑战签名：对服务端原样返回的 `signedPayload` UTF-8 字节执行 `SHA256withECDSA`；Android `Signature` 输出 ASN.1 DER，再以 Base64 提交。
- session：只持久化名为 `zhiliaohub.admin.sid` 的 Cookie。Cookie 内容使用另一把 Android Keystore AES-GCM 密钥加密后保存，App 备份与设备迁移均已关闭。
- TOTP：管理员手动输入网页设置页显示的 Base32 密钥；App使用平台 `HmacSHA1` 按RFC 6238生成6位、30秒窗口验证码。密钥由本App专用的Android Keystore AES-GCM密钥加密，DataStore只保存IV与密文；输入、解密和HMAC使用的明文缓冲区用后覆盖。验证码显示与本机解绑均要求强生物识别，退到后台后立即隐藏。
- TOTP密钥永远不会加入网络请求或日志；备份和设备迁移规则排除了包括TOTP DataStore在内的全部App文件域。解绑只清除本机的密文与Keystore密钥，不改变后台或腾讯验证器。
- **已知代价**：同一TOTP密钥同时存在于腾讯验证器和本App，任一App被攻破都等于后台TOTP泄漏。这是“两边都能显示同一个码”的固有代价，用户已知情并接受。
- 非敏感设置：服务器地址和“已配对”标记保存在 Preferences DataStore；该标记不能替代 Keystore 密钥或服务端认证。
- 日志：代码不记录私钥、签名原文、Cookie 或配对码。

更换同一后台的可达地址会保留配对状态和设备签名密钥；旧 session Cookie 继续遵循 host/domain 匹配规则，不会跨主机发送。设置页另行提供“仅清除会话 Cookie”和需要明确确认的“清除本机配对并删除设备密钥”操作。

## 已对接接口

| 接口 | 用途 |
|---|---|
| `POST /api/device-auth/pair` | 提交一次性配对码、设备名和 P-256 SPKI PEM 公钥 |
| `POST /api/device-auth/challenge` | 获取唯一应签名的 `signedPayload` |
| `POST /api/device-auth/login` | 提交 DER/Base64 ECDSA 签名并取得 session Cookie |
| `GET /api/admin/device` | 使用现有 Cookie 探测管理员设备会话是否仍有效 |
| `GET /health` | 显示真实的“在线/离线”和最近检查时间 |
| `GET /api/admin/backup-status` | 在已认证通道读取知了hub与知天各自的状态词和服务端提示；不解析 `diagnostic` |

服务端将“配对码错误、过期或已使用”统一返回为同一个 `401`，App 无法可靠区分这三种服务端原因，因此会如实显示组合提示；本地格式错误和服务器不可达则分别提示。

设备被服务端吊销时，既有管理员设备会话返回 `401`，新的 challenge 返回 `409`。App 据此显示“当前设备已被吊销，请在网页后台重新生成配对码”，清理旧 Cookie 和本机签名密钥，并引导回配对页。

## 项目结构

```text
app/src/main/
├── java/com/zhiliaohub/app/
│   ├── data/       # DataStore 设置与配对清理
│   ├── network/    # URL/origin 校验、OkHttp 与设备认证 API
│   ├── security/   # P-256设备密钥、加密Cookie及本机TOTP
│   └── ui/         # 设置、配对、登录与三张状态卡片
└── res/
    ├── layout/     # 原生 View XML
    └── xml/        # 网络安全与数据导出规则

app/src/qa/res/
└── drawable/ic_app.xml # 仅测试版使用的橙色T图标；无独立Kotlin源码
```

## 主要依赖

| 依赖 | 版本 | 用途 |
|---|---:|---|
| AndroidX AppCompat | 1.7.1 | 原生 View Activity 与兼容主题 |
| AndroidX Activity KTX | 1.13.0 | Activity result API |
| AndroidX Lifecycle Runtime KTX | 2.11.0 | 生命周期协程 |
| AndroidX Biometric | 1.1.0 | `BiometricPrompt` 与 CryptoObject |
| AndroidX DataStore Preferences | 1.2.1 | 非敏感配置持久化 |
| OkHttp | 5.3.0 | HTTP、CookieJar 与严格 origin 网络层 |

构建工具固定为 AGP 8.13.2、Kotlin 2.3.20、Gradle 8.14，以匹配本轮实际安装并验证过的 API 36/JDK 17 环境。

## 当前验证状态

2026-08-14 已完成两个Debug变体的编译、各16项JVM测试和Lint（0 errors），并在Vivo V2405A（Android 15 / API 35）上并行安装。测试版通过独立UID在本地后台完成配对、生物识别登录和健康状态“在线”；正式版覆盖安装保留原生产设置和本地凭据，重启后现有生产会话免生物识别恢复且健康状态“在线”，但过程中观察到一次可恢复的瞬时网络失败。本轮没有清除正式版生产凭据重新配对。此前局域网、USB端到端及覆盖安装验证记录详见 [STATUS.md](STATUS.md)。

2026-09-11 在本机完成备份状态卡片的双变体验证：`prodDebug`、`qaDebug` 各21项JVM测试均0失败，Lint均0 errors。假响应覆盖两侧正常、单侧超期、知天不可达、整体网络失败和401；网络失败保留上次两行结果，401回到既有重新登录流程，模型不包含 `diagnostic`。本轮未安装真机，qa连接本地后台与prod连接生产环境的显示结果仍须由用户实际确认。

2026-09-11 在本机完成TOTP核心与安全契约验证：RFC 6238附录B的6个SHA-1时间点、RFC 4648 Base32有/无填充、固定Speakeasy期望值、AES-GCM密文往返、备份排除、无网络/日志依赖、生物识别显示门和会话刷新401决策均有JVM测试。两个Flavor测试、Lint与Debug构建均通过。真机Keystore/DataStore生命周期、生物识别、与腾讯验证器对码仍须在用户重绑仪式中验证，不能由JVM测试代报通过。

仓库使用 [GitHub Actions](https://github.com/z987645344-arch/zhiliaohub_app/actions) 在 push 或 pull request 到 `main` 时执行 Debug 编译、JVM 单元测试和 Android Lint；CI 不运行模拟器或替代人工真机验证。
