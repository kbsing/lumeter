import io

# ---- A. SPOT mode back to a fixed center point ----

# 1. Tap layer: no SPOT branch (tap no longer moves anything).
p = 'app/src/main/java/com/lumeter/ui/meter/components/ViewfinderOverlay.kt'
s = io.open(p, encoding='utf-8').read()
old = '''                    detectTapGestures { offset ->
                        val x = (offset.x / size.width).coerceIn(0.02f, 0.98f)
                        val y = (offset.y / size.height).coerceIn(0.02f, 0.98f)
                        when (vm.meteringMode) {
                            MeteringMode.MULTI -> vm.addSpot(x, y, vm.ev100 ?: 0.0)
                            MeteringMode.SPOT -> vm.setSpotPos(x, y)
                            else -> {}
                        }
                    }'''
assert old in s
new = '''                    detectTapGestures { offset ->
                        if (vm.meteringMode == MeteringMode.MULTI) {
                            vm.addSpot(
                                (offset.x / size.width).coerceIn(0.02f, 0.98f),
                                (offset.y / size.height).coerceIn(0.02f, 0.98f),
                                vm.ev100 ?: 0.0,
                            )
                        }
                    }'''
s = s.replace(old, new)

# 2. ModeReticle: SPOT shows the fixed center ring again.
s = s.replace('''@Composable
private fun ModeReticle(mode: MeteringMode) {
    if (mode == MeteringMode.MULTI || mode == MeteringMode.SPOT) return''',
'''@Composable
private fun ModeReticle(mode: MeteringMode) {
    if (mode == MeteringMode.MULTI) return''')

# 3. SpotLayer renders only in MULTI; drop the single-spot else branch.
s = s.replace('''        if (vm.meteringMode == MeteringMode.MULTI || vm.meteringMode == MeteringMode.SPOT) {
            SpotLayer(vm, boxWidth, boxHeight)
        }''', '''        if (vm.meteringMode == MeteringMode.MULTI) {
            SpotLayer(vm, boxWidth, boxHeight)
        }''')
old_layer_head = '''@Composable
private fun SpotLayer(vm: AppViewModel, boxWidth: Float, boxHeight: Float) {
    val isMulti = vm.meteringMode == MeteringMode.MULTI
    // Push spot positions to the engine whenever geometry or any spot moves.
    LaunchedEffect(
        vm.spots,
        vm.spotPos,
        vm.meteringMode,'''
assert old_layer_head in s
s = s.replace(old_layer_head, '''@Composable
private fun SpotLayer(vm: AppViewModel, boxWidth: Float, boxHeight: Float) {
    // Push spot positions to the engine whenever geometry or any spot moves.
    LaunchedEffect(
        vm.spots,
        vm.meteringMode,''')
old_push = '''            val viewPoints = if (isMulti) {
                vm.spots.map { it.x to it.y }
            } else {
                listOf(vm.spotPos)
            }
            vm.pushEngineSpots(
                viewPoints.map { (x, y) -> transform.screenToFrame(x * boxWidth, y * boxHeight) },
            )'''
assert old_push in s
s = s.replace(old_push, '''            vm.pushEngineSpots(
                vm.spots.map { (x, y) -> transform.screenToFrame(x * boxWidth, y * boxHeight) },
            )''')
old_render = '''    Box(Modifier.fillMaxSize()) {
        val engineEvs = vm.spotDisplayEvs
        if (isMulti) {
            vm.spots.forEachIndexed { index, spot ->
                val ev = engineEvs.getOrNull(index)?.takeIf { it.isFinite() } ?: spot.ev100
                SpotHandle(
                    x = spot.x * boxWidth,
                    y = spot.y * boxHeight,
                    boxWidth = boxWidth,
                    boxHeight = boxHeight,
                    label = "${index + 1} ''' + chr(92) + '''u00b7 ${FormatUtils.evText(ev)}",
                    onDrag = { nx, ny -> vm.updateSpot(spot.id, nx / boxWidth, ny / boxHeight) },
                    onDelete = { vm.removeSpot(spot.id) },
                )
            }
        } else {
            val ev = engineEvs.firstOrNull()?.takeIf { it.isFinite() } ?: vm.ev100 ?: 0.0
            SpotHandle(
                x = vm.spotPos.first * boxWidth,
                y = vm.spotPos.second * boxHeight,
                boxWidth = boxWidth,
                boxHeight = boxHeight,
                label = FormatUtils.evText(ev),
                onDrag = { nx, ny ->
                    vm.setSpotPos(nx / boxWidth, ny / boxHeight)
                },
                onDelete = null,
            )
        }
    }
}'''
assert old_render in s, 'render branch anchor missing'
new_render = '''    Box(Modifier.fillMaxSize()) {
        val engineEvs = vm.spotDisplayEvs
        vm.spots.forEachIndexed { index, spot ->
            val ev = engineEvs.getOrNull(index)?.takeIf { it.isFinite() } ?: spot.ev100
            SpotHandle(
                x = spot.x * boxWidth,
                y = spot.y * boxHeight,
                boxWidth = boxWidth,
                boxHeight = boxHeight,
                label = "${index + 1} ''' + chr(92) + '''u00b7 ${FormatUtils.evText(ev)}",
                onDrag = { nx, ny -> vm.updateSpot(spot.id, nx / boxWidth, ny / boxHeight) },
                onDelete = { vm.removeSpot(spot.id) },
            )
        }
    }
}'''
s = s.replace(old_render, new_render)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('A. viewfinder: SPOT fixed at center')

# 4. Engine: SPOT meters the frame center again.
p = 'app/src/main/java/com/lumeter/camera/MeterAnalyzer.kt'
s = io.open(p, encoding='utf-8').read()
old = '''                MeteringMode.SPOT -> {
                    val point = spots.firstOrNull()
                    analyzer.regionStat(
                        point?.frameU ?: 0.5f,
                        point?.frameV ?: 0.5f,
                        EvMath.SPOT_ROI_FRACTION,
                    )
                }'''
assert old in s
s = s.replace(old, '                MeteringMode.SPOT -> analyzer.regionStat(0.5f, 0.5f, EvMath.SPOT_ROI_FRACTION)')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('engine spot fixed')

# 5. VM: remove the now-unused spotPos API.
p = 'app/src/main/java/com/lumeter/ui/AppViewModel.kt'
s = io.open(p, encoding='utf-8').read()
old = '''
    /** Single metering point for SPOT mode, normalized viewfinder coordinates. */
    var spotPos by mutableStateOf(0.5f to 0.5f)
        private set'''
assert old in s
s = s.replace(old, '')
old = '''
    fun setSpotPos(x: Float, y: Float) {
        spotPos = x.coerceIn(0.02f, 0.98f) to y.coerceIn(0.02f, 0.98f)
    }'''
assert old in s
s = s.replace(old, '')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('vm spotPos removed')
