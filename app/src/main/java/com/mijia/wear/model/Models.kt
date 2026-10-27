package com.mijia.wear.model

/** 设备列表条目（对应 Windows 版 core/models.py 的 DeviceInfo）。 */
data class DeviceInfo(
    val did: String,
    val name: String,
    val model: String,
    val homeName: String,
    val roomName: String,
    val online: Boolean,
    val specType: String = "",
) {
    /** 小爱音箱判定：与 Windows 版一致，按 model 是否含 wifispeaker。 */
    val isSpeaker: Boolean get() = "wifispeaker" in model

    /**
     * 路由器判定：米家路由的 model 形如 `xiaomi.router.xxx` / `...router...`，
     * 路由器在手表上没有可操作的 spec，列表里过滤掉。
     */
    val isRouter: Boolean get() = "router" in model
}

/** 米家家庭。 */
data class HomeInfo(
    val id: String,
    val name: String,
    val uid: Long,
)

/** 米家手动场景。 */
data class SceneInfo(
    val sceneId: String,
    val name: String,
    val homeId: String,
    val homeName: String,
    val ownerUid: Long,
)

/** 属性值枚举项。 */
data class ValueItem(
    val value: Int,
    val description: String,
    val descZh: String?,
) {
    val label: String get() = descZh ?: description
}

/** 单个属性元数据，界面据此决定生成开关/滑块/下拉哪种控件。 */
data class PropDef(
    val name: String,
    val desc: String,
    val type: String,           // bool / int / uint / float / string
    val readable: Boolean,
    val writable: Boolean,
    val range: List<Double>?,    // [min, max, step]
    val valueList: List<ValueItem>?,
    val siid: Int,
    val piid: Int,
) {
    /** 数值型且有范围 → 滑块/步进器 */
    val isNumeric: Boolean get() = type in NUMERIC && range != null && range.size >= 2
    /** 有枚举表 → 下拉/单选 */
    val isEnum: Boolean get() = !valueList.isNullOrEmpty()

    companion object {
        private val NUMERIC = setOf("int", "uint", "float")
    }
}

/**
 * 单个动作（按钮）元数据。
 *
 * [inPis]/[outPis] 对应 miot-spec 里 action 的 `in`/`out` 属性 iid 列表：
 * `in` 是入参值的**位置顺序**，`out` 是返回值的**位置顺序**，
 * 摄像头拉流这类带参数/带返回值的动作必须靠它构造和解析。
 */
data class ActionDef(
    val name: String,
    val desc: String,
    val siid: Int,
    val aiid: Int,
    /** 所属服务的类型名（urn 第 4 段，如 camera-stream-for-amazon-alexa）。 */
    val serviceType: String = "",
    /** 入参属性 piid，顺序即请求 `in` 数组顺序。 */
    val inPis: List<Int> = emptyList(),
    /** 出参属性 piid，顺序即响应 `out` 数组顺序。 */
    val outPis: List<Int> = emptyList(),
)

/** 设备功能规格（miot-spec）。 */
data class SpecInfo(
    val name: String,
    val specType: String,
    val properties: List<PropDef>,
    val actions: List<ActionDef>,
) {
    fun prop(name: String): PropDef? = properties.firstOrNull { it.name == name }
}

/** 点开设备后控制面板所需的全部信息。 */
data class DeviceDetail(
    val did: String,
    val name: String,
    val model: String,
    val props: List<PropDef>,
    val actions: List<ActionDef>,
)
