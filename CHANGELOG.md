# Changelog

## v0.6 - 2026-09-11

- **显示/隐藏双轴门控**：`TotpDisplayGate` 在既有生物识别解锁轴之外新增独立遮蔽轴；生物识别成功后默认直接显示，按钮只翻转遮蔽状态，绝不调用 `lock()`。未解锁时切换无效；`lock()` 同时恢复“未解锁 + 默认不遮蔽”的干净初态，因而 `hideTotpCode()` 的全部既有调用点都不会把上一轮遮蔽状态带入下一次验证。
- **真实遮蔽与当前窗口**：遮蔽时 `totpCode.text` 被直接替换为资源 `••••••`，不是透明度、前景色或覆盖层；倒计时协程继续运行并在窗口切换时更新内存中的当前码，取消遮蔽立即重绘当前窗口的6位码，不会回显旧窗口。按钮仅在已绑定时出现，文案由资源在“隐藏验证码 / 显示验证码”间切换。
- **前两轮阻塞修复一并纳入**：保留 `5b697ff` 的登录签名/TOTP Prompt互斥，以及 `2183f1d` 的生命周期必然释放、代次租约和迟到回调隔离；本轮没有合并两个 Prompt，也没有改配对、签名、会话、TOTP密钥存储或RFC 6238实现。
- **版本与标签边界**：`versionCode 6→7`、`versionName 0.5.0→0.6.0`。本轮预检当场核对本地与远程标签均只到 `v0.4`，不存在 `v0.5` Git标签；因此后续 `v0.6` 实际还会覆盖 `96a3730` 的本机TOTP卡片与 `9bb1254` 的0.5.0版本更新，不能把APK内部版本号0.5.0误写成已有Git标签。推送与CI由指挥师执行并已完成：`5b697ff`、`2183f1d`、`ea8036f` 依次推送，最后一次 CI run `34617511961` 全绿（prod/qa 各42项、Lint 0 errors）；附注标签 `v0.6` 打在本条所在的文档提交上，因此标签跨度覆盖上述三个提交及 `96a3730`、`9bb1254`。
- **自动化证据**：prod/qa 两个Flavor各42项JVM测试、0失败、0跳过；新增3项净测试覆盖未解锁切换无效、解锁默认可见、遮蔽不落锁、取消遮蔽取当前码及 `lock()` 双轴复位。两变体Lint均0 errors、7 warnings，两个Debug APK构建成功；`aapt` 从两个产物均读到 `versionCode='7'`、`versionName='0.6.0'`。
- **真机验收（用户执行，2026-09-11）**：遮蔽后倒计时继续走；遮蔽状态跨过一次30秒窗口再取消遮蔽，显示的是当前窗口的码并用它成功登录后台；遮蔽状态按Home返回后回到需重新生物识别的初态，未继承上一轮遮蔽。**仍未验证**：截图与无障碍读屏取值未实测，仅由“文本被替换为 `••••••`”推断。构建侧记录：首次未指定SDK的测试命令未进入编译，沙箱内读取SDK被权限阻断；改为在命令环境中指向既有SDK并获准读取后，完整测试、Lint和构建均通过，未创建或提交 `local.properties`。
- **逐文件改动（本批新增）**：
  - `app/src/main/java/com/zhiliaohub/app/ui/TotpUiState.kt`：+11/-1，增加独立遮蔽轴、未解锁保护与双轴复位。
  - `app/src/main/java/com/zhiliaohub/app/ui/MainActivity.kt`：+28/-7，接入切换按钮、当前窗口码刷新、真实文本替换与控件状态。
  - `app/src/main/res/layout/activity_main.xml`：+9/-0，增加仅已绑定时显示的验证码遮蔽按钮。
  - `app/src/main/res/values/strings.xml`：+2/-0，增加“隐藏验证码 / 显示验证码”资源文案。
  - `app/src/test/java/com/zhiliaohub/app/ui/TotpUiStateTest.kt`：+42/-1，拆分并扩充双轴状态测试。
  - `app/build.gradle.kts`：+2/-2，更新APK内部版本为7 / 0.6.0。
  - `CHANGELOG.md`：+17/-0，记录v0.6候选覆盖、证据、边界与逐文件范围。

## 2026-09-11 修复生物识别互斥锁生命周期死锁（未打标签）

- **上一轮引入的阻塞回归**：`5b697ff` 为阻止登录签名与TOTP两个Prompt互相取消而增加互斥，但没有在 `onStop` / `onDestroy` 释放。真机退后台时系统弹窗可以消失，而终态回调不保证在同一Activity恢复前送达；下一次登录因此被自己遗留的锁拒绝，界面只剩“身份验证正在进行”、TOTP按钮全灰。互斥修复必须同时保证不会死锁，本条明确保留这次回归教训。
- **确切泄漏路径与兜底**：`authenticate()` 抛异常及所有 `onAuthenticationError` 原本已有释放；遗漏的是界面不可见路径。现在 `onStop` 主动取消当前Prompt，并在 `finally` 中清除对应登录/TOTP上下文、释放锁、恢复按钮及可重试提示；`onDestroy` 再做幂等兜底。宁可让用户重新验证一次，也不保留一个没有弹窗的锁。
- **防迟到回调误伤**：协调器由“仅记录用途”升级为带单调代次的租约；每次登录Prompt与TOTP Prompt各自创建回调并捕获本轮租约。旧弹窗的迟到回调即使与新一轮用途相同，也无法释放新租约或覆盖新一轮界面状态。两个Prompt仍分离，登录继续携带 `CryptoObject(signature)`，TOTP继续不携带。
- **原互斥性质保留**：任一Prompt在飞时，另一方在调用 `authenticate()` 前即被拒绝；Android `onAuthenticationFailed` 仍作为非终态识别失败保留锁，成功、全部终态错误、取消和生命周期离开才释放。
- **自动化证据**：两个Flavor的JVM测试均由37项增至39项，最终39/39、0失败；新增/强化断言覆盖登录与TOTP两种生命周期释放、AndroidX 14类终态错误码、同用途新旧租约隔离，以及上一轮双向互斥。两个Flavor Lint与Debug APK构建结果见本轮最终验证。
- **边界**：未修改配对、签名算法、会话协议、TOTP密钥存储、RFC 6238及APK版本。没有代报真机通过；用户仍需实测“登录弹指纹 → Home → 返回App → 重新发起登录”。
- **逐文件改动（本批新增）**：
  - `app/src/main/java/com/zhiliaohub/app/ui/BiometricPromptCoordinator.kt`：+41/-10，引入代次租约、生命周期放弃及终态错误分类。
  - `app/src/main/java/com/zhiliaohub/app/ui/MainActivity.kt`：+86/-48，接入逐轮回调、`onStop`/`onDestroy`取消与必然释放。
  - `app/src/test/java/com/zhiliaohub/app/ui/BiometricPromptCoordinatorTest.kt`：+71/-35，覆盖死锁、终态错误、迟到回调和互斥回归。
  - `CHANGELOG.md`：+14/-0，记录阻塞回归、根因、修复证据与真机边界。

## 2026-09-11 修复登录签名与TOTP生物识别提示互相取消（未打标签）

- **阻塞缺陷与判断纠正**：v0.5 真机联调发现设备配对虽成功，设备登录却始终无法完成；登录签名与TOTP各自持有一个 `BiometricPrompt`，后发起者会取消先前提示。指挥师此前把该问题判断为“不阻塞、下轮再修”，事实证明判断错误，本条保留这次优先级误判。
- **互斥而不合并**：新增共享协调器，在调用 `authenticate()` 前占用；登录签名在飞时，TOTP绑定、显示与解绑按钮禁用并明确提示“请先完成当前的身份验证”；TOTP在飞时，自动登录不会顶掉它，而是显示可重试的忙状态。登录仍使用带 `CryptoObject(signature)` 的Prompt，TOTP仍使用不带CryptoObject的Prompt，密钥用途边界未合并。
- **释放与错误语义**：成功及终态错误/取消均释放互斥；Android `onAuthenticationFailed` 只是一次未识别、Prompt仍在，因此刻意不释放。系统或其他验证流程打断时明确说明“本机密钥未被判定失效”，不再复用“上下文已失效/需要重新绑定”；真正的 `KeyPermanentlyInvalidatedException` 仍走既有重新配对提示。
- **协议边界**：未修改配对、挑战应答、签名算法、登录请求、会话持久化、TOTP密钥存储或RFC 6238实现；`versionCode=6`、`versionName=0.5.0`未改。
- **自动化证据**：两个Flavor的JVM测试均由32项增至37项，最终37/37、0失败；新增5项锁住双向忙状态不会启动第二个Prompt、四种终态均释放、迟到回调不能释放另一Prompt，以及打断文案不误报密钥失效。两个Flavor Lint均0 errors、7 warnings；两个Debug APK均构建成功。
- **未验证**：没有代报真机通过。用户仍需在本地通过 `adb reverse localhost:3001` 完成一次设备登录，确认签名走完、两张备份状态行出现，并确认登录Prompt期间操作TOTP不会打断登录。
- **逐文件改动（本批新增）**：
  - `app/src/main/java/com/zhiliaohub/app/ui/BiometricPromptCoordinator.kt`：+80/-0，共享互斥状态、终态与明确提示。
  - `app/src/main/java/com/zhiliaohub/app/ui/MainActivity.kt`：+135/-8，在两个Prompt发起点接入互斥、按钮状态与终态释放。
  - `app/src/test/java/com/zhiliaohub/app/ui/BiometricPromptCoordinatorTest.kt`：+102/-0，覆盖双向竞争、终态释放与错误语义。
  - `CHANGELOG.md`：+14/-0，记录本条现场证据、判断纠正与验证边界。

## 2026-09-11 新增本机TOTP卡片并补齐会话刷新复核（未打标签）

- **本机TOTP**：新增手输Base32绑定、6位码与30秒倒计时、强生物识别显示门和本机解绑。绑定成功后立即要求生物识别并显示一次验证码，供用户与腾讯验证器当场核对；退到后台即隐藏。TOTP是纯本机能力，服务器会话或网络不可用时卡片仍可进入，不新增后端接口、相机权限或出站请求。
- **实现选择**：使用平台 `javax.crypto.Mac` 的 `HmacSHA1`，自行实现RFC 6238规定的时间计数器与动态截断；严格Base32解码支持RFC 4648末尾填充和无填充输入。未引入TOTP第三方依赖，避免为约二十行协议胶水扩大供应链。
- **密钥边界**：真实Base32密钥不形成持久String；输入先复制到 `CharArray` 并立即清空输入框，规范化、AES与HMAC使用的明文缓冲区均在用后覆盖。Android Keystore内的本App专用AES密钥负责GCM加密，DataStore只保存IV与密文；解绑尝试同时删除密文和Keystore别名。现有 `allowBackup=false`、`fullBackupContent=false` 与 `dataExtractionRules` 已排除整个文件域，无须放宽或新增规则。
- **已知代价**：同一TOTP密钥同时存在于腾讯验证器和本App，任一App被攻破都等于后台TOTP泄漏。这是“两边都能显示同一个码”的固有代价，用户已知情。本轮没有实现同步、上传、二维码扫描或崩溃上报。
- **设备吊销显示滞后**：健康卡“立即检查”现在保留公开 `/health` 请求，同时调用既有认证 `checkSession()`；假401进入原有重新认证流程，不改配对、挑战应答、登录或会话持久化协议。
- **自动化证据**：两个Flavor各32项JVM测试、0失败；新增11项覆盖RFC 6238附录B全部6个SHA-1时间点、Base32填充/无填充与非法输入、固定Speakeasy期望值、AES-GCM密文往返、备份排除、无网络/日志依赖、生物识别显示门、解绑双层清理源码契约和会话刷新401。两个Flavor Lint均0 errors、7 warnings，两个Debug APK均构建成功。
- **未验证**：没有把真实TOTP密钥输入测试环境，也没有在真机执行Keystore/DataStore生命周期、生物识别、倒计时、退后台隐藏、解绑或与腾讯验证器对码；这些步骤留给用户的重绑仪式，不能用JVM测试代报通过。`versionCode=5`、`versionName=0.4.0`未改；本轮不推送、不核CI、不打标签。
- **逐文件改动（本批新增）**：
  - `app/src/main/java/com/zhiliaohub/app/security/Base32Codec.kt`：+79/-0，严格规范化、校验、解码并清理输入缓冲区。
  - `app/src/main/java/com/zhiliaohub/app/security/TotpGenerator.kt`：+47/-0，平台HMAC-SHA1的RFC 6238实现。
  - `app/src/main/java/com/zhiliaohub/app/security/TotpSecretCrypto.kt`：+29/-0，独立可测的AES-GCM密文封装。
  - `app/src/main/java/com/zhiliaohub/app/security/EncryptedTotpSecretStore.kt`：+115/-0，Keystore密钥、密文DataStore与双层清除。
  - `app/src/main/java/com/zhiliaohub/app/ui/TotpUiState.kt`：+41/-0，生物识别显示门与会话刷新决策。
  - `app/src/main/java/com/zhiliaohub/app/ZhiliaohubApplication.kt`：+4/-0，初始化本机TOTP密钥存储。
  - `app/src/main/java/com/zhiliaohub/app/ui/MainActivity.kt`：+227/-3，绑定、显示、倒计时、隐藏、解绑及健康按钮会话复核。
  - `app/src/main/res/layout/activity_main.xml`：+120/-4，增加独立TOTP卡片并把认证卡片单独控制显隐。
  - `app/src/main/res/values/strings.xml`：+14/-0，增加绑定、安全提示、倒计时与解绑文案。
  - `app/src/test/java/com/zhiliaohub/app/security/TotpGeneratorTest.kt`：+60/-0，覆盖RFC与Speakeasy固定期望值。
  - `app/src/test/java/com/zhiliaohub/app/security/TotpSecurityContractTest.kt`：+88/-0，覆盖密文、备份排除、无网络日志依赖及清除契约。
  - `app/src/test/java/com/zhiliaohub/app/ui/TotpUiStateTest.kt`：+51/-0，覆盖生物识别门与认证401。
  - `README.md`：+8/-3；`STATUS.md`：+16/-2，同步安全模型、已知代价、验证证据与真机边界。
  - `CHANGELOG.md`：+25/-0，记录本条现场证据与逐文件范围。

## Git标签 v0.4 - 2026-09-12

- **覆盖 1 条工作条目、3 个提交**，其中 1 个属本轮存档动作本身：2026-09-11 主界面新增两项目备份状态卡片（`a962e95`）、APK 版本更新至 0.4.0（`f772e0a`），以及本条存档提交。Android CI `34581934037` success。
- **版本语义**：真实新功能（备份状态卡片，两行独立显示知了hub / 知天），走两段式 `v0.4`；`versionCode 4→5`、`versionName 0.3.0→0.4.0` 同步。**功能基线由 `v0.3` 推进到 `v0.4`。**
- **跨项目契约**：只解析 `status` 与 `hint`；`diagnostic` 不解析不渲染（代码中命中 0）；两行不合并成总状态；知天不可达只影响那一行；网络失败保留上次结果；401 沿用重新登录流程。
- **验证存档方独立核到的**：`diagnostic` 在 App 源码中命中 0；`versionCode 5` / `versionName 0.4.0` 已在 `build.gradle.kts`；两个 flavor 各 21 项 JVM 测试通过；敏感值 0 命中。
- **未验证**：真机安装与卡片显示由用户完成（qa 包对本地后台、prod 包对生产）；重点看两行是否独立、刷新、断网保留旧结果、401 回登录。

## 2026-09-11 主界面新增两项目备份状态卡片（随 v0.4 一并存档）

- **本轮行为**：主界面预留位改为一张两行备份状态卡片，分别显示知了hub与知天的服务端 `status + hint`；两行独立渲染，知天 `unreachable` 不影响知了hub。`stale`、`unknown`、`unreachable` 使用显眼危险样式，`ok` 低调、`disabled` 中性；刷新行为与健康卡一致。
- **认证与失败边界**：新增的 `GET /api/admin/backup-status` 复用现有 Cookie、`execute()` 和只读请求 500ms/1.5s 退避；认证 POST 分类未改。401继续进入既有重新登录流程；整体网络失败显示“取不到”且保留上次两行结果，不崩溃、不清空。
- **数据克制**：模型只解析两侧的 `status` 与 `hint`，不解析、不渲染 `diagnostic`，也不引入文件名、目录路径或归档数量。日期算术仍由服务端完成，App直接显示服务端 `hint`。
- **验证证据**：`prodDebug`、`qaDebug` 的 JVM 测试均由各16项增至各21项，最终均0失败；五项新增测试覆盖两侧正常、单侧超期、知天不可达、整体网络失败和401，并以含 `diagnostic` 的假响应确认模型没有该字段。两变体 Lint 均0 errors、9 warnings；两变体 Debug APK均实际构建成功。
- **测试环境说明**：Android JVM 首轮执行时，3项成功响应解析测试因 Android SDK 的 `org.json` stub 失败；本轮仅在 `testImplementation` 增加 Java `org.json` 实现，APK运行时依赖未改变。修正后重新执行了完整双变体测试，而不是跳过解析断言。
- **未验证**：本轮没有安装真机，未代报 qa 对本地后台、prod 对生产后台的卡片显示；真实设备需分别确认两行状态、刷新、网络失败保留上次结果和401重新登录。没有推送、没有核CI、没有打标签，交由指挥师后续执行。
- **逐文件改动（本批新增）**：
  - `app/src/main/java/com/zhiliaohub/app/network/ApiResult.kt`：+23/-0，增加严格状态枚举及两侧响应模型。
  - `app/src/main/java/com/zhiliaohub/app/network/NetworkRetryPolicy.kt`：+1/-0，把备份状态声明为可退避重试的只读操作。
  - `app/src/main/java/com/zhiliaohub/app/network/ZhiliaohubApi.kt`：+16/-0，增加认证 GET 与克制解析。
  - `app/src/main/java/com/zhiliaohub/app/ui/BackupStatusUiState.kt`：+32/-0，集中定义状态文案、视觉语义与保留上次结果的状态容器。
  - `app/src/main/java/com/zhiliaohub/app/ui/MainActivity.kt`：+96/-0，接入加载、刷新、独立两行渲染及失败降级。
  - `app/src/main/res/layout/activity_main.xml`：+100/-3，以真实备份卡片替换预留占位。
  - `app/src/main/res/values/strings.xml`：+7/-2，增加卡片文案并移除旧占位文案。
  - `app/src/test/java/com/zhiliaohub/app/network/NetworkRetryPolicyTest.kt`：+2/-1，锁住新增只读操作与认证写入分类。
  - `app/src/test/java/com/zhiliaohub/app/network/BackupStatusTest.kt`：+159/-0，覆盖五种契约与失败场景。
  - `app/build.gradle.kts`：+1/-0；`gradle/libs.versions.toml`：+2/-1，仅为 JVM 测试加入 `org.json` 实现。
  - `README.md`：+5/-2；`STATUS.md`：+16/-4，同步接口、功能、验证边界与待真机事项。
  - `CHANGELOG.md`：+22/-0，记录本条现场证据与逐文件范围。

## Git标签 v0.3.1 - 2026-08-28

- **覆盖 3 条工作条目、5 个提交**，其中 2 个提交属本轮存档动作本身（补记条目与本条存档条目），**不是 5 件工作**：
  - 2026-08-15 校准 README 与 CHANGELOG 的版本标注（提交 `0ef03b5`；条目为本轮补记）
  - 2026-08-18 补 `.gitignore` 的 `.env` 规则（提交 `a82b81e`）
  - 2026-08-18 放宽 `.gitignore` 的模板否定规则为 `!.env*.example`（提交 `d7069ec`）
- **为什么走三段式**：`v0.3..v0.3.1` 全部改动只落在 `.gitignore`、`CHANGELOG.md`、`README.md` 三个文件，**不含任何代码与构建配置改动**（`.kt`/`.gradle`/`AndroidManifest`/CI 工作流命中 0）。按版本号语义，纯文档与忽略规则整理用三段式补丁号，`versionCode` 与 `versionName` 均未变动，APK 行为与 `v0.3` 相同。
- **`0ef03b5` 此前没有属于自己的条目**：它当时只调整了本文件既有标题与 `README.md`，因此在 CHANGELOG 里看不到那轮工作发生过；本轮先补记该条目，再打标，使标签覆盖范围内的每一轮工作都有据可查。
- **两条 2026-08-18 条目标题中的「（未打标签）」保留原样**：它们在写入时是准确的，覆盖关系由本条说明；回头改写历史条目去迎合后来的状态，是文档漂移的起点。

## 2026-08-18 放宽 `.gitignore` 的模板否定规则为 `!.env*.example`（未打标签）

- **修的是一个会静默吞掉文件的缺陷**：原规则 `!.env.example` 只放行这一个确切文件名，而它上一行的 `.env.*` 会挡住 `.env.local.example`、`.env.qa.example` 等任何带中缀的模板。这类文件被忽略后**提交时无声消失**——`git add` 不报错、diff 里看不见、CI 也不会异常，直到有人发现仓库里根本没有那个文件。
- **该缺陷是在主站 `zhiliaohub` 真实撞上的**，不是推演出来的：那轮要交付一个 `.env.local.example` 本机模板，写完后按 7.7 用落盘探针一测才发现它命中 `.env.*` 被排除；若没测，会以为交付完成而文件其实从未入库。主站已在 `d9a4b5e` 修复，本轮把同一处对齐到本仓库。
- **本轮是预防性的**：`zhiliaohub_app` 目前**没有任何已跟踪的 `.env*.example` 文件**，改完当下不影响任何现有文件。改它是为了防止将来有人往仓库里加模板时无声失败——那种失败没有任何报错可循，只能靠事后察觉。
- **实测口径以真实文件为准，不靠读 `.gitignore` 推断**：落盘 8 个探针后按 `git status --ignored` 归类核对——`.env`、`.env.local`、`.env.bak-1`、`.env.production`、`app/.env` 全部为 `!!`（仍被挡住），`.env.example`、`.env.local.example`、`app/.env.local.example` 全部为 `??`（可被 `git add` 看见）。探针已删除，仓库无残留。
- 本轮只改 `.gitignore` 一行（恰好 1 增 1 删）与本文件，未动其他忽略规则、未改任何代码、未连接服务器。依据主站手册 8.1，本轮不打标签。

## 2026-08-18 补 `.gitignore` 的 `.env` 规则（未打标签）

- 本仓库此前**完全没有任何 env 忽略规则**，连 `.env` 本体都不被忽略——只要在工作区里出现 `.env` 或其备份，`git add .` 就会把它纳入提交。本轮在 `.gitignore` 末尾补入 `.env`、`.env.*`、`!.env.example` 三行，与主站 `zhiliaohub` 同轮对齐；三行均不带路径锚定，因此对任意层级生效（含 `app/.env`）。
- 起因是主站侧的一次真实暴露：2026-08-16 在服务器改配置时于仓库目录内建了 4 个含真实密钥的 `.env` 备份。排查时用 `git check-ignore` 实测六个仓库，才发现本仓库连基本规则都没有——原记载以为已覆盖。
- 实测口径以 `git check-ignore` 与真实文件为准，不靠读 `.gitignore` 推断：改后 `.env`、`.env.bak-1`、`.env.local`、`app/.env` 均被忽略；`.env.example` 用新建未跟踪探针文件确认仍会进入未跟踪列表（`git check-ignore -v` 匹配否定规则时也返回 0，退出码会误导），探针已清理。
- 本仓库当前没有任何已跟踪的 `.env*` 文件，本轮改动不影响任何现有文件；不改代码、不改构建配置、不打标签。

## 2026-08-15 校准 README 与 CHANGELOG 的版本标注（未打标签）

- **补记条目。** 该轮改动（提交 `0ef03b5`）当时只调整了本文件既有标题与 `README.md`，**没有为它自身留下条目**，因此在 CHANGELOG 里看不到这轮工作发生过。本条按当时的实际改动补写，内容取自该提交的 diff，不含任何事后追加的验证结论。
- **区分三类被混用的版本标注。** 本文件原先用同一层级的标题混写了三种不同性质的东西：Git 标签、APK 的 `versionName`、以及一次性事件记录。该轮把它们分别改为 `Git标签 v0.3` / `v0.2` / `v0.1`、`APK versionName 0.1.1` / `0.1.0`、`事件记录：首次真机验证`，使读者不必逐条读正文才能分辨某个号是标签还是安装包版本。
- **更正一处已不成立的表述。** `v0.3` 条目下的「双构建变体（上一轮，保持未提交）」改为「随 v0.3 一并存档」——该轮工作事实上已被 `v0.3` 收录，原措辞停留在存档之前的状态。
- **更正 README 两处**：补明当前仓库存档版本；并把真机验证记录中的测试项数由「各9项」更正为「各16项」。

## Git标签 v0.3 - 2026-08-14

- 将 `versionCode` 从3递增为4，`versionName` 从 `0.2.0` 更新为 `0.3.0`；本轮以 `v0.3` 标签存档并推送到 [GitHub仓库](https://github.com/z987645344-arch/zhiliaohub_app)。

### 网络切换容错（本轮）

- 会话检查与健康检查两个只读GET请求新增500毫秒、1.5秒两级退避，网络传输失败时最多自动重试2次；配对、申请挑战和登录三个认证POST明确保持零自动重试。
- OkHttp客户端启用Fast Fallback并共享连接池；Application使用系统默认网络回调监听WiFi、移动网络的建立、断开与切换，网络变化时清理可能失效的旧连接。
- 网络提示细分DNS解析失败、连接超时、连接重置、一般连接中断及TLS/证书失败，并携带真实自动重试次数；401、429和常见HTTP状态使用清晰中文兜底文案。
- 两个变体各16项JVM测试全部通过，两个Debug APK构建成功，Lint均为0 errors；测试覆盖退避次数/间隔、只读与认证写请求分类、TLS和HTTP不重试及错误文案。
- Vivo V2405A真实覆盖安装后，从WiFi切换到移动网络时复现“连接被网络或代理线路重置”，最终APK完成2次自动重试并准确显示重试次数；当前移动数据链路最终仍失败。两轮WiFi恢复测试均无需杀死App：第一轮较快恢复，第二轮约等待40秒并手动重试后才恢复生产会话和健康状态“在线”，说明App恢复机制有效但底层线路稳定时间不可控。
- 未能强制复现此前代理软件香港节点内部DNS切换的完全相同过程；真实代理线路下的长期改善效果仍需后续使用中观察。当前移动数据到生产域名的底层线路问题不属于App重试逻辑能够修复的范围。

### 双构建变体（上一轮，随 v0.3 一并存档）

- 新增 `prod` 与 `qa` 两个Product Flavor，共用唯一的 `src/main` Kotlin/资源实现，不复制认证、网络或Keystore代码。AGP禁止flavor名称以 `test` 开头，因此构建期使用 `qa`，测试APK仍采用独立包名 `com.zhiliaohub.app.test`。
- 正式版桌面名称为“知了hub”并保留原蓝灰图标；测试版桌面名称为“知了hub·测试”，使用flavor专属橙色 `T` 图标，降低误操作生产环境的风险。
- 两个包名对应不同Android UID，DataStore服务器地址、配对标记、加密session Cookie和Android Keystore密钥由系统天然隔离；未修改认证与网络请求逻辑。
- 测试环境地址不写死，继续由用户按当前局域网/USB环境手动设置。
- CI改为显式构建、测试和Lint `prodDebug`、`qaDebug` 两个变体。
- 本机已真实完成两个Debug APK构建、每个变体9项JVM测试及Lint（0 errors），并在Vivo V2405A上并行安装；正式版覆盖保留既有数据，测试版以全新UID启动且未读取生产设置。测试版已在本地后台完成独立配对与登录；正式版生产会话最终恢复且健康状态在线，但过程中观察到一次可恢复的瞬时网络失败。完整结果见 `STATUS.md`。

## Git标签 v0.2 - 2026-08-07

- 将 `versionCode` 从 2 递增为 3，`versionName` 从 `0.1.1` 更新为 `0.2.0`；本轮以 `v0.2` 标签存档并推送到 [GitHub仓库](https://github.com/z987645344-arch/zhiliaohub_app)。

- HTTP 开发地址新增 localhost、回环及 RFC1918 私有 IPv4 白名单，拒绝任意公网 HTTP 地址；OkHttp 唯一 origin 锁定和禁用重定向保持不变。
- 修改同一后台的服务器地址不再清除配对标记、session 存储或 Keystore 设备密钥；Cookie 仍按主机隔离，新地址必要时复用原密钥走生物识别挑战登录。
- 设置页新增同一 WiFi 与可信任家庭网络提示，README 补充局域网直连和 USB `adb reverse` 备选步骤。
- 9 项 JVM 单元测试、Debug APK 构建和 Android Lint（0 errors）通过。
- 在 Vivo V2405A 上清空全部 `adb reverse` 后，使用同一 WiFi 的电脑 RFC1918 地址完成真实挑战登录；地址切换保留配对和 Keystore 私钥，健康状态显示“在线”。
- 用户关闭 WiFi 后 App 明确显示请求超时，重新开启 WiFi 后网站健康状态恢复，无需重新配对。
- 首次联调曾因后台由 Codex 沙箱权限启动、真实 SQLite 写入受限而返回 HTTP 500；改用真实用户权限启动后 challenge 返回 `201`。该问题属于测试进程权限，不是 App 或认证协议缺陷。

## Git标签 v0.1 首次存档 - 2026-08-06

- 已将当前 Kotlin Android 工程以创世提交 `74b89b1` 存档到 [GitHub 仓库](https://github.com/z987645344-arch/zhiliaohub_app)。
- 新增 GitHub Actions CI，对 `main` 的 push 和 pull request 执行 `assembleDebug`、`testDebugUnitTest` 与 `lintDebug`。
- 首次存档包含设备配对、挑战应答登录、session 持久化、吊销处理、健康状态卡片，以及已通过的 8 项真机端到端验证和覆盖安装验证。
- 使用 `v0.1` 标签，明确生产 HTTPS、网络异常和生物识别锁定等边界场景仍待补测。
- GitHub Actions 首次运行因 runner 中的 `sdkmanager` 不在 PATH 而以退出码 127 失败；删除多余的 SDK 安装步骤后，[CI #2](https://github.com/z987645344-arch/zhiliaohub_app/actions/runs/31090840332) 成功完成 Debug 编译、JVM 单元测试和 Android Lint。

## APK versionName 0.1.1 - 2026-08-06

- 将 `versionCode` 从 1 递增为 2，`versionName` 从 `0.1.0` 更新为 `0.1.1`。
- 在主界面增加当前版本号展示，便于确认覆盖安装后的实际运行版本。
- `0.1.1` Debug 构建、7 项 JVM 单元测试和 Android Lint 回归通过。
- 使用 `:app:installDebug` 在 Vivo V2405A（Android 15 / API 35）上直接覆盖现有 `0.1.0`；安装过程没有卸载、清数据或签名冲突。
- 覆盖后 `firstInstallTime` 保持不变、`lastUpdateTime` 更新，包版本变为 `0.1.1`。
- 用户实际确认覆盖安装后直接使用保留的 session Cookie 免生物识别进入主界面，且健康状态“在线”。
- 用户实际确认仅清除 session Cookie 后，原 Android Keystore P-256 私钥仍能经生物识别完成挑战登录，无需重新配对。
- 覆盖安装结论：配对状态、加密 session Cookie 和 Keystore 私钥均完整保留。

## 事件记录：首次真机验证 - 2026-08-06

- 在 Vivo V2405A（Android 15 / API 35）上实际安装并启动 App `0.1.0`。
- 使用真实本地 `admin-server`、`http://localhost:3001` 和 `adb reverse tcp:3001 tcp:3001` 完成 USB 联调。
- 用户实际确认网页密码登录、首次 TOTP 绑定、一次性配对码生成和手动设备配对均通过。
- 用户实际确认首次生物识别挑战登录及 `/health`“在线”状态通过。
- 用户实际确认从最近任务彻底关闭后，持久 session Cookie 可免生物识别恢复登录。
- 用户实际确认仅清除 session Cookie 后会重新触发生物识别挑战应答登录。
- 用户实际确认网页吊销设备后，App 能明确提示设备已吊销并引导重新配对。
- 本轮 a–h 共 8 项真机验证全部通过。
- 联调中曾误填 `https://localhost:3001` 并得到预期的 HTTPS 连接失败提示；修正为实际 HTTP 开发地址后验证通过。

## APK versionName 0.1.0 - 2026-08-04

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
