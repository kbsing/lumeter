# Lumeter 开发路线详设

> 配合 README 的简版路线图。粒度:每个功能给出目标 / 改动面 / 验收 / 工作量。
> 工作量单位 = 一次专注开发会话(含 JVM 单测与真机验证)。版本节奏:每版走
> "单测锁数学 → 真机验收 → 签名 release 发 GitHub",与 v0.6 相同。

## 原则

1. 读数可信 > 功能数量:每个影响 EV 的改动必须有纯内核单测 + 真机交叉验证。
2. 先收尾现成资产(YUV histogram、倒易率模型、校准数学都已在库),再开新坑。
3. 相机层改动(会话/流)永远保持"失败即回退 YUV"的底线。
4. 不做的事:角度测光、曝光预览渲染、iOS。投入产出不匹配,见文末。

---

## v0.7 测光可信度

### F1 取景器直方图叠加 · 1 会话

- **目标**:取景器顶部叠加亮度直方图,直观看到高光/暗部分布;替代纯文字的 too bright/too dark。
- **改动面**:
  - `core/meter`:新增 `LumaHistogram`(64 桶、EV 域分箱,`YuvFrameAnalyzer.histogram()` 是线性域且无人调用,重写为 EV 域 + 输出 p01/p99);单测:平场输入单峰、裁剪输入贴边。
  - `camera/MeterAnalyzer`:每 N 帧(约 2Hz)算一次直方图,放进 `MeterEngineState.histogram`;RAW 路径:从 RawBayerStats 采样亮度直接分箱。
  - `data`:`UserPreferences.histogramEnabled` + DataStore key。
  - `ui`:设置页开关(描述文案 ×4 语言);`ViewfinderOverlay` 顶部 Canvas 绘制(半透明、log 横轴、accent 色),横竖屏都放。
- **验收**:开关即时生效;对亮墙/暗房直方图明显偏一侧;不拖慢取景帧率。
- **风险**:低。纯 UI + 既有采样。

### F2 RAW 独立校准 · 1 会话 + 真机

- **目标**:YUV 与 RAW 两条路径的系统偏差分开校准,互不污染。
- **前置**:需要真机 RAW 对比数据定形态(vivo 上 RAW−YUV 若为恒定偏移 → 一点校准;若随亮度漂移 → v0.9 升级两点拟合,本期先做恒定偏移版)。
- **改动面**:
  - `data/PreferencesRepository`:校准拆为 `calibration_offset_yuv` / `calibration_offset_raw`,迁移时旧值同时写入两路(保守默认);K 常数保持共用(物理常数,与路径无关)。
  - `ui/AppViewModel`:`calOffset` 变为按 `rawActive` 取值;`sceneEv` 链路不变。
  - `ui/CalibrationPage`:顶部 YUV/RAW 来源切换(仅 rawAvailable 时显示 RAW 项);"按参考校准"向导:对灰卡/已知表 → 输入参考 EV → 复用 `CalibrationMath.updatedUserCorrection` 写入当前来源。
- **验收**:切来源各自校准互不影响;向导一步完成;杀进程重启后保留。
- **风险**:中。DataStore 迁移需兼容旧安装。

### F3 自动场景范围 · 1 会话

- **目标**:MATRIX 模式免手动打点,自动给出场景高光/暗部 EV,喂给宽容度卡。
- **改动面**:
  - `core/meter/YuvFrameAnalyzer`:新增 `frameRange()`——全帧采样(复用现逐像素通道)返回 p05/p95 线性亮度;`RawBayerStats`:2×2 单元逐格算亮度后同样取分位(注意按格合并四通道)。
  - `camera` 两路:范围 EV 经独立 ring 中值平滑后放进 `MeterEngineState.sceneLowEv/HighEv`。
  - `ui/ToolsPage`:LatitudeCard 在 MULTI 标点 <2 时自动退到场景范围;取景器可选一行 "R 8.2–14.6" 小字。
  - 单测:合成帧分位数正确性(两端加极值)。
- **验收**:对高反差场景,自动范围与手动 MULTI HIGH/LOW 差 ≤1/3 EV。
- **风险**:低。分位数对冷像素敏感 → 用中值域分位而非 min/max。

### F4 倒易率联动求解 · 1 会话

- **目标**:A 模式解出 ≥1s 快门时,按当前胶片自动加倒易率补偿,并在归因里单独列出。
- **改动面**:
  - `data`:`UserPreferences.currentFilmStock: String?`(轻量胶片选择,是 v0.8 胶卷管理的先导);`applyFilmStock` 改为同时记录型号。
  - `core/exposure/ExposureState` 加 `reciprocity: Reciprocity?`:
    - A 模式:理想快门 → `correctedSeconds` → 吸附,残差含补偿;
    - S 模式:对设定快门求补偿档数,并入 eEff(等效开大光圈);
    - M 模式:对设定快门只报告补偿量(不改针)。
    - `Causes` 加 `reciprocityStops`;单测覆盖三模式 + 阈值边界(1s 整不补)。
  - `ui`:建议行追加 "含倒易率 +1 EV";补偿生效时快门环旁小标记。
- **验收**:选 Tri-X 400、测出 10s → 显示 20s 且归因 +1.0;胶片为空不补偿。
- **风险**:低。数学已在 ToolsMath 有测试。

### F5 SPOT 可拖动 · 0.5 会话

- **改动面**:`MeterAnalyzer` 加 `spotPosition`(volatile,默认中心);`ViewfinderOverlay` SPOT 模式:点按移动 + 沿用 SpotHandle 拖拽,`PreviewGeometry` 复用 MULTI 的映射;设置页加"SPOT 跟随点击"开关(保守默认开)。
- **验收**:拖到暗角读数变化;HOLD 冻结行为不变;横屏坐标正确。

**v0.7 整体验收清单**:直方图开关/两向显示;RAW 校准后与已知表 ±0.1 EV;自动范围 vs 手动 MULTI ≤1/3 EV;Tri-X 10s→20s 联动;SPOT 拖动全链路;回归 v0.6.1 清单不破坏。

---

## v0.8 胶片工作流

### F1 胶卷/胶卷仓 · 2 会话

- **目标**:从"测光工具"变"拍摄伴侣":建档胶卷、余张跟踪、读数归卷。
- **改动面**:
  - `data/ReadingDatabase` **Room v2 迁移**:新表 `FilmRoll(id, stockName, iso, frameCount, framesShot, loadedAt, notes, archivedAt)`;`Reading` 加 `rollId: Long?`(旧数据为 NULL,不强制归卷)。手写 `Migration(1,2)`,迁移后用 `getReadingCount` 冒烟。
  - `ui/AppViewModel`:`currentRoll` 状态;`logReading` 进事务:插读数 + `framesShot+1`;余 0 提示。
  - `ui/FilmPage` 改双 Tab(胶片库 | 我的胶卷):胶卷卡(型号、日期、24/36 进度条、归档);新建胶卷对话框(从库选型号 + 张数预设 12/24/36)。
  - 文案 ×4。
- **验收**:建卷→拍 3 张→进度 3/36;归档后不再显示在"当前";升级安装不崩且历史完整。
- **风险**:中。Room 迁移错一字段就是升级即崩,必须用旧 v1 数据库真机验证升级路径。

### F2 记录增强与导出 · 1 会话

- **改动面**:`Reading` 加 `filmStock/zone/source` 列(并入 F1 的 v2 迁移一次做完,避免二次迁移);历史页按胶卷筛选 chip;SAF `CreateDocument` 导出 CSV/JSON(读数+胶卷);长按单条复制文本。
- **验收**:导出文件可被表格软件打开,字段齐全;筛选正确。

### F3 闪光读数 · 0.5 会话

- **改动面**:ToolsPage 闪光卡加"用当前读数"按钮:读 `sceneEv`+`userIso` → 对照 GN 算环境光可达光圈 vs 闪光需要光圈,给出"闪光为主/补光/过近"结论(复用 FlashMath,新增一个纯函数 `mixVerdict`,单测)。
- **验收**:GN32、环境 f/5.6、3m → 提示闪光需 f/10.7、差约 1.5 档。

**v0.8 验收**:建卷→测光→log→导出全链路;旧数据无损升级;四语言完整。

---

## v0.9 进阶测光(条件触发)

### F1 场景色温估算 · 1 会话

- **改动面**:移植 lightstop `RawColorTemperatureEstimator`(Apache-2.0,已验证的 R/B→CCT 经验曲线)到 `core/meter`(纯化 + 单测);`RawMeterSource` 的通道中值已在手,零额外采样成本,发布 CCT/tint;读数面板 RAW 徽标旁显示 "5480K",超出色温带时提示钨光/日光卷适配。
- **验收**:日光灯/白炽灯/阴天三场景 CCT 落在公认区间(±300K 量级)。

### F2 渐晕位置补偿 · 1–2 会话(条件触发)

- **前置**:v0.7 真机测试时顺带量化:均匀亮墙四角 vs 中心 EV 差。≥1/3 EV 才值得做。
- **方案**:按镜头存 3×3 增益网格(DataStore,校准页"渐晕标定"入口),SPOT/MULTI 读数按 ROI 位置乘增益。

---

## 1.0 发布与打磨

- **F-Droid 上架**(1 会话准备 + 平台审核周期):`metadata/` 目录(四语言 summary/description、截图)、确认从源码可构建(签名与 local.properties 解耦——已满足,签名缺失时构建不签)、changelog 用 fastlane 格式。全程 Apache-2.0 + OFL 字体,无跟踪,符合收录线。
- **性能**(1 会话):`RawBayerStats` 每次调用分配 4×16384 数组,MULTI 12 点时 GC 明显——改缓冲池复用;用 Profiler 确认。
- **工程与文案**(1 会话):versionName 字符串改 gradle `resValue` 自动生成(结束每版手改 4 个 strings.xml);无障碍 contentDescription 走查;四语言全量校对;`docs/QA-CHECKLIST.md` 固化真机回归清单。

---

## 工程债清单(随版本顺带)

| 项 | 处理时机 |
|---|---|
| AppViewModel 700+ 行,测光/工具/胶片状态混居 | v0.8 开工前拆 `FilmViewModel`,其余暂缓(拆错状态比不拆更糟) |
| CameraManager YUV/RAW 切换无自动化覆盖 | 无法单测,靠 QA-CHECKLIST 的后台/旋转/权限用例人工兜 |
| ExposureSolver 与 core/meter 各自维护光圈/快门刻度 | v0.7 F4 动 solver 时统一到一份常量 |

## 已明确不做

- 角度测光(硬件语义不清,UI 重写成本高)
- 取景器曝光预览渲染(需要 ISP 管线级工程)
- 网络同步/账号体系(单机工具,隐私即卖点)
