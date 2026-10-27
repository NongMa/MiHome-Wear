package com.mijia.wear.repo

/**
 * miot-spec 英文 → 中文兜底表，移植自上游 MiHome-Windows 的 `spec_zh.py`。
 *
 * spec 的 `comment` 字段是中文来源，但部分品类（音箱、路由器、温湿度计等）
 * 官方数据缺该字段，界面会退化成纯英文。这里按属性/动作短名兜底补全：
 * 同名标准属性在所有设备上共用翻译，与型号无关。
 *
 * 匹配两级：先精确匹配（`on-2` 是「按键」、`on` 是「开关」，语义不同必须
 * 分开），未命中再去掉尾部数字后缀重试（`on-10` → `on`）。
 */
object SpecZh {

    /** 属性短名 → 中文；键含数字后缀的是去重名，语义可能与基名不同。 */
    private val PROPERTY_ZH = mapOf(
        "volume" to "音量",
        "mute-2" to "静音",
        "mute-4" to "静音",
        "playing-state" to "播放状态",
        "text-content" to "文本内容",
        "silent-execution" to "指令静默执行",
        "sleep-mode" to "睡眠模式",
        "audio-id" to "音频ID",
        "download-speed" to "下载速度",
        "upload-speed" to "上传速度",
        "connected-device-number" to "连接设备数",
        "network-connection-type" to "上网方式",
        "ip-address" to "IP地址",
        "status" to "工作状态",
        "fault" to "故障",
        "ppp-username" to "拨号上网用户名",
        "ppp-encrypted-password" to "拨号上网加密密码",
        "static-ip-address" to "静态IP地址",
        "subnet-mask" to "子网掩码",
        "gateway-ip-address" to "网关IP地址",
        "dns-ip-address" to "DNS地址",
        "dns-auto-mode" to "DNS自动配置",
        "access-mode" to "接入方式",
        "rssi" to "信号强度",
        "wifi-band-combine" to "WiFi双频合一",
        "online-time" to "在线时长",
        "wifi-ssid-3" to "WiFi网络名称",
        "wifi-encrypted-password-3" to "WiFi网络密码",
        "on-3" to "开关",
        "wifi-ssid-hidden" to "隐藏WiFi",
        "encrypted-strength-3" to "加密强度",
        "signal-strength-3" to "发射信号强度",
        "wifi-channel-3" to "WiFi信道",
        "wifi-bandwidth-3" to "WiFi信道带宽",
        "on-4" to "开关",
        "wifi-ssid-4" to "WiFi网络名称",
        "wifi-encrypted-password-4" to "WiFi网络密码",
        "encrypted-strength-4" to "加密强度",
        "signal-strength-4" to "发射信号强度",
        "wifi-channel-4" to "WiFi信道",
        "wifi-bandwidth-4" to "WiFi信道带宽",
        "speed-control" to "限速控制",
        "max-upload-speed" to "最大上传速度",
        "max-download-speed" to "最大下载速度",
        "on-5" to "开关",
        "mode" to "模式",
        "brightness" to "亮度",
        "color" to "颜色",
        "color-temperature" to "色温",
        "flow" to "流光功能",
        "saturability" to "饱和度",
        "on" to "开关",
        "default-power-on-state" to "默认上电状态",
        "save-state" to "恢复断电前灯光",
        "delay-time" to "延时时间",
        "delay-off-countdown" to "延时关灯倒计时",
        "delay-off-enable" to "延时关灯开关",
        "kid-mode" to "儿童模式开关",
        "double-click-enable" to "双击功能开关",
        "double-click" to "双击触发的功能",
        "scene-mode" to "灯上的模式",
        "pomodoro-enable" to "专注模式开关",
        "work-minutes" to "专注工作时长",
        "break-minutes" to "专注休息时长",
        "work-countdown-sec" to "工作结束倒计时",
        "break-countdown-sec" to "休息倒计时",
        "mode-one-bright" to "模式1亮度",
        "mode-one-ct" to "模式1色温",
        "mode-two-bright" to "模式2亮度",
        "mode-two-ct" to "模式2色温",
        "mode-three-bright" to "模式3亮度",
        "mode-three-ct" to "模式3色温",
        "mode-four-bright" to "模式4亮度",
        "mode-four-ct" to "模式4色温",
        "double-click-mode" to "双击启动的模式",
        "eco" to "背光开关",
        "off-delay" to "点动功能",
        "toggle" to "切换",
        "temperature" to "温度",
        "relative-humidity" to "湿度",
        "battery-level" to "电池电量",
        "on-2" to "按键",
        "on-10" to "开关",
        "version" to "设备版本号",
        "mode-2" to "模式",
        "anti-flicker" to "防闪烁模式",
        "wiring-method" to "接线方式",
        "mode-3" to "模式",
        "mode-4" to "模式",
        "mode-5" to "模式",
        "power-consumption" to "功耗参数",
        "electric-power" to "电功率",
        "power-consumption-accumulation-way" to "耗电量累加形式",
        "on-7" to "开关",
        // 手表端常见品类补充（空调/风扇/净化器/摄像头/扫地机等）
        "target-temperature" to "目标温度",
        "target-humidity" to "目标湿度",
        "fan-level" to "风档",
        "horizontal-swing" to "水平摆动",
        "vertical-swing" to "垂直摆动",
        "air-quality" to "空气质量",
        "pm25" to "PM2.5",
        "temperature-humidity-sensor" to "温湿度",
        "illumensity" to "照度",
        "washing-machine" to "洗涤程序",
        "operation-mode" to "运行模式",
        "mode-appliance" to "运行模式",
        "start-time" to "开始时间",
        "end-time" to "结束时间",
        "remaining-time" to "剩余时间",
        "door-contact" to "门开关状态",
        "target-position" to "目标开度",
        "motor-controller" to "电机控制",
        "coverage-area" to "清扫面积",
        "clean-area" to "清扫面积",
        "clean-time" to "清扫时长",
        "suction-grade" to "吸力档位",
        "water-tank-status" to "水箱状态",
        "brush-life" to "滚刷寿命",
        "filter-life" to "滤芯寿命",
        "water-level" to "水位",
        "presence" to "有人状态",
        "motion-state" to "移动状态",
        "illow" to "开合角度",
        "curtain-status" to "窗帘状态",
        "target-position-2" to "目标开度",
    )

    /** 动作短名 → 中文。 */
    private val ACTION_ZH = mapOf(
        "play" to "播放",
        "pause" to "暂停",
        "next" to "下一首",
        "previous" to "上一首",
        "play-text" to "朗读文本",
        "play-music" to "播放音乐",
        "play-radio" to "播放电台",
        "execute-text-directive" to "执行文本指令",
        "stop-alarm" to "停止闹钟",
        "wake-up" to "唤醒",
        "toggle" to "切换",
        "turn-on" to "开启",
        "turn-off" to "关闭",
        "reboot" to "重启",
        "set-default" to "恢复默认",
        "apply-scene" to "应用场景",
        "bright-circle" to "亮度循环",
        "bright-increase" to "调高亮度",
        "bright-decrease" to "调低亮度",
        "bright-on-circle" to "亮度循环开关",
        "ct-circle" to "色温循环",
        "ct-increase" to "调高色温",
        "ct-decrease" to "调低色温",
        "ct-on-circle" to "色温循环开关",
        "delay-off" to "延时关",
        "fan-speed-up" to "风速调高",
        "fan-speed-down" to "风速调低",
        "temperature-up" to "温度调高",
        "temperature-down" to "温度调低",
        "start" to "启动",
        "stop" to "停止",
        "pause-action" to "暂停",
        "locate" to "定位",
        "start-sweep" to "开始清扫",
        "stop-sweep" to "停止清扫",
        "start-sweep-mop" to "开始拖地",
        "pause-sweep" to "暂停清扫",
        "start-wash" to "开始洗拖布",
        "execute-directive" to "执行指令",
        "manual-seek" to "手动搜台",
        "logout-cloud" to "退出云台",
    )

    /** 枚举值英文 → 中文（value-list 缺 comment 时兜底）。 */
    private val VALUE_ZH = mapOf(
        "auto" to "自动",
        "manual" to "手动",
        "on" to "开",
        "off" to "关",
        "open" to "开",
        "close" to "关",
        "low" to "低档",
        "middle" to "中档",
        "medium" to "中档",
        "high" to "高档",
        "quiet" to "静音",
        "silent" to "静音",
        "sleep" to "睡眠",
        "turbo" to "强力",
        "strong" to "强力",
        "weak" to "弱",
        "standard" to "标准",
        "normal" to "常规",
        "custom" to "自定义",
        "cool" to "制冷",
        "heat" to "制热",
        "fan" to "送风",
        "dry" to "除湿",
        "fan-dry" to "除湿",
        "fresh" to "新鲜",
        "warm" to "保温",
        "breeze" to "柔风",
        "natural" to "自然风",
        "none" to "无",
        "idle" to "待机",
        "running" to "运行中",
        "paused" to "暂停",
        "charging" to "充电中",
        "docked" to "已归位",
        "sweeping" to "清扫中",
        "mopping" to "拖地中",
        "paused-state" to "已暂停",
        "charging-state" to "充电中",
        "go-charging" to "回充中",
        "finished" to "已完成",
        "stuck" to "被困住了",
        "turn-off" to "关机",
        "turn-on" to "开机",
        "up" to "上调",
        "down" to "下调",
        "left" to "向左",
        "right" to "向右",
        "celsius" to "摄氏度",
        "fahrenheit" to "华氏度",
        "percentage" to "百分比",
        "seconds" to "秒",
        "minutes" to "分钟",
        "hours" to "小时",
        "ppm" to "ppm",
        // 台灯 / 照明模式补充
        "read" to "阅读",
        "reading" to "阅读",
        "pc" to "电脑",
        "computer" to "电脑",
        "favorite" to "收藏",
        "night" to "夜灯",
        "nightlight" to "夜灯",
        "night-light" to "夜灯",
        "study" to "学习",
        "work" to "工作",
        "day" to "日光",
        "candle" to "烛光",
        "flash" to "闪烁",
        "warm" to "暖光",
        "bright" to "亮光",
        "movie" to "观影",
        "tv" to "观影",
        "dinner" to "用餐",
        "mode-1" to "模式1",
        "mode-2" to "模式2",
        "mode-3" to "模式3",
        "mode-4" to "模式4",
        "mode1" to "模式1",
        "mode2" to "模式2",
        "mode3" to "模式3",
        "mode4" to "模式4",
    )

    /** 单词级直译，用于拼接未知短语（仅当全部词都能翻才用）。 */
    private val TOKEN_ZH = mapOf(
        "target" to "目标", "temperature" to "温度", "humidity" to "湿度",
        "brightness" to "亮度", "color" to "颜色",
        "mode" to "模式", "level" to "档位", "speed" to "速度", "fan" to "风",
        "delay" to "延时", "countdown" to "倒计时", "fault" to "故障",
        "status" to "状态", "battery" to "电池", "power" to "功率",
        "volume" to "音量", "mute" to "静音", "play" to "播放",
        "state" to "状态", "time" to "时间", "area" to "面积",
        "count" to "数量", "number" to "数量", "light" to "灯光",
        "sway" to "摆动", "swing" to "摆动", "vertical" to "垂直",
        "horizontal" to "水平", "position" to "位置", "water" to "水",
        "air" to "空气", "quality" to "质量", "filter" to "滤芯",
        "read" to "阅读", "reading" to "阅读", "pc" to "电脑",
        "computer" to "电脑", "favorite" to "收藏", "night" to "夜灯",
        "study" to "学习", "work" to "工作", "day" to "日光",
        "movie" to "观影", "dinner" to "用餐", "lamp" to "台灯",
        "life" to "寿命", "error" to "错误", "signal" to "信号",
        "channel" to "信道", "address" to "地址", "ssid" to "WiFi名称",
        "wifi" to "WiFi", "password" to "密码", "key" to "按键",
        "on" to "开关", "off" to "关", "enable" to "开关",
        "temperature-humidity" to "温湿度",
    )

    private val NUM_SUFFIX = Regex("-\\d+$")

    /** 是否含中文。 */
    fun hasCjk(text: String): Boolean = text.any { it in '\u4e00'..'\u9fff' }

    /** 按名查表：精确匹配 → 去掉尾部数字后缀重试。 */
    private fun lookup(name: String, table: Map<String, String>): String? {
        table[name]?.let { return it }
        val stripped = name.replace(NUM_SUFFIX, "")
        return if (stripped != name) table[stripped] else null
    }

    /** 属性的中文名；无兜底返回 null。 */
    fun propZh(name: String): String? = lookup(name, PROPERTY_ZH)

    /** 动作的中文名；无兜底返回 null。 */
    fun actionZh(name: String): String? = lookup(name, ACTION_ZH)

    /** 枚举值/单位英文 → 中文；无兜底返回 null。 */
    fun valueZh(en: String): String? = VALUE_ZH[en.trim().lowercase()]

    /**
     * 未知英文短语的直译：按空格/连字符拆词逐个翻，全部能翻才输出，
     * 否则返回 null（宁可保守，也不要半中半英）。
     */
    fun translatePhrase(en: String): String? {
        val tokens = en.trim().lowercase().split(Regex("[\\s_\\-/]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val zh = tokens.map { VALUE_ZH[it] ?: TOKEN_ZH[it] ?: return null }
        return zh.joinToString("")
    }

    /** 描述里的中文段（`"English / 中文"` 或纯中文）；没有返回 null。 */
    fun cjkPart(desc: String): String? = desc.split(" / ").firstOrNull { hasCjk(it) }
}
