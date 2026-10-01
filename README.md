# Lumeter 光度计 LUMEN MK·I

Android 反射式测光表,面向手动曝光与胶片摄影:取景器内点测/多点测光,输出 EV100 与 A/S/M 曝光参数。

测光算法内核衍生自 [lightstop](https://github.com/the-waterwheel/lightstop)(Apache-2.0),UI 按 LUMEN MK·I 设计稿用 Jetpack Compose 全新实现。

## 功能

- **四种测光模式**:MATRIX(全帧)/ CENTER(中央重点)/ SPOT(单点,点击场景任意处测光、拖动跟随)/ MULTI(最多 12 点,各点独立 EV + AVG/HIGH/LOW 聚合 + 光比 RANGE)
- **A/S/M 曝光模型**:光圈优先/快门优先/手动,曝光值吸附标准刻度;±3 EV 曝光偏差指针尺
- **测光链**:YUV 分析流 → tonemap 反演至线性亮度 → 18% 灰 EV100;AE 稳态门 + 多帧中值融合,读数不闪烁;每帧元数据与图像按传感器时间戳严格配对
- **ND 补偿**(0/3/6/10 档)、曝光补偿(1/2 或 1/3 档)、AE-L 锁定、会话级暂停/恢复
- **读数记录**(Room 持久化)、**测光校准**(EV 偏移 ±2 + 反射光 K 常数 10.6–14)
- **胶片库**(6 款常用胶卷,选择即设 ISO,含倒易率提示)
- **多语言**(简/繁/英/日,应用内切换)、三色主题(Amber/Signal/Phosphor)、沉浸式取景、拨轮刻度触觉

## 构建

JDK 17+,Android SDK 36:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出:`app/build/outputs/apk/debug/app-debug.apk`。单元测试:`.\gradlew.bat :app:testDebugUnitTest`。

## 架构

```
core/    纯算法(JVM 可测):EV 数学、tonemap 反演、YUV 统计、中值融合、A/S/M 求解、校准数学
camera/  CameraX 封装:会话/预览绑定、多点 ROI 引擎、CaptureResult 时间戳配对、坐标变换
data/    Room(读数历史)+ DataStore(偏好/校准)
ui/      Compose:三段式主屏(顶栏/取景器/可收起控制面板)+ 历史/胶片/设置/校准子页
```

## 路线图

- Zone System(十区标尺、置区反推)
- 工具面板:倒易率计算、闪光指数、景深、宽容度
- RAW 测光内核(MeterSource 已预留接口,lightstop 的 C++ Bayer 统计可移植)

## 许可

Apache License 2.0,见 [LICENSE](LICENSE)。测光算法内核衍生自 lightstop,见 [NOTICE](NOTICE)。字体:Barlow Condensed、DM Mono(OFL,随包分发)。
