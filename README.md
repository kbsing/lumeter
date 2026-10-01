# Lumeter 光度计 LUMEN MK·I

Android 反射式测光表,面向手动曝光与胶片摄影:取景器内点测/多点测光,输出 EV100 与 A/S/M 曝光参数,内置 Zone System、胶片工具与 RAW 测光内核。

测光算法内核衍生自 [lightstop](https://github.com/the-waterwheel/lightstop)(Apache-2.0),UI 按 LUMEN MK·I 设计稿用 Jetpack Compose 全新实现。

## 功能

- **四种测光模式**:MATRIX(全帧)/ CENTER(中央重点)/ SPOT(单点,点击场景任意处测光、拖动跟随)/ MULTI(最多 12 点,各点独立 EV + AVG/HIGH/LOW 聚合 + 光比 RANGE)
- **A/S/M 曝光模型**:光圈优先/快门优先/手动,曝光值吸附标准刻度;±3 EV 曝光偏差指针尺;等效曝光对一键换挡
- **Zone System**:0–X 十一区置区条,把测光区放到指定区反推曝光;MULTI 标点实时标注所在区
- **工具面板**:倒易率校正(28 款胶片模型)、闪光指数(GN/距离/光圈互算)、景深(超焦距/近远界/COC 预设)、胶片宽容度 vs 场景光比评估
- **RAW 测光**(实验):Bayer 域直读传感器,绕过 ISP tonemap;不支持时自动回退 YUV
- **测光链**:YUV 分析流 → tonemap 反演至线性亮度 → 18% 灰 EV100;AE 稳态门 + 多帧中值融合,读数不闪烁;每帧元数据与图像按传感器时间戳严格配对
- **ND 补偿**(0/3/6/10 档)、曝光补偿(1/2 或 1/3 档)、AE-L 锁定、会话级暂停/恢复
- **读数记录**(Room 持久化)、**测光校准**(EV 偏移 ±2 + 反射光 K 常数 10.6–14)
- **胶片库**(28 款,选择即设 ISO,含倒易率提示与宽容度参数)
- **多语言**(简/繁/英/日,应用内切换)、三色主题(Amber/Signal/Phosphor)、沉浸式取景、横屏四栏布局、拨轮刻度触觉

## 构建

JDK 17+,Android SDK 36:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出:`app/build/outputs/apk/debug/app-debug.apk`。单元测试:`.\gradlew.bat :app:testDebugUnitTest`。

Release 签名:keystore 放在仓库外,通过 `local.properties` 注入四项属性(`lumeter.release.store` / `storePassword` / `keyAlias` / `keyPassword`),缺失时 release 构建不签名。`.\gradlew.bat :app:assembleRelease`。

## 架构

```
core/    纯算法(JVM 可测):EV 数学、tonemap 反演、YUV 统计、Bayer 统计、中值融合、
         A/S/M 求解、Zone System、工具数学(倒易率/闪光/景深/宽容度)、校准数学
camera/  CameraX + Camera2:YUV 分析流、RAW_SENSOR 会话、CaptureResult 时间戳配对、
         坐标变换、共享稳态读数累加器
data/    Room(读数历史)+ DataStore(偏好/校准/胶片)
ui/      Compose:三段式主屏(顶栏/取景器/可收起控制面板)+ 横屏四栏 +
         历史/胶片/工具/设置/校准子页
```

## 许可

Apache License 2.0,见 [LICENSE](LICENSE)。测光算法内核衍生自 lightstop,见 [NOTICE](NOTICE)。字体:Barlow Condensed、DM Mono(OFL,随包分发)。
