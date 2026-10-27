package com.mijia.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.mijia.wear.repo.SpecZh
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.Route
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.ui.mjChipColors
import com.mijia.wear.vm.AppViewModel
import kotlin.math.roundToInt

/** 摄像头拉流动作：不做成普通按钮，统一收进「实时视频」入口。 */
private val STREAM_ACTIONS = setOf("start-rtsp-stream", "start-hls-stream", "start-p2p-stream", "stop-stream")

/** 能真正取回播放地址的动作（p2p 流无地址可播，故不作为视频入口）。 */
private val VIDEO_ENTRY_ACTIONS = setOf("start-rtsp-stream", "start-hls-stream")

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
)

/** 中文描述特征（spec 的 comment 已中文化的那部分），与英文短名互为补充。 */
private val SETTING_ZH_KEYWORDS = listOf(
    "延时", "倒计时", "上电", "断电", "儿童", "童锁", "双击", "隐藏", "信道", "带宽",
    "密码", "网关", "子网", "拨号", "固件", "版本", "网络名称", "接入方式", "限速",
    "在线时长", "防闪烁", "接线", "点动", "背光", "指示灯", "提示音", "静默执行",
    "IP地址", "DNS", "恢复默认", "上电状态",
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
            val allProps = d.props.sortedByDescending { if (it.writable) 1 else 0 }
            val hiddenProps = allProps.count { it.isSetting() }
            val props = if (showAll) allProps else allProps.filterNot { it.isSetting() }
            val allActions = d.actions.filterNot { it.name in STREAM_ACTIONS }
            val hiddenActions = allActions.count { it.isSetting() }
            val actions = if (showAll) allActions else allActions.filterNot { it.isSetting() }

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

            if (d.actions.any { it.name in VIDEO_ENTRY_ACTIONS }) {
                item(key = "video") {
                    Chip(
                        label = { Text("实时视频", color = Mj.AccentSoft) },
                        secondaryLabel = { Text("摄像头画面") },
                        onClick = { nav.navigate(Route.video(did)) },
                        colors = mjChipColors(background = Color(0xFF16243F)),
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                formatValue(current),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                color = Mj.AccentSoft,
            )
        }

        if (prop.type == "int" || prop.type == "uint") {
            val lo = min.roundToInt()
            val hi = max.roundToInt()
            val st = step.roundToInt().coerceAtLeast(1)
            val progress = lo..hi step st
            val v = current.roundToInt().coerceIn(lo, hi)
            InlineSlider(
                value = v,
                onValueChange = { onValue(it) },
                valueProgression = progress,
                decreaseIcon = { Icon(StepperDefaults.Decrease, contentDescription = "减少") },
                increaseIcon = { Icon(StepperDefaults.Increase, contentDescription = "增加") },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val lo = min.toFloat()
            val hi = max.toFloat()
            val steps = (((hi - lo) / step.toFloat()).roundToInt() - 1).coerceAtLeast(0)
            InlineSlider(
                value = current.toFloat().coerceIn(lo, hi),
                onValueChange = { onValue(it.toDouble()) },
                steps = steps,
                decreaseIcon = { Icon(StepperDefaults.Decrease, contentDescription = "减少") },
                increaseIcon = { Icon(StepperDefaults.Increase, contentDescription = "增加") },
                valueRange = lo..hi,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EnumRow(prop: PropDef, value: Any?, onValue: (Any) -> Unit) {
    val items = prop.valueList ?: return
    if (items.isEmpty()) return
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
