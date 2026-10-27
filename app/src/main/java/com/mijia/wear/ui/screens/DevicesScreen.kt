package com.mijia.wear.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.mijia.wear.model.DeviceInfo
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.Route
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.ui.mjChipColors
import com.mijia.wear.vm.AppViewModel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 设备页，照系统桌面 `com.xiaomi.miwear.home.launcher` 的做法实现：
 *
 * - **网格 = 二维自由拖动画布**，对应系统 `FixedRecyclerView` + `GridLayoutManager`
 *   的全方向拖动（上下左右都能拖，内容超出屏幕的部分靠拖动浏览）；
 *   边界处硬钳制，不会拖到空白区；
 * - 视图与系统一致分两档：`GRID`（图标网格）/ `LIST`（列表），
 *   对应系统的 `GridLayoutManager` / `ListLayoutManager`；网格长按弹完整设备名；
 * - 图标用官方米家图标（`/v2/productconfig/get_icon` → CDN PNG），拿不到时退回 emoji。
 */
private enum class ViewMode(val label: String) {
    GRID("网格"),
    LIST("列表"),
    ;

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** 网格列数：4 列更接近系统应用管理的密度，列宽收窄后边缘列不被圆屏裁掉。 */
private const val GRID_COLUMNS = 4

/** 单元宽（dp）：4 列总宽 272dp，留出横向拖动余量；径向缩放保证边缘不裁。 */
private const val CELL_W_DP = 68

/** 单元高（dp）：网格只有图标，高度给足即可。 */
private const val CELL_H_DP = 50

/** 图标直径（dp）：4 列布局下相应缩小，中心放大后回到醒目尺寸。 */
private const val ICON_DP = 32

/** 中心缩放：格子中心落在屏幕正中央时放大到 1.85x。 */
private const val CENTER_SCALE = 1.85f

/** 缩放随距离衰减的幅度：最远的格子缩到 0.90x。 */
private const val SCALE_RANGE = 0.95f

/** 透明度随距离衰减的幅度：最远的格子降到 0.72。 */
private const val FADE_RANGE = 0.28f

@Composable
fun DevicesScreen(vm: AppViewModel, nav: NavHostController) {
    val devices by vm.devices.collectAsState()
    val powers by vm.powers.collectAsState()
    val loading by vm.loading.collectAsState()
    // 官方图标（model → 米家 CDN PNG），拉到即刷新
    val icons by vm.icons.collectAsState()
    val hidden by vm.hiddenCount.collectAsState()
    var mode by rememberSaveable { mutableStateOf(ViewMode.GRID) }

    // 每次进入设备中心自动刷新最新设备列表与电源状态
    LaunchedEffect(Unit) {
        vm.loadDevices()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading && devices.isEmpty() -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.35f))
                Text(
                    "正在加载设备…",
                    fontSize = 12.sp,
                    color = Mj.TextDim,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            devices.isEmpty() && !loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (hidden > 0) "没有在线的可控设备" else "没有找到设备",
                    fontSize = 13.sp,
                    color = Mj.TextDim,
                )
                if (hidden > 0) {
                    Text(
                        text = "已隐藏 $hidden 台离线 / 路由器设备",
                        fontSize = 11.sp,
                        color = Mj.TextMute,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp).padding(horizontal = 24.dp),
                    )
                }
            }

            mode == ViewMode.LIST -> DeviceListView(
                vm = vm,
                devices = devices,
                powers = powers,
                icons = icons,
                loading = loading,
                onOpen = { nav.navigate(Route.device(it)) },
                // 与网格一致：长按提示完整设备名
                onLongPress = { vm.showToast(it.name) },
            )

            else -> DeviceCanvas(
                devices = devices,
                powers = powers,
                icons = icons,
                onOpen = { nav.navigate(Route.device(it)) },
                // 长按图标提示完整设备名（列表名常带省略号）
                onLongPress = { vm.showToast(it.name) },
            )
        }

        // 顶部视图切换：浮在网格上方，不随拖动移动。
        // 圆屏顶部很窄（y=10dp 处只有 96dp 宽），整行必须下移到
        // 屏宽足够的位置（y≥28dp），否则两端胶囊会被圆边裁掉。
        if (devices.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xF0000000), Color(0x00000000)),
                        ),
                    )
                    .padding(top = 30.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewMode.entries.forEach { m ->
                    MiniPill(text = m.label, selected = mode == m) { mode = m }
                }
            }
        }

        // 底部单独的刷新（网格模式；列表模式的刷新在列表末尾）
        if (devices.isNotEmpty() && mode != ViewMode.LIST) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x00000000), Color(0xF0000000)),
                        ),
                    )
                    .padding(top = 8.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                MiniPill(
                    text = if (loading) "刷新中…" else "刷新",
                    enabled = !loading,
                    onClick = { vm.loadDevices() },
                )
            }
        }
    }
}

// ---------- 二维拖动网格 ----------

/**
 * 全方向拖动的图标网格：内容按 4 列 × N 行排布，尺寸大于屏幕的部分
 * 靠单指任意方向拖动浏览，位置钳制在内容边界内（不会拖出空白）。
 * 点击按「拖动后的位置」命中，松手即点击、拖过即取消。
 */
@Composable
private fun DeviceCanvas(
    devices: List<DeviceInfo>,
    powers: Map<String, Boolean?>,
    icons: Map<String, Bitmap>,
    onOpen: (String) -> Unit,
    onLongPress: (DeviceInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        // 圆屏半径：缩放距离的归一化基准
        val screenR = minOf(screenW, screenH) / 2f
        val cellW = with(density) { CELL_W_DP.dp.toPx() }
        val cellH = with(density) { CELL_H_DP.dp.toPx() }

        val rows = ceil(devices.size / GRID_COLUMNS.toFloat()).toInt().coerceAtLeast(1)
        val contentW = cellW * GRID_COLUMNS
        val contentH = cellH * rows
        // 内容尺寸大于屏幕的部分才可拖；顶部让出 tab 遮罩条，底部让出刷新按钮
        val originX = (screenW - contentW) / 2f
        val originY = with(density) { 66.dp.toPx() }
        val bottomPad = with(density) { 46.dp.toPx() }
        val maxDX = ((contentW - screenW) / 2f).coerceAtLeast(0f)
        val maxDY = (originY + contentH + bottomPad - screenH).coerceAtLeast(0f)

        var offX by remember(devices.size) { mutableFloatStateOf(0f) }
        var offY by remember(devices.size) { mutableFloatStateOf(0f) }

        // 按「拖动后的位置」换算命中格子
        fun hit(p: Offset): DeviceInfo? {
            val col = floor((p.x - originX - offX) / cellW).toInt()
            val row = floor((p.y - originY - offY) / cellH).toInt()
            if (col !in 0 until GRID_COLUMNS || row !in 0 until rows) return null
            return devices.getOrNull(row * GRID_COLUMNS + col)
        }

        // 表冠滚轮：rotary 事件只发给「有焦点」的组件，进入网格先抢焦点
        val rotaryFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { rotaryFocus.requestFocus() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rotaryFocus)
                // 表冠 = 纵向翻行，和上下拖动共用同一套边界钳制（-maxDY..0）
                .onRotaryScrollEvent { e ->
                    if (maxDY <= 0f) {
                        false
                    } else {
                        offY = (offY - e.verticalScrollPixels).coerceIn(-maxDY, 0f)
                        true
                    }
                }
                .focusable()
                // 点击进入设备页；长按提示完整设备名
                .pointerInput(devices.size) {
                    detectTapGestures(
                        onTap = { p -> hit(p)?.let { onOpen(it.did) } },
                        onLongPress = { p -> hit(p)?.let { onLongPress(it) } },
                    )
                }
                // 任意方向拖动：纵向从顶部起向下拖到末行，横向对称居中，边界钳制
                .pointerInput(maxDX, maxDY) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offX = (offX + drag.x).coerceIn(-maxDX, maxDX)
                        offY = (offY + drag.y).coerceIn(-maxDY, 0f)
                    }
                },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            devices.forEachIndexed { i, d ->
                val col = i % GRID_COLUMNS
                val row = i / GRID_COLUMNS
                DeviceCell(
                    device = d,
                    powered = powers[d.did] == true,
                    icon = icons[d.model],
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (originX + col * cellW + offX).roundToInt(),
                                y = (originY + row * cellH + offY).roundToInt(),
                            )
                        }
                        // 官方应用管理的「中间放大、四周变小」：按格子中心到屏幕
                        // 中心的距离缩放并淡出。取径向距离而非纯纵向，四周的格子
                        // 缩得更小，最外列放大后也不会被圆屏裁掉。
                        .graphicsLayer {
                            val cx = originX + col * cellW + cellW / 2f + offX
                            val cy = originY + row * cellH + cellH / 2f + offY
                            val dist = (hypot(
                                cx - screenW / 2f,
                                cy - screenH / 2f,
                            ) / screenR).coerceIn(0f, 1f)
                            val scale = CENTER_SCALE - SCALE_RANGE * dist
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - FADE_RANGE * dist
                        },
                    widthDp = with(density) { cellW.toDp() },
                    heightDp = with(density) { cellH.toDp() },
                )
            }
            }
        }
    }
}

@Composable
private fun DeviceCell(
    device: DeviceInfo,
    powered: Boolean,
    icon: Bitmap?,
    modifier: Modifier,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = modifier.width(widthDp).height(heightDp).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ICON_DP.dp)
                .alpha(if (device.online) 1f else 0.4f)
                .background(
                    // 官方图标自带透明底，垫一层深色圆；没有图标时退回彩色渐变 + emoji
                    brush = if (icon != null) {
                        Brush.linearGradient(listOf(Mj.SurfaceHigh, Mj.Surface))
                    } else {
                        Brush.linearGradient(paletteFor(device))
                    },
                    shape = CircleShape,
                )
                .border(1.dp, Mj.Stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = device.name,
                    contentScale = ContentScale.Fit,
                    // 官方 PNG 内含留白，再收一圈避免贴边
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Text(text = deviceGlyph(device), fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            if (powered) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .background(Mj.Success, CircleShape),
                )
            }
        }
    }
}

// ---------- 图标外观 ----------

private val PALETTES = listOf(
    listOf(Color(0xFF64B5F6), Color(0xFF0D47A1)),
    listOf(Color(0xFFFF8A65), Color(0xFFBF360C)),
    listOf(Color(0xFF81C784), Color(0xFF1B5E20)),
    listOf(Color(0xFFBA68C8), Color(0xFF4A148C)),
    listOf(Color(0xFFFFD54F), Color(0xFFF57F17)),
    listOf(Color(0xFF4DD0E1), Color(0xFF006064)),
    listOf(Color(0xFFF06292), Color(0xFF880E4F)),
    listOf(Color(0xFFAED581), Color(0xFF33691E)),
)

/** 按 model 取配色，同一设备每次进入外观一致。 */
private fun paletteFor(device: DeviceInfo): List<Color> {
    val h = (device.model + device.did).hashCode()
    val idx = if (h == Int.MIN_VALUE) 0 else kotlin.math.abs(h) % PALETTES.size
    return PALETTES[idx]
}

private fun deviceGlyph(device: DeviceInfo): String {
    val m = device.model.lowercase()
    return when {
        "camera" in m -> "📷"
        "wifispeaker" in m -> "🔊"
        "lamp" in m || "light" in m -> "💡"
        "plug" in m -> "🔌"
        "fan" in m -> "💨"
        "vacuum" in m -> "🧽"
        "airp" in m -> "🍃"
        "aircondition" in m -> "❄"
        "router" in m -> "📡"
        "oven" in m -> "🍞"
        "curtain" in m -> "🪟"
        "lock" in m -> "🔒"
        "soocare" in m || "massage" in m -> "💆"
        "thermometer" in m || "temp" in m || "humidity" in m -> "🌡"
        else -> device.name.trim().firstOrNull()?.toString() ?: "?"
    }
}

// ---------- 小胶囊 ----------

@Composable
private fun MiniPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .background(
                color = if (selected) Mj.Accent else Mj.Surface,
                shape = RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = when {
                !enabled -> Mj.TextDisabled
                selected -> Color.White
                else -> Mj.Text
            },
        )
    }
}

// ---------- 列表视图 ----------

/**
 * 圆屏友好的列表：每行是「比整屏窄一档」的卡片，图标 + 名称一行、
 * 「房间 · 状态」一行；缩到屏幕上、下两端被 ScalingLazyColumn 压小时，
 * 短行不会被圆边切掉，状态用颜色区分（开=绿 / 关=灰 / 离线=暗灰）。
 */
@Composable
private fun DeviceListView(
    vm: AppViewModel,
    devices: List<DeviceInfo>,
    powers: Map<String, Boolean?>,
    icons: Map<String, Bitmap>,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onLongPress: (DeviceInfo) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 列表两端会被 Wear 的边缘缩放压小；顶部再让出「网格/列表」胶囊条
        contentPadding = PaddingValues(top = 78.dp, bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(devices.size) { i ->
            val d = devices[i]
            DeviceRow(
                device = d,
                power = powers[d.did],
                icon = icons[d.model],
                onOpen = { onOpen(d.did) },
                onLongPress = { onLongPress(d) },
            )
        }

        item {
            Chip(
                label = { Text("刷新", color = Mj.AccentSoft) },
                onClick = { vm.loadDevices() },
                enabled = !loading,
                colors = mjChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f),
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    power: Boolean?,
    icon: Bitmap?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val status = when {
        !device.online -> "离线"
        power == true -> "开"
        power == false -> "关"
        else -> "在线"
    }
    val statusColor = when {
        !device.online -> Mj.TextMute
        power == true -> Mj.Success
        power == false -> Mj.TextDim
        else -> Mj.TextDim
    }
    // 圆屏上放不下「家·房间」两段，优先房间，没有房间再用家名
    val place = device.roomName.ifBlank { device.homeName }

    Row(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .clip(RoundedCornerShape(22.dp))
            .background(Mj.Surface)
            // 点按进详情、长按提示完整名称，与网格手势保持一致
            .pointerInput(device.did) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .alpha(if (device.online) 1f else 0.4f)
                .background(
                    // 官方图标垫灰阶圆底；没有图标时退回彩色渐变 + 字形
                    brush = if (icon != null) {
                        Brush.linearGradient(listOf(Mj.SurfaceHigh, Mj.Surface))
                    } else {
                        Brush.linearGradient(paletteFor(device))
                    },
                    shape = CircleShape,
                )
                .border(1.dp, Mj.Stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = device.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Text(device.name.trim().firstOrNull()?.toString() ?: "?", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = Mj.Text,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = place,
                    color = Mj.TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ", color = Mj.TextMute, fontSize = 11.sp)
                Text(status, color = statusColor, fontSize = 11.sp)
            }
        }
    }
}
