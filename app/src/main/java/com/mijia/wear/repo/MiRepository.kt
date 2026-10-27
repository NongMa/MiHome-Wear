package com.mijia.wear.repo

import com.mijia.wear.model.ActionDef
import com.mijia.wear.model.DeviceDetail
import com.mijia.wear.model.DeviceInfo
import com.mijia.wear.model.HomeInfo
import com.mijia.wear.model.PropDef
import com.mijia.wear.model.SceneInfo
import com.mijia.wear.model.SpecInfo
import com.mijia.wear.net.MiApiException
import com.mijia.wear.net.MiCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 米家业务仓库：家庭 / 设备 / 场景 / 属性与动作控制。
 * 语义对齐 Windows 版 `app/core/service.py` 的适配层，
 * 网络细节全部下沉到 [MiCloudClient]。
 */
class MiRepository(
    private val client: MiCloudClient,
    private val specs: SpecRepository,
) {

    // ---------- 家庭 ----------

    suspend fun homes(): List<HomeInfo> = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("fg", true)
            .put("fetch_share", true)
            .put("fetch_share_dev", true)
            .put("fetch_cariot", true)
            .put("limit", 300)
            .put("app_ver", 7)
            .put("plat_form", 0)
        val homelist = client.request("/v2/homeroom/gethome_merged", data)
            .optJSONArray("homelist") ?: JSONArray()
        (0 until homelist.length()).map { i ->
            val h = homelist.getJSONObject(i)
            HomeInfo(
                id = h.optString("id"),
                name = h.optString("name", "未命名家庭"),
                uid = h.optLong("uid"),
            )
        }
    }

    /** did -> (家庭名, 房间名)，由各家庭的 roomlist 反查。 */
    private fun roomIndex(homesRaw: JSONArray): Map<String, Pair<String, String>> {
        val map = HashMap<String, Pair<String, String>>()
        for (i in 0 until homesRaw.length()) {
            val home = homesRaw.getJSONObject(i)
            val homeName = home.optString("name", "未知")
            val rooms = home.optJSONArray("roomlist") ?: continue
            for (j in 0 until rooms.length()) {
                val room = rooms.getJSONObject(j)
                val roomName = room.optString("name", "未知")
                val dids = room.optJSONArray("dids") ?: continue
                for (k in 0 until dids.length()) {
                    map[dids.optString(k)] = homeName to roomName
                }
            }
        }
        return map
    }

    // ---------- 设备 ----------

    suspend fun devices(): List<DeviceInfo> = withContext(Dispatchers.IO) {
        val homesRaw = requestHomesRaw()
        val locations = roomIndex(homesRaw)
        val result = LinkedHashMap<String, DeviceInfo>()

        for (i in 0 until homesRaw.length()) {
            val home = homesRaw.getJSONObject(i)
            val homeId = home.optString("id")
            val uid = home.optLong("uid")
            for (raw in devicesInHome(homeId, uid)) {
                val did = raw.optString("did")
                if (did.isEmpty()) continue
                val (homeName, roomName) = locations[did] ?: ("未知" to "未知")
                result[did] = raw.toDeviceInfo(homeName, roomName)
            }
        }
        // 共享设备列表（上游 device_list_page，按 owner 字段过滤，与 CLI 一致）
        for (raw in sharedDevices()) {
            val did = raw.optString("did")
            if (did.isEmpty() || result.containsKey(did)) continue
            result[did] = raw.toDeviceInfo("共享", "共享")
        }

        result.values.sortedWith(
            compareBy({ it.homeName }, { it.roomName }, { it.name })
        )
    }

    private suspend fun requestHomesRaw(): JSONArray {
        val data = JSONObject()
            .put("fg", true)
            .put("fetch_share", true)
            .put("fetch_share_dev", true)
            .put("fetch_cariot", true)
            .put("limit", 300)
            .put("app_ver", 7)
            .put("plat_form", 0)
        return client.request("/v2/homeroom/gethome_merged", data)
            .optJSONArray("homelist") ?: JSONArray()
    }

    private fun devicesInHome(homeId: String, ownerUid: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var startDid = ""
        while (true) {
            val data = JSONObject()
                .put("home_owner", ownerUid)
                .put("home_id", homeId.toLongOrNull() ?: 0L)
                .put("limit", 200)
                .put("start_did", startDid)
                .put("get_split_device", true)
                .put("support_smart_home", true)
                .put("get_cariot_device", true)
                .put("get_third_device", true)
            val ret = client.request("/home/home_device_list", data)
            val info = ret.optJSONArray("device_info")
            if (info == null || info.length() == 0) break
            for (i in 0 until info.length()) out += info.getJSONObject(i)
            val hasMore = ret.optBoolean("has_more", false)
            startDid = ret.optString("max_did", "")
            if (!hasMore || startDid.isEmpty()) break
        }
        return out
    }

    private fun sharedDevices(): List<JSONObject> {
        val data = JSONObject()
            .put("ssid", "<unknown ssid>")
            .put("bssid", "02:00:00:00:00:00")
            .put("getVirtualModel", true)
            .put("getHuamiDevices", 1)
            .put("get_split_device", true)
            .put("support_smart_home", true)
            .put("get_cariot_device", true)
            .put("get_third_device", true)
            .put("get_phone_device", true)
            .put("get_miwear_device", true)
        val list = client.request("/v2/home/device_list_page", data).optJSONArray("list") ?: JSONArray()
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter { it.optBoolean("owner", false) }
    }

    private fun JSONObject.toDeviceInfo(homeName: String, roomName: String) = DeviceInfo(
        did = optString("did"),
        name = optString("name", optString("did")),
        model = optString("model"),
        homeName = homeName,
        roomName = roomName,
        online = optBoolean("isOnline", false),
        specType = optString("spec_type", ""),
    )

    // ---------- 场景 ----------

    suspend fun scenes(): List<SceneInfo> = withContext(Dispatchers.IO) {
        val homesRaw = requestHomesRaw()
        val out = ArrayList<SceneInfo>()
        for (i in 0 until homesRaw.length()) {
            val home = homesRaw.getJSONObject(i)
            val homeId = home.optString("id")
            val uid = home.optLong("uid")
            val homeName = home.optString("name", "")
            val data = JSONObject()
                .put("app_version", 12)
                .put("get_type", 2)
                .put("home_id", homeId)
                .put("owner_uid", uid)
            val ret = try {
                client.request(
                    "/appgateway/miot/appsceneservice/AppSceneService/GetSimpleSceneList",
                    data,
                )
            } catch (e: MiApiException) {
                continue
            }
            val arr = ret.optJSONArray("manual_scene_info_list") ?: continue
            for (j in 0 until arr.length()) {
                val s = arr.getJSONObject(j)
                out += SceneInfo(
                    sceneId = s.optString("scene_id"),
                    name = s.optString("name", "未命名场景"),
                    homeId = s.optString("home_id", homeId),
                    homeName = homeName,
                    ownerUid = uid,
                )
            }
        }
        out
    }

    /** 执行手动场景；上游返回假值视为失败。 */
    suspend fun runScene(scene: SceneInfo) = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("scene_id", scene.sceneId)
            .put("scene_type", 2)
            .put("phone_id", "null")
            .put("home_id", scene.homeId)
            .put("owner_uid", scene.ownerUid)
        val ret = client.request(
            "/appgateway/miot/appsceneservice/AppSceneService/NewRunScene",
            data,
        )
        // 上游把 result 为假视为失败；对象/标量都按成功处理
        val ok = when (val v = ret.opt(MiCloudClient.SCALAR_KEY)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.isNotEmpty() && v != "false" && v != "0"
            else -> true
        }
        if (!ok) throw MiApiException(-1, "场景执行失败")
    }

    // ---------- 属性读写 ----------

    /** 批量读取同一台设备的属性，一次往返。 */
    suspend fun readProps(did: String, props: List<PropDef>): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val queries = JSONArray()
            val keyToName = HashMap<Pair<Int, Int>, String>()
            for (p in props) {
                if (!p.readable) continue
                queries.put(JSONObject().put("did", did).put("siid", p.siid).put("piid", p.piid))
                keyToName[p.siid to p.piid] = p.name
            }
            val result = HashMap<String, Any?>(keyToName.size)
            for (name in keyToName.values) result[name] = null
            if (queries.length() == 0) return@withContext result

            val rets = client.requestArray(
                "/miotspec/prop/get",
                JSONObject().put("params", queries).put("datasource", 1),
            )
            for (i in 0 until rets.length()) {
                val item = rets.getJSONObject(i)
                val name = keyToName[item.optInt("siid") to item.optInt("piid")] ?: continue
                result[name] = if (item.optInt("code", 0) == 0) item.opt("value") else null
            }
            result
        }

    /** 写单个属性；写接口 code ∈ {0,1} 都算成功（1=网关已接收）。 */
    suspend fun setProp(did: String, prop: PropDef, value: Any?) = withContext(Dispatchers.IO) {
        if (!prop.writable) throw MiApiException(-1, "属性 ${prop.name} 不可写入")
        val param = JSONObject()
            .put("did", did)
            .put("siid", prop.siid)
            .put("piid", prop.piid)
            .put("value", value ?: JSONObject.NULL)
        val rets = client.requestArray("/miotspec/prop/set", JSONObject().put("params", JSONArray().put(param)))
        for (i in 0 until rets.length()) {
            val item = rets.getJSONObject(i)
            val code = item.optInt("code", 0)
            if (code !in intArrayOf(0, 1)) {
                throw MiApiException(code, item.optString("message", "设置失败"))
            }
        }
    }

    /** 执行动作。[extra] 中 `in` 字段用于小爱类文本指令通道。 */
    suspend fun runAction(did: String, action: ActionDef, extra: JSONObject? = null) =
        withContext(Dispatchers.IO) {
            val param = JSONObject()
                .put("did", did)
                .put("siid", action.siid)
                .put("aiid", action.aiid)
            if (extra != null) {
                for (key in extra.keys()) param.put(key, extra.get(key))
            }
            val ret = client.request("/miotspec/action", JSONObject().put("params", param))
            val code = ret.optInt("code", 0)
            if (code !in intArrayOf(0, 1)) {
                throw MiApiException(code, ret.optString("message", "执行失败"))
            }
        }

    /**
     * 执行带入参的动作并返回 **out 数组**（位置顺序与 [ActionDef.outPis] 对应）。
     *
     * 摄像头拉流（start-rtsp-stream / start-hls-stream）靠它拿回流地址：
     * `in` 按 [ActionDef.inPis] 的位置顺序传值，`out` 里是 stream-address 之类的出参。
     */
    suspend fun runActionOut(
        did: String,
        action: ActionDef,
        inValues: List<Any?> = emptyList(),
    ): JSONArray = withContext(Dispatchers.IO) {
        val param = JSONObject()
            .put("did", did)
            .put("siid", action.siid)
            .put("aiid", action.aiid)
            .put("in", JSONArray(inValues))
        val ret = client.request("/miotspec/action", JSONObject().put("params", param))
        val code = ret.optInt("code", 0)
        if (code !in intArrayOf(0, 1)) {
            throw MiApiException(code, ret.optString("message", "执行失败"))
        }
        // 云端有时把 out 放在 result 下，两种位置都兜住
        ret.optJSONArray("out")
            ?: ret.optJSONObject("result")?.optJSONArray("out")
            ?: JSONArray()
    }

    // ---------- 开关（多键面板子设备的通道映射） ----------

    private val subDeviceSuffix = Regex("""\.s(\d+)$""")

    /** `1234567890.s3` → `1234567890`；普通 did 返回 null。 */
    private fun parentDid(did: String): String? {
        val m = subDeviceSuffix.find(did) ?: return null
        return did.substring(0, m.range.first)
    }

    /** 开关请求发给谁：虚拟子设备一律发给父设备（云端只认父 did）。 */
    private fun switchTarget(did: String): String = parentDid(did) ?: did

    /**
     * 挑出该设备可写的 on 属性。多键面板规格含 on-2/on-3/on-4 多路，
     * 必须按 did 后缀选对应通道，否则会控制错灯。
     */
    fun findOnMethod(spec: SpecInfo?, did: String): PropDef? {
        if (spec == null) return null
        val props = spec.properties.filter {
            it.type == "bool" && it.writable &&
                (it.name == "on" || it.name.replace('_', '-').startsWith("on-"))
        }
        if (props.isEmpty()) return null
        val gang = subDeviceSuffix.find(did)?.groupValues?.get(1)?.toIntOrNull()
        if (gang != null) {
            props.firstOrNull { it.siid == gang }?.let { return it }
            val index = gang - 2
            if (index in props.indices) return props[index]
        }
        return props[0]
    }

    /** 批量读取设备开关状态；无开关能力的设备返回 null。 */
    suspend fun powerStates(devices: List<DeviceInfo>): Map<String, Boolean?> =
        withContext(Dispatchers.IO) {
            val result = HashMap<String, Boolean?>()
            val methodOf = HashMap<String, PropDef>()
            val targetOf = HashMap<String, String>()
            val owners = ArrayList<String>()
            val queries = JSONArray()

            for (d in devices) {
                val spec = specs.get(d.specType)
                val on = findOnMethod(spec, d.did)
                if (on == null) {
                    result[d.did] = null
                    continue
                }
                methodOf[d.did] = on
                targetOf[d.did] = switchTarget(d.did)
                queries.put(JSONObject().put("did", targetOf[d.did]).put("siid", on.siid).put("piid", on.piid))
                owners.add(d.did)
            }
            if (queries.length() == 0) return@withContext result

            val rets = client.requestArray(
                "/miotspec/prop/get",
                JSONObject().put("params", queries).put("datasource", 1),
            )
            // 响应按 (did, siid) 回填：同一父设备多路共用一个 did
            val byKey = HashMap<Pair<String, Int>, JSONObject>()
            for (i in 0 until rets.length()) {
                val item = rets.getJSONObject(i)
                byKey[item.optString("did") to item.optInt("siid")] = item
            }
            for (i in owners.indices) {
                val owner = owners[i]
                val method = methodOf[owner] ?: continue
                val item = byKey[targetOf[owner] to method.siid] ?: rets.optJSONObject(i)
                if (item != null && item.optInt("code", 0) == 0) {
                    result[owner] = item.opt("value").toBool()
                }
                // 读失败不写入：None 的语义是"确认没有开关能力"
            }
            result
        }

    /** 写开关状态（直写批量接口，一次请求完成）。 */
    suspend fun setPower(device: DeviceInfo, state: Boolean) = withContext(Dispatchers.IO) {
        val spec = specs.get(device.specType)
        val on = findOnMethod(spec, device.did)
            ?: throw MiApiException(-1, "设备不支持开关控制")
        val param = JSONObject()
            .put("did", switchTarget(device.did))
            .put("siid", on.siid)
            .put("piid", on.piid)
            .put("value", state)
        val rets = client.requestArray("/miotspec/prop/set", JSONObject().put("params", JSONArray().put(param)))
        for (i in 0 until rets.length()) {
            val item = rets.getJSONObject(i)
            val code = item.optInt("code", 0)
            if (code !in intArrayOf(0, 1)) {
                throw MiApiException(code, item.optString("message", "设置失败"))
            }
        }
    }

    // ---------- 控制面板 ----------

    /** 规格驱动的设备详情：属性控件清单 + 动作按钮清单。 */
    suspend fun deviceDetail(device: DeviceInfo): DeviceDetail? = withContext(Dispatchers.IO) {
        val spec = specs.get(device.specType) ?: return@withContext null
        DeviceDetail(
            did = device.did,
            name = device.name,
            model = device.model,
            props = spec.properties,
            actions = spec.actions,
        )
    }

    // ---------- 小爱 ----------

    /** 向小爱音箱下发文本指令（execute-text-directive 走 in 通道）。 */
    suspend fun sendTextDirective(device: DeviceInfo, text: String, quiet: Boolean = false) {
        val spec = specs.get(device.specType)
            ?: throw MiApiException(-1, "该音箱无公开规格")
        val action = spec.actions.firstOrNull { it.name == "execute-text-directive" }
            ?: throw MiApiException(-1, "音箱不支持文本指令")
        val extra = JSONObject().put("in", JSONArray().put(text).put(if (quiet) 1 else 0))
        runAction(device.did, action, extra)
    }

    /** 找一台在线的小爱音箱；[prefer] 为空时取第一台在线的。 */
    fun pickSpeaker(devices: List<DeviceInfo>, prefer: String = ""): DeviceInfo? {
        if (prefer.isNotEmpty()) {
            devices.firstOrNull { it.did == prefer && it.online && it.isSpeaker }?.let { return it }
        }
        return devices.firstOrNull { it.isSpeaker && it.online }
    }

    suspend fun spec(device: DeviceInfo): SpecInfo? = specs.get(device.specType)

    /** 网络错误统一出口，方便上层提示。 */
    fun describe(e: Exception): String = when (e) {
        is MiApiException -> "云端返回错误（${e.code}）"
        is IOException -> "网络异常，请检查手表联网状态"
        else -> e.message ?: "未知错误"
    }
}

/** 云端布尔值可能是 true / 1 / "true"，统一转成 Boolean。 */
private fun Any?.toBool(): Boolean = when (this) {
    is Boolean -> this
    is Number -> toInt() != 0
    is String -> this == "true" || this == "1"
    else -> false
}
