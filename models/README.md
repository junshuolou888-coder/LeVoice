# 工程内的语音模型

工程保存三套完整模型，打包时只选一套。此目录存放其中两套：

- `zipformer-zh-2023/`：multi-zh-hans 2023-12-12 INT8，约 72.8 MiB。
- `zipformer-zh-2025/`：中文 2025-06-30 INT8，约 159.6 MiB。

每个目录包含 `encoder.int8.onnx`、`decoder.onnx`、`joiner.int8.onnx`、`tokens.txt`；另外有官方文件元数据 `origin.json` 和构建校验清单 `checksums.json`。两套均采用 INT8 encoder/joiner 和 FP32 decoder。

在根目录 `gradle.properties` 修改 `speechModel=2023` 或 `speechModel=2025`，然后正常打包。也可单次覆盖：

```sh
./gradlew assembleDebug -PspeechModel=2023
./gradlew assembleDebug -PspeechModel=2025
```

恢复最初的 14M 轻量模型使用 `-PspeechModel=baseline`，文件保留并已提交在 `app/src/main/assets/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile/` 目录，约 24 MiB。三个选项都不需要另行下载模型。

APK 只包含所选模型，启动时从 APK assets 加载。电视上早期试用留下的独立模型文件不会覆盖打包选择。安装 APK 即可使用，无需额外复制模型。

Gradle 在构建时检查模型 SHA-256，缺文件或校验不符会报错。切换不需要 `clean`；两次构建输出路径相同，如需同时保存安装包，先复制或重命名上一份 APK。

## Git LFS

2023/2025 的 ONNX 权重使用 Git LFS 保存。新机器先安装 Git LFS，克隆后运行 `git lfs install` 和 `git lfs pull`，确保取回真实权重；仅下载 GitHub 源码 ZIP 可能只得到指针文件。14M 权重仍以普通 Git 文件保存。构建前会校验 2023/2025 文件 SHA-256。

## 固定来源

- [2023 官方模型](https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/tree/ac54a23c9d106dfbd178be831329fabe261bac58)：原始文件名含 `epoch-20-avg-1-chunk-16-left-128`，工程内统一为上面的短文件名，内容不变。
- [2025 官方模型](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/tree/ad658fa0201659a09ea3c176129a191c77ecae8f)。
- [官方使用说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)。

完整测试与限制见 [语音模型文档](../docs/语音模型.md)。

## 新旧电视运行库

新电视（Android 12、32 位 ARM）默认使用 `speechRuntime=modern`（sherpa-onnx 1.13.8，最低 API 24）。原 Android 6 电视需要：

```sh
./gradlew assembleDebug -PspeechModel=2023 -PspeechRuntime=android6
```

`android6` 构建沿用旧兼容库；该库在新电视的 32 位系统上会发生 SIGBUS，不要将它部署到这台新电视。模型选择与运行库选择互相独立。
