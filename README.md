# Lumeter 光度计

Android 分区/多点测光应用。面向手动曝光与胶片摄影,取景器内点测/多点测光,输出 EV100 与光圈/快门/ISO 组合。

测光算法内核衍生自 [lightstop](https://github.com/the-waterwheel/lightstop)(Apache 2.0),界面用 Jetpack Compose 全新实现。

## 当前状态:M1(测光主链路)

- CameraX 预览 + YUV 分析流测光(tonemap 反演,不假设 sRGB)
- AE 稳态检测 + 多帧中值融合,读数不闪烁
- 实时 EV100、相机曝光参数、推荐曝光三角(ISO 可切换)
- 实时线性 luma 直方图 + 18% 灰参考线
- 点测光(取景器点按/拖动移动测光点)/ 中央重点

## 路线图

- M2:多点测光 + Zone System(十区标尺、加权合成、置区反推)
- M3:测光校准(参考 EV/参考相机/灰卡 lux)
- M4:倒易率/闪光指数/景深/宽容度工具
- M5:参数记录、发布打磨

## 构建

JDK 17+,Android SDK 36:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出:`app/build/outputs/apk/debug/app-debug.apk`

## 许可

Apache License 2.0,见 [LICENSE 备注](NOTICE)。测光内核衍生自 lightstop。
