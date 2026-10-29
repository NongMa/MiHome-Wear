package com.mijia.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.StepperDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.InlineSlider
import com.mijia.wear.model.ActionDef
import com.mijia.wear.model.PropDef
import com.mijia.wear.model.ValueItem
import com.mijia.wear.repo.SpecZh
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.ui.mjChipColors
import com.mijia.wear.vm.AppViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 摄像头拉流 START 动作：直接不展示（收进「实时视频」入口）。含后缀变体（stop-stream-6 等已单独处理）。 */
private val VIDEO_ENTRY_ACTIONS = setOf(
    "start-rtsp-stream",
    "start-hls-stream",
    "start-p2p-stream",
)
/**
 * 摄像头拉流 STOP 动作不展示在详情页（stop-stream-N 这类是内部命令，
 * UI 上重复且无意义；stopCameraStream 由 VideoScreen 离开页面时收尾）。
 */
private fun ActionDef.isStopStream(): Boolean = name.startsWith("stop-stream")

/**
 * 属性的界面标题：**只出中文**。
 * 优先级 = spec 的中文 comment > 短名中文表 > 短语直译 > 按控件类型的泛称，
 * 最后一道兜底保证界面上不出现英文。
 */
private fun PropDef.uiLabel(): String {
    SpecZh.cjkPart(desc)?.let { return it }
    SpecZh.propZh(name)?.let { return it }
    SpecZh.translatePhrase(desc)?.let { return it }
    return when {
        type == "bool" -> "开关"
        isEnum -> "模式"
        isNumeric -> "数值调节"
        else -> "状态"
    }
}

/** 动作按钮标题：同样只出中文，兜底为「执行操作」。 */
private fun ActionDef.uiLabel(): String {
    SpecZh.cjkPart(desc)?.let { return it }
    SpecZh.actionZh(name)?.let { return it }
    SpecZh.translatePhrase(desc)?.let { return it }
    return "执行操作"
}

/** 枚举当前值：中文优先，英文能翻则翻，未收录词汇回退显示原始英文名称。 */
private fun enumLabel(desc: String?): String {
    if (desc.isNullOrEmpty()) return "—"
    if (SpecZh.hasCjk(desc)) return desc
    return SpecZh.valueZh(desc) ?: SpecZh.translatePhrase(desc) ?: desc
}

private fun formatValue(value: Any?): String = when (value) {
    null -> "—"
    is Boolean -> if (value) "开" else "关"
    is Number -> {
        val d = value.toDouble()
        if (d == d.roundToInt().toDouble()) d.roundToInt().toString() else String.format("%.1f", d)
    }
    else -> {
        // 字符串值（IP、SSID 等）原样保留；能翻译的英文枚举翻成中文
        val s = value.toString()
        if (s.any { it.code > 127 }) s
        else SpecZh.valueZh(s) ?: SpecZh.translatePhrase(s) ?: s
    }
}

// ---------- 高级设置过滤 ----------

/**
 * 「深度配置」类属性的英文短名特征：定时/上电/网络/固件这些一次性设置，
 * 手表上主要是随手操作，默认不显示（底部可展开「全部设置」）。
 */
private val SETTING_NAME_KEYWORDS = listOf(
    "delay", "countdown", "power-on", "save-state", "kid", "double-click", "pomodoro",
    "work-minute", "break-minute", "work-countdown", "break-countdown",
    "wifi", "ssid", "password", "ip-address", "subnet", "gateway", "dns", "ppp",
    "channel", "bandwidth", "signal-strength", "encrypted-strength", "access-mode",
    "speed-control", "max-upload", "max-download", "online-time", "rssi",
    "version", "text-content", "silent-execution", "child-lock", "indicator",
    "anti-flicker", "wiring-method", "off-delay", "static-ip", "hidden", "ota",
    // 数值增量调节（brightness-delta / ct-delta / ct-adjust-alexa / adjust-*-cnt）
    "delta", "adjust",
    // 用户长按自定义功能及其关联子项（long-press-enable / -one / -two / -three / -ids-set/get）
    "long-press",
    // 长按场景引用的「我的模式1-4」预设定义（mode-one-bright / mode-two-ct 等）
    "mode-one", "mode-two", "mode-three", "mode-four",
)

/** 中文描述特征（spec 的 comment 已中文化的那部分），与英文短名互为补充。 */
private val SETTING_ZH_KEYWORDS = listOf(
    "延时", "倒计时", "上电", "断电", "儿童", "童锁", "双击", "隐藏", "信道", "带宽",
    "密码", "网关", "子网", "拨号", "固件", "版本", "网络名称", "接入方式", "限速",
    "在线时长", "防闪烁", "接线", "点动", "背光", "指示灯", "提示音", "静默执行",
    "IP地址", "DNS", "恢复默认", "上电状态", "流状态", "格式化", "弹出", "读取流配置信息",
    // 用户长按自定义功能及关联子项、数值增量调节
    "长按", "调节",
)

private val NUM_SUFFIX = Regex("-\\d+$")

/** 是否属于「手表上用不到的自定义设置」：短名特征或中文描述命中其一即算。 */
private fun isSetting(name: String, desc: String): Boolean {
    val key = name.replace(NUM_SUFFIX, "")
    if (SETTING_NAME_KEYWORDS.any { it in key }) return true
    val zh = SpecZh.cjkPart(desc) ?: desc
    return SETTING_ZH_KEYWORDS.any { it in zh }
}

private fun PropDef.isSetting(): Boolean = isSetting(name, desc)

private fun ActionDef.isSetting(): Boolean = isSetting(name, desc)

/**
 * 摄像头的主开关（siid=2, piid=1，通常是 on-2 / on）不隐藏。
 * 其余摄像头属性/动作（夜视、录像模式、追踪、水印、格式化、弹出等）
 * 一律视为「高级设置」，默认不显示。
 */
private fun PropDef.isCameraMainSwitch(): Boolean =
    siid == 2 && piid == 1 && name.startsWith("on")

/**
 * 设备详情：按 spec 自动生成控件——布尔→开关、数值→滑块、枚举→点按切换、
 * 只读→灰显、动作→按钮，与 Windows 版的控件工厂语义一致。
 *
 * 摄像头的拉流动作不做成普通按钮（需要带入参并解析返回地址），
 * 而是收进「实时视频」入口，由 VideoScreen 走完整流程。
 */
@Composable
fun DeviceScreen(vm: AppViewModel, did: String, nav: NavHostController) {
    val devices by vm.devices.collectAsState()
    val detail by vm.detail.collectAsState()
    val values by vm.propValues.collectAsState()

    val current by vm.currentDevice.collectAsState()

    LaunchedEffect(did, devices) { vm.openDevice(did) }

    // 默认只显示常用操作，深度配置收起来（底部可展开）
    var showAll by rememberSaveable { mutableStateOf(false) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            ScreenHeader(
                title = current?.name ?: detail?.name ?: "设备",
                subtitle = current?.let { "${it.homeName}·${it.roomName}" }
                    ?.takeIf { it.isNotBlank() && it != "·" },
            )
        }

        if (detail == null && current == null) {
            item { CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.35f)) }
        }

        if (detail == null && devices.isNotEmpty() && current == null) {
            item { Text("未找到该设备") }
        }

        detail?.let { d ->
            val isCam = current?.isCamera ?: false
            val allProps = d.props.sortedByDescending { if (it.writable) 1 else 0 }
            val hiddenProps = allProps.count { isCam && !it.isCameraMainSwitch() || it.isSetting() }
            val props = if (showAll) allProps
            else allProps.filterNot { (isCam && !it.isCameraMainSwitch()) || it.isSetting() }.distinctBy { it.name }
            val allActions = d.actions
                .filterNot { it.name in VIDEO_ENTRY_ACTIONS }
                .filterNot { it.isStopStream() }
            val hiddenActions = allActions.count { isCam || it.isSetting() }
            val actions = if (showAll) allActions
            else allActions.filterNot { isCam || it.isSetting() }.distinctBy { it.name }

            props.forEach { prop ->
                when {
                    prop.writable && prop.type == "bool" -> item(key = "p-${prop.name}") {
                        BoolRow(prop = prop, value = values[prop.name]) { vm.setProp(prop, it) }
                    }

                    prop.writable && prop.isNumeric -> item(key = "n-${prop.name}") {
                        NumericRow(prop = prop, value = values[prop.name]) { v ->
                            vm.setProp(prop, v)
                        }
                    }

                    prop.writable && prop.isEnum -> item(key = "e-${prop.name}") {
                        EnumRow(prop = prop, value = values[prop.name]) { v ->
                            vm.setProp(prop, v)
                        }
                    }

                    prop.readable -> item(key = "r-${prop.name}") {
                        Chip(
                            label = { Text(prop.uiLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = { Text(formatValue(values[prop.name])) },
                            onClick = { },
                            enabled = false,
                            colors = mjChipColors(background = Mj.SurfaceHigh),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            actions.forEach { action ->
                item(key = "a-${action.name}") {
                    Chip(
                        label = {
                            Text(
                                text = action.uiLabel(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        secondaryLabel = { Text("点按执行") },
                        onClick = { vm.runAction(action.name) },
                        colors = mjChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Chip(
                    label = { Text("刷新状态", color = Mj.AccentSoft) },
                    onClick = { vm.refreshProps() },
                    colors = mjChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 高级设置的展开/收起开关（没有隐藏项时不显示）
            val hidden = hiddenProps + hiddenActions
            if (hidden > 0) {
                item(key = "more") {
                    Chip(
                        label = {
                            Text(
                                if (showAll) "收起高级设置" else "高级设置",
                                color = Mj.TextDim,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                if (showAll) "只显示常用项" else "另有 $hidden 项已隐藏",
                                color = if (showAll) Mj.AccentSoft else Mj.TextMute,
                            )
                        },
                        onClick = { showAll = !showAll },
                        colors = mjChipColors(background = Mj.SurfaceHigh),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoolRow(prop: PropDef, value: Any?, onToggle: (Boolean) -> Unit) {
    val checked = value == true || value == 1 || value == "true"
    ToggleChip(
        checked = checked,
        onCheckedChange = onToggle,
        label = { Text(prop.uiLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        toggleControl = {
            Icon(
                imageVector = ToggleChipDefaults.switchIcon(checked = checked),
                contentDescription = if (checked) "已开启" else "已关闭",
            )
        },
        secondaryLabel = {
            Text(
                if (checked) "开" else "关",
                color = if (checked) Mj.AccentSoft else Mj.TextDim,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumericRow(prop: PropDef, value: Any?, onValue: (Any) -> Unit) {
    val range = prop.range ?: return
    if (range.size < 2) return
    val min = range[0]
    val max = range[1]
    if (max <= min) return
    val step = range.getOrNull(2)?.takeIf { it > 0 } ?: 1.0
    val current = (value as? Number)?.toDouble() ?: min
    val label = prop.uiLabel()

    val isInt = prop.type == "int" || prop.type == "uint"
    val lo = min.toFloat()
    val hi = max.toFloat()
    val cur = current.toFloat().coerceIn(lo, hi)
    val frac = ((cur - lo) / (hi - lo)).coerceIn(0f, 1f)

    // 防抖：拖动时只更新本地显示值，停止拖动 400ms 后才真正下发
    var pendingFrac by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pendingFrac) {
        val f = pendingFrac ?: return@LaunchedEffect
        delay(400)
        val v = lo + (hi - lo) * f
        onValue(if (isInt) v.roundToInt().coerceIn(min.roundToInt(), max.roundToInt()) else v.toDouble())
        pendingFrac = null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val shownFrac = pendingFrac ?: frac
            val shownVal = lo + (hi - lo) * shownFrac
            Text(
                formatValue(if (isInt) shownVal.roundToInt() else shownVal.toDouble()),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                color = Mj.AccentSoft,
            )
        }

        SwipeSlider(
            fraction = pendingFrac ?: frac,
            onFractionChange = { f -> pendingFrac = f },
        )
    }
}

/**
 * 横向拖动滑条：手指在条上左右滑动即可调值，无需点 +/-。
 *
 * 手势用 [detectHorizontalDragGestures]——只有确认横向拖动才接管，
 * 纵向滑动（列表滚动）直接放行给父级，避免上下滑屏时误触滑块。
 */
@Composable
private fun SwipeSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
) {
    val barHeight = 8.dp
    val thumbRadius = 9.dp
    val gestureHeight = 44.dp
    val density = LocalDensity.current
    // 拖动中的实时位置（拖动时直接更新，不等外部 fraction 回流）
    val dragPos = remember { mutableStateOf<Float?>(null) }
    // 拖动锚点：起始手指 x 与起始值
    val anchorX = remember { mutableStateOf(0f) }
    val anchorFrac = remember { mutableStateOf(0f) }
    val currentFrac by rememberUpdatedState(fraction)
    val shown = dragPos.value ?: fraction

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(gestureHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        val totalW = constraints.maxWidth.toFloat()
        if (totalW <= 0f) return@BoxWithConstraints
        val thumbR = with(density) { thumbRadius.toPx() }
        // 轨道左右各内缩一个拇指半径，保证拇指始终完整可见
        val trackW = (totalW - 2 * thumbR).coerceAtLeast(1f)
        val thumbCenterX = thumbR + shown * trackW

        // 轨道（底槽 + 渐变填充），左右内缩 thumbRadius
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = thumbRadius, end = thumbRadius)
                .fillMaxWidth()
                .height(barHeight)
        ) {
            // 底槽：极淡描边，深色背景下轮廓更清晰
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(Mj.SurfaceHigh)
                    .border(1.dp, Mj.Stroke.copy(alpha = 0.6f), RoundedCornerShape(barHeight / 2))
            )
            // 已填充部分：品牌蓝→浅蓝渐变
            Box(
                modifier = Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(Brush.horizontalGradient(listOf(Mj.Accent, Mj.AccentSoft)))
            )
        }

        // 拇指：白底 + 描边 + 柔和阴影
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((thumbCenterX - thumbR).roundToInt(), 0) }
                .size(thumbRadius * 2)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.dp, Mj.Accent, CircleShape)
        )

        // 手势层：覆盖整行（含拇指）便于抓取，只响应横向拖动
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(trackW) {
                    if (trackW <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            anchorX.value = offset.x
                            anchorFrac.value = currentFrac
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val f = (anchorFrac.value + (change.position.x - anchorX.value) / trackW)
                                .coerceIn(0f, 1f)
                            dragPos.value = f
                            onFractionChange(f)
                        },
                        onDragEnd = { dragPos.value = null },
                        onDragCancel = { dragPos.value = null },
                    )
                }
        )
    }
}

/**
 * 枚举项是否有明文（中文或可翻译成中文）。
 * spec 里有些枚举只给内部英文代号（如 `mode-1`），翻译不出来就不显示整行。
 */
private fun ValueItem.hasReadableLabel(): Boolean {
    val l = label
    if (l.isEmpty()) return false
    if (SpecZh.hasCjk(l)) return true
    return SpecZh.valueZh(l) != null || SpecZh.translatePhrase(l) != null
}

@Composable
private fun EnumRow(prop: PropDef, value: Any?, onValue: (Any) -> Unit) {
    val items = prop.valueList ?: return
    if (items.isEmpty()) return
    // 任一子项翻译不出明文 → 整行不显示
    if (items.any { !it.hasReadableLabel() }) return
    val current = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
    val idx = items.indexOfFirst { it.value == current }
    val currentLabel = if (idx >= 0) enumLabel(items[idx].label) else "—"

    Chip(
        label = { Text(prop.uiLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            Text(
                currentLabel,
                color = Mj.AccentSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = {
            val next = if (idx < 0) 0 else (idx + 1) % items.size
            onValue(items[next].value)
        },
        colors = mjChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
