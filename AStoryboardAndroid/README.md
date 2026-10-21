# AStoryboardAndroid

Moon 的原生 Android 客户端：Kotlin + Jetpack Compose + Material 3。
替代 `AStoryboardMobile`（Expo / React Native）这条线；RN 工程暂时保留，等原生功能对齐后再归档。

## 交互结构

手机端只有一个主界面：**对话**。

```
顶栏（高度 52dp）
  我的项目            +      ⋮
  Hello ▾
  ├─ 点击「项目 + 对话」→ 切换对话
  ├─ +                → 新建对话
  └─ ⋮                → 项目库 / 生成中心 / 生成记录 / 设置
```

- 没有底部 Tab；项目库、生成中心、生成记录、设置都是二级页，系统返回键回到对话。
- 手机端**不创建项目**：没有项目时显示空状态，引导去网页端创建。
- 生成记录来自 `/api/agent/generation-records`；生成中心会新建一条会话并发送生成指令。

## 运行

前提：JDK 21、Android SDK 36、Android Studio。

1. Android Studio 打开 `AStoryboardAndroid/`（不是仓库根目录）。
2. 同步后运行 `app`。
3. 后端：`8080` 网关必须可访问；`8082`/`8084` 由网关代路由。

### API 地址

| 场景 | 地址 | 做法 |
|---|---|---|
| Android 模拟器 | `http://10.0.2.2:8080` | 默认，无需配置 |
| USB 真机 | `http://127.0.0.1:8080` | `adb reverse tcp:8080 tcp:8080`，构建时加 `-PAPI_BASE_URL=http://127.0.0.1:8080` |
| 局域网真机 | `http://<电脑IP>:8080` | 构建时加 `-PAPI_BASE_URL=http://192.168.x.x:8080`，防火墙放行 8080 |

命令行构建（第一次需要网络下载依赖）：

```powershell
# 如果 gradlew 拉不到 Gradle 8.13，直接用本机已解压的 Gradle
E:\Development\gradle\wrapper\dists\gradle-8.13-bin\ap7pdhvhnjtc6mxtzz89gkh0c\gradle-8.13\bin\gradle.bat `
  :app:assembleDebug -PAPI_BASE_URL=http://127.0.0.1:8080 --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 目录

```
app/src/main/java/com/moon/storyboard/
├── StoryboardApp.kt          Application：SessionStore + ApiClient
├── MainActivity.kt
├── data/
│   ├── ApiClient.kt          OkHttp + JWT 拦截器 + turn SSE 解析
│   ├── Models.kt             kotlinx.serialization DTO
│   └── SessionStore.kt       SharedPreferences 登录态
└── ui/
    ├── AppRoot.kt            登录门禁
    ├── MainScaffold.kt       对话主界面 + 四个二级页
    ├── MainViewModel.kt      项目/会话/消息/记录/生成
    ├── components/           TopBar、Pill、EmptyState、缩略图
    ├── login/                登录
    ├── chat/                 对话、SSE、会话切换
    ├── library/              项目库（人物/道具/场景）
    ├── generate/             生成中心 + 生成记录
    ├── settings/             设置（账号、切换项目、退出）
    └── theme/                Material 3 暖纸色 + 珊瑚色
```

## 已实现

- 邮箱密码登录，token 持久化；401 自动用 refreshToken 重试（后端当前登录响应没有 refreshToken 时只保留 accessToken）。
- 会话按需创建：首条消息才落会话，不再预建空对话。
- 记住上次打开的会话（`lastConversationId`），重启回到原对话，而不是列表里最新的空会话。
- `POST /turns/stream` SSE：`TEXT_BLOCK_DELTA` 实时正文、`turn_completed` 收尾、`turn_failed` 报错。
- 项目库、生成中心、生成记录、设置。
- 生成记录缩略图：相对 `/api/files/...` 自动补全为绝对地址，签名 token 原样保留。
- 生成中心参考素材：系统相册选图 → `POST /api/agent/upload` → 以 `picUrls` 随本轮 turn 发送，最多 5 张，可逐张移除。
- 手机端不创建项目：没有项目时只提示去网页端创建，项目切换仅限已有项目。

## 已知限制

- HITL 确认卡片还没做：`turn_paused` 目前按「当前正文收尾」处理，后续要补 resume 流程。
- 助手正文是纯文本渲染，Markdown 的标题/列表/代码块还没有专门排版。
- 没有推送；`expo-notifications` 那套与原生无关，后续接 FCM 或 Web Push 另做。
- 生成中心只选类型/画幅/数量，模型走网关默认；模型选择器后续再加。
- 参考素材只支持相册图片，暂不支持视频/音频参考，也没有从项目库里选资产。

## 验证

- `:app:assembleDebug` 通过（AGP 8.12 / Gradle 8.13 / Kotlin 2.0.21 / compileSdk 36）。
- 已在 `emulator-5554` 上安装运行，验证登录、SSE 回复、菜单、项目库、生成中心、生成记录、设置。

截图：`docs/screenshots/android/`。
