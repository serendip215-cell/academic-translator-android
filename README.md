# 文献翻译助手（AcademicTranslator）

一款面向**安卓平板**的学术文献翻译工具：在 PDF 阅读器、浏览器或任何文献 App 中**选中英文文本**，屏幕上会自动在选区旁边弹出**中英对照悬浮面板**（平板横屏左右并排）。翻译针对**计算机科学文献**优化：内置 CS 术语表、保留公式/代码/引用标记、术语首次出现附英文原文。

## 功能特性

- **自动捕获选中文本**：基于 AccessibilityService 监听 `TYPE_VIEW_TEXT_SELECTION_CHANGED`，450ms 防抖、自动去重，无需手动点按钮。
- **旁边对照显示**：`TYPE_APPLICATION_OVERLAY` 悬浮窗，自动定位到选区下方/上方，可拖动标题栏移动，面板内可滚动、可选择文本、一键复制译文。
- **兜底入口**：同时注册了 `ACTION_PROCESS_TEXT`，在不发选区事件的 App 里，可从系统文本选择菜单点“翻译（文献助手）”。
- **三种翻译引擎，自动回退**：
  1. **大模型 API（推荐）**：任意 OpenAI Chat Completions 兼容端点（OpenAI、DeepSeek、Kimi、通义、OpenRouter、本地 vLLM/Ollama 网关等），使用学术翻译 Prompt + 动态术语表；
  2. **ML Kit 设备端离线翻译**：免费、离线，需 Google Play 服务；
  3. **MyMemory 免费在线翻译**：免 Key，适合临时测试。
- **计算机术语表**：`app/src/main/assets/cs_glossary.json` 内置约 70 条常用术语（Transformer、注意力、微调、RLHF、MoE、量化、消融实验等），并支持在设置页追加自定义术语（每行 `english=中文`）。
- **隐私**：API Key 仅保存在本机 SharedPreferences；选中文本只发送到你选择的引擎；密码框内容一律忽略。

## 编译与安装

环境要求：**Android Studio Hedgehog（2023.1）或更新版本**、JDK 17。minSdk 26（Android 8.0），targetSdk 34。

1. Android Studio → `Open` → 选择本目录 `AcademicTranslator`；首次同步会自动下载 Gradle 8.9 与依赖。
2. 平板开启“开发者选项 → USB 调试”，连接电脑，点 `Run`。
   - 命令行：在项目根目录执行 `gradle wrapper`（若本机有 Gradle）生成 wrapper 脚本后，`./gradlew assembleDebug`，产物在 `app/build/outputs/apk/debug/app-debug.apk`。
3. 打开 App，依次：
   - 授予**悬浮窗权限**；
   - 在系统无障碍设置中开启“**文献翻译助手 · 选中文本自动翻译**”；
   - Android 13+ 授予通知权限（保证悬浮服务常驻）；
   - 选择翻译引擎；用大模型请填写 API Base URL / Key / 模型名。
4. 打开任意英文 PDF 或网页，长按选中一段文字，译文面板会自动出现在旁边。

## 代码结构

```
app/src/main/
├── assets/cs_glossary.json                 # 内置 CS 术语表
├── java/com/example/academictranslator/
│   ├── accessibility/
│   │   └── TranslationAccessibilityService.kt  # 监听选区变化、取文本与坐标
│   ├── overlay/
│   │   └── OverlayService.kt                  # 悬浮窗、定位、拖动、翻译调度
│   ├── translate/
│   │   ├── Translator.kt                      # 引擎接口与结果模型
│   │   ├── LlmTranslator.kt                   # OpenAI 兼容大模型 + 学术 Prompt
│   │   ├── MlKitTranslator.kt                 # 设备端离线翻译
│   │   ├── MyMemoryTranslator.kt              # 免费在线兜底
│   │   └── TranslationManager.kt              # 引擎选择与失败回退
│   ├── data/
│   │   ├── SettingsStore.kt                   # 引擎/Key/模型/自定义术语
│   │   └── GlossaryStore.kt                   # 术语表加载与匹配
│   └── ui/
│       ├── MainActivity.kt                    # 权限、引擎设置、术语、测试
│       └── ProcessTextActivity.kt             # 系统选择菜单兜底入口
└── res/                                      # 布局（平板左右对照）、图标、无障碍配置
```

## 已知限制与可扩展方向

- 少数 PDF 阅读器使用自绘内核，不上报标准选区事件，无障碍监听会失效——请用文本选择菜单中的“翻译（文献助手）”入口；也可后续改用 MediaProjection + OCR 方案。
- ML Kit 在无 Google Play 服务的国产平板上不可用，App 会自动回退到在线引擎。
- 目前语言方向固定为 英译中；如需日译中/中译英，可在三个 Translator 实现中参数化语言对，并在设置页加选项。
- 可进一步扩展：术语表云端同步、译文历史记录（Room）、PDF 原页双语对照、OCR 选区翻译。
