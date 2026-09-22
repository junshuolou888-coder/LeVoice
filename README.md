# 本地语音助手（Android TV）

基于 sherpa-onnx 的 Android TV 本地中文语音控制 MVP。录音和识别都在设备端完成，不依赖网络服务。

## 已支持的语音指令

- 打开系统、网络、蓝牙、声音、显示或应用设置
- 查询指定城市或通过公网 IP 定位的当前天气，以及今天、明天、后天预报
- 返回主页
- 增大音量、减小音量、切换静音

## 使用

1. 用 Android Studio 打开本目录，或运行 `./gradlew assembleDebug`。
2. 根据电视 ABI 安装 `app/build/outputs/apk/debug/` 中对应的 APK。
3. 连接带麦克风的遥控器，首次使用时允许录音权限。
4. 聚焦“开始说话”，按遥控器确认键，说出一条指令。

## 组成

- 引擎：`app/libs/sherpa-onnx-1.12.39-android6.aar`
- 兼容性：项目原始 1.13.8 AAR 的原生库要求 Android 7；Android 6 电视构建使用 API 兼容的 sherpa-onnx 1.12.39
- 模型：`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile`
- 模型许可：Apache-2.0

当前轻量模型只支持中文，优先保证电视端响应速度。识别结束采用端点检测，检测到一句话说完后会自动执行。

匹配评分、重复词容错、JSON 配置和通用跳转说明见 [语音指令引擎](docs/语音指令引擎.md)。

联网天气、IP 定位、凭据配置与调试说明见 [联网天气](docs/联网天气.md)。
