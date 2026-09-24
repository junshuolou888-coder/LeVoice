# 本地语音助手（Android TV）

基于 sherpa-onnx 的 Android TV 本地中文语音控制 MVP。录音和识别都在设备端完成，不依赖网络服务。

## 已支持的语音指令

- 打开系统、网络、蓝牙、声音、显示或应用设置
- 查询指定城市或通过公网 IP 定位的当前天气，以及今天、明天、后天预报
- 返回主页
- 增大音量、减小音量、切换静音

## 使用

1. 工程已包含 `weather.local.json`（按项目所有者要求提交，构建时注入安装包）；重新配置可参考 `weather.example.json`。然后用 Android Studio 打开本目录，或运行 `./gradlew assembleDebug`。
2. 根据电视 ABI 安装 `app/build/outputs/apk/debug/` 中对应的 APK。
3. 连接带麦克风的遥控器，首次使用时允许录音权限。
4. 聚焦“开始说话”，按遥控器确认键，说出一条指令。

新电视的原厂语音键适配使用 `-PtvIntegration=stv` 生成 `com.stv.voice` 待签名包，须经匹配的厂商平台签名后才能覆盖原厂应用。普通版默认仍是 `com.localvoicetv`。构建、签名校验和真机验收见 [厂商语音适配](docs/厂商语音适配.md)。

## 组成

- 引擎：默认 `sherpa-onnx-1.13.8.aar`，最低 Android 7；Android 6 使用 `-PspeechRuntime=android6`。
- 兼容性：新 32 位 Android 12 电视使用 `modern` 运行库，修复旧库加载时的 SIGBUS；旧 Android 6 电视保留 `android6` 构建选项。
- 可选原轻量模型：`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile`（Apache-2.0）
- 默认内置中文流式 `Zipformer multi-zh-hans 2023-12-12 INT8`；也可打包 2025 INT8。两套文件均在 [models](models/README.md) 目录。

在 `gradle.properties` 修改 `speechModel=2023/2025/baseline`，或使用 `-PspeechModel=2023` 单次选择；每次只打包一套。打包、校验、切换及设备性能测试见 [语音模型](docs/语音模型.md)。识别结束采用端点检测，检测到一句话说完后会自动执行。

匹配评分、重复词容错、JSON 配置和通用跳转说明见 [语音指令引擎](docs/语音指令引擎.md)。

联网天气、IP 定位、凭据配置与调试说明见 [联网天气](docs/联网天气.md)。
