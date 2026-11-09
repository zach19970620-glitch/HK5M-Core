# HK5M-Core

从 HK5M-H210 工程提取的三个基础模块,互相独立,没有任何业务层。

| 模块 | 目录 | 说明 |
|---|---|---|
| 相机抓帧 | `camera/` | CameraX 1920×1080,业务帧锁 5fps;USB UVC 插拔检测;NV21 裁剪/缩放/转换 |
| 人脸推理 | `inference/` + `cpp/` | JNI 调 `librk_dl.so`(RKNN):RetinaFace 检测 + VGGFace 属性 + 表情;`InferenceBridge.onFrame(frame)` 喂帧,`faces` 取结果 |
| 语音听写 | `speech/` | 麦克风 16kHz PCM → 火山引擎流式 ASR;`SpeechRecognitionBridge.ui` 取字幕 |

`MainActivity` 是最小联调页:一组按钮开关相机+人脸,一组按钮开关听写。

## 使用前

- 模型 `*.rknn` 放 `app/src/main/assets/models/`(不入库)。
- 原生库见 `app/src/main/jniLibs/arm64-v8a/README.md`;`librk_dl_jni.so` 由 CMake 编译。OpenCV 相关 so 需自备。
- 语音:`local.properties` 填 `volc.apiKey`(或 `volc.appKey` + `volc.accessKey`),可选 `volc.resourceId`、`volc.wsUrl`。
- 仅 arm64-v8a;minSdk 24 / targetSdk 33。

## 构建

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
