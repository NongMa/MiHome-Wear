package com.mijia.wear.repo

import android.content.Context
import com.mijia.wear.model.ActionDef
import com.mijia.wear.model.PropDef
import com.mijia.wear.model.SpecInfo
import com.mijia.wear.model.ValueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * miot-spec.org 设备规格仓库，对应 mijia-api 的 `get_device_info()`：
 * 按 spec_type URN 拉规格 → 展平成属性/动作表 → 落盘缓存（冒号转下划线）。
 *
 * spec 结构决定了控件工厂的输入，缓存损坏时必须能自愈（重拉）。
 */
class SpecRepository(context: Context) {

    private val cacheDir = File(context.filesDir, "spec").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, SpecInfo>()

    /** 防止同一/不同规格并发重复拉取；suspend 友好，替代 synchronized。 */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 取规格；spec_type 为空（云端未下发公开规格）返回 null。 */
    suspend fun get(specType: String): SpecInfo? {
        if (specType.isBlank()) return null
        memory[specType]?.let { return it }
        mutex.withLock {
            memory[specType]?.let { return it }
            val cached = readCache(specType)
            if (cached != null) {
                memory[specType] = cached
                return cached
            }
            val fetched = fetch(specType) ?: return null
            writeCache(specType, fetched)
            memory[specType] = fetched
            return fetched
        }
    }

    private fun cacheFile(specType: String) = File(cacheDir, specType.replace(':', '_') + ".json")

    private fun readCache(specType: String): SpecInfo? = runCatching {
        val file = cacheFile(specType)
        if (!file.exists()) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("version") != CACHE_VERSION) return null
        parse(json, specType)
    }.getOrNull()

    private fun writeCache(specType: String, info: SpecInfo) = runCatching {
        cacheFile(specType).writeText(toJson(info).toString(), Charsets.UTF_8)
    }

    private suspend fun fetch(specType: String): SpecInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$SPEC_URL?type=$specType")
            .header("User-Agent", "MiHomeWear")
            .get()
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (!json.has("description") || !json.has("services")) return@withContext null
                buildFromRaw(json, specType)
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            // 个别规格在 miot-spec.org 不存在，按无能力处理
            null
        }
    }

    // ---------- 原始 spec → 内部模型 ----------

    private fun buildFromRaw(root: JSONObject, specType: String): SpecInfo {
        val props = ArrayList<PropDef>()
        val actions = ArrayList<ActionDef>()
        val services = root.optJSONArray("services") ?: JSONArray()

        for (i in 0 until services.length()) {
            val svc = services.getJSONObject(i)
            val svcType = svc.optString("type")
            // 跳过 device-information 服务（只含固件版本等元信息）
            if (svcType.split(":").getOrNull(3) == "device-information") continue
            val siid = svc.optInt("iid")

            val svcProps = svc.optJSONArray("properties") ?: JSONArray()
            for (j in 0 until svcProps.length()) {
                val p = svcProps.getJSONObject(j)
                val format = p.optString("format")
                val type = when {
                    format.startsWith("int") -> "int"
                    format.startsWith("uint") -> "uint"
                    else -> format
                }
                val access = p.optJSONArray("access") ?: JSONArray()
                val rw = buildString {
                    for (k in 0 until access.length()) {
                        when (access.optString(k)) {
                            "read" -> append('r')
                            "write" -> append('w')
                        }
                    }
                }
                val range = p.optJSONArray("value-range")?.let { arr ->
                    (0 until arr.length()).map { arr.getDouble(it) }
                }
                val valueList = p.optJSONArray("value-list")?.let { arr ->
                    (0 until arr.length()).map { item ->
                        val v = arr.getJSONObject(item)
                        valueItem(
                            value = v.optInt("value"),
                            en = v.optString("description"),
                            comment = v.optString("comment").takeIf { it.isNotEmpty() },
                        )
                    }
                }
                props += PropDef(
                    name = p.optString("type").split(":").getOrNull(3) ?: "unknown",
                    desc = backfillZh(
                        name = p.optString("type").split(":").getOrNull(3) ?: "unknown",
                        desc = joinDesc(p.optString("description"), p.optString("comment")),
                        zh = SpecZh::propZh,
                    ),
                    type = type,
                    readable = rw.contains('r'),
                    writable = rw.contains('w'),
                    range = range,
                    valueList = valueList,
                    siid = siid,
                    piid = p.optInt("iid"),
                )
            }

            val svcActions = svc.optJSONArray("actions") ?: JSONArray()
            for (j in 0 until svcActions.length()) {
                val a = svcActions.getJSONObject(j)
                val actionName = a.optString("type").split(":").getOrNull(3) ?: "unknown"
                actions += ActionDef(
                    name = actionName,
                    desc = backfillZh(
                        name = actionName,
                        desc = joinDesc(a.optString("description"), a.optString("comment")),
                        zh = SpecZh::actionZh,
                    ),
                    siid = siid,
                    aiid = a.optInt("iid"),
                    serviceType = svcType.split(":").getOrNull(3) ?: "",
                    inPis = a.optJSONArray("in").toIidList(),
                    outPis = a.optJSONArray("out").toIidList(),
                )
            }
        }

        // 上游的两轮消歧：重名项依次追加 -siid、再追加 -piid/aiid
        dedupeInPlace(props, actions)

        return SpecInfo(
            name = root.optString("description"),
            specType = specType,
            properties = props,
            actions = actions,
        )
    }

    /** 与上游 `_deduplicate_names` 对齐：重名项依次追加 -siid、-piid/aiid。 */
    private fun dedupeInPlace(props: MutableList<PropDef>, actions: MutableList<ActionDef>) {
        var counts = props.groupingBy { it.name }.eachCount()
        for (i in props.indices) {
            val p = props[i]
            if ((counts[p.name] ?: 0) > 1) props[i] = p.copy(name = "${p.name}-${p.siid}")
        }
        counts = props.groupingBy { it.name }.eachCount()
        for (i in props.indices) {
            val p = props[i]
            if ((counts[p.name] ?: 0) > 1) props[i] = p.copy(name = "${p.name}-${p.piid}")
        }

        var actCounts = actions.groupingBy { it.name }.eachCount()
        for (i in actions.indices) {
            val a = actions[i]
            if ((actCounts[a.name] ?: 0) > 1) actions[i] = a.copy(name = "${a.name}-${a.siid}")
        }
        actCounts = actions.groupingBy { it.name }.eachCount()
        for (i in actions.indices) {
            val a = actions[i]
            if ((actCounts[a.name] ?: 0) > 1) actions[i] = a.copy(name = "${a.name}-${a.aiid}")
        }
    }

    private fun joinDesc(description: String, comment: String): String =
        if (comment.isEmpty()) description
        else if (description.isEmpty()) comment
        else "$description / $comment"

    /**
     * 描述补中文：已有中文原样；否则按短名查表、再试短语直译，
     * 命中则拼成 `英文 / 中文`（界面层只需取中文段）。
     */
    private fun backfillZh(name: String, desc: String, zh: (String) -> String?): String {
        if (SpecZh.hasCjk(desc)) return desc
        val fallback = zh(name) ?: SpecZh.translatePhrase(desc.ifBlank { name }) ?: return desc
        return when {
            desc.isEmpty() -> fallback
            else -> "$desc / $fallback"
        }
    }

    /** 枚举项：comment 中文优先，其次短语直译；英文原值始终保留在 [ValueItem.description]。 */
    private fun valueItem(value: Int, en: String, comment: String?): ValueItem = ValueItem(
        value = value,
        description = en,
        descZh = comment ?: SpecZh.valueZh(en) ?: SpecZh.translatePhrase(en),
    )

    /** action 的 `in`/`out` 数组 → piid 列表；缺失按空处理。 */
    private fun JSONArray?.toIidList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optInt(it).takeIf { v -> v > 0 } }
    }

    // ---------- 缓存序列化 ----------

    private fun toJson(info: SpecInfo): JSONObject {
        val json = JSONObject()
        json.put("version", CACHE_VERSION)
        json.put("name", info.name)
        json.put("spec_type", info.specType)
        val props = JSONArray()
        for (p in info.properties) {
            val item = JSONObject()
                .put("name", p.name)
                .put("description", p.desc)
                .put("type", p.type)
                .put("rw", (if (p.readable) "r" else "") + (if (p.writable) "w" else ""))
            p.range?.let { item.put("range", JSONArray(it)) }
            p.valueList?.let { vl ->
                val arr = JSONArray()
                for (v in vl) {
                    arr.put(
                        JSONObject()
                            .put("value", v.value)
                            .put("description", v.description)
                            .put("desc_zh_cn", v.descZh ?: JSONObject.NULL)
                    )
                }
                item.put("value-list", arr)
            }
            item.put("method", JSONObject().put("siid", p.siid).put("piid", p.piid))
            props.put(item)
        }
        json.put("properties", props)
        val acts = JSONArray()
        for (a in info.actions) {
            acts.put(
                JSONObject()
                    .put("name", a.name)
                    .put("description", a.desc)
                    .put("service", a.serviceType)
                    .put("in", JSONArray(a.inPis))
                    .put("out", JSONArray(a.outPis))
                    .put("method", JSONObject().put("siid", a.siid).put("aiid", a.aiid))
            )
        }
        json.put("actions", acts)
        return json
    }

    private fun parse(json: JSONObject, specType: String): SpecInfo {
        val props = ArrayList<PropDef>()
        val arr = json.optJSONArray("properties") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val method = p.optJSONObject("method") ?: JSONObject()
            val rw = p.optString("rw")
            val valueList = p.optJSONArray("value-list")?.let { vl ->
                (0 until vl.length()).map { j ->
                    val v = vl.getJSONObject(j)
                    valueItem(
                        value = v.optInt("value"),
                        en = v.optString("description"),
                        comment = v.optString("desc_zh_cn").takeIf { it.isNotEmpty() },
                    )
                }
            }
            props += PropDef(
                name = p.optString("name"),
                desc = backfillZh(
                    name = p.optString("name"),
                    desc = p.optString("description"),
                    zh = SpecZh::propZh,
                ),
                type = p.optString("type"),
                readable = rw.contains('r'),
                writable = rw.contains('w'),
                range = p.optJSONArray("range")?.let { r -> (0 until r.length()).map { r.getDouble(it) } },
                valueList = valueList,
                siid = method.optInt("siid"),
                piid = method.optInt("piid"),
            )
        }
        val actions = ArrayList<ActionDef>()
        val actArr = json.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until actArr.length()) {
            val a = actArr.getJSONObject(i)
            val method = a.optJSONObject("method") ?: JSONObject()
            actions += ActionDef(
                name = a.optString("name"),
                desc = backfillZh(
                    name = a.optString("name"),
                    desc = a.optString("description"),
                    zh = SpecZh::actionZh,
                ),
                siid = method.optInt("siid"),
                aiid = method.optInt("aiid"),
                serviceType = a.optString("service"),
                inPis = a.optJSONArray("in").toIidList(),
                outPis = a.optJSONArray("out").toIidList(),
            )
        }
        return SpecInfo(json.optString("name"), specType, props, actions)
    }

    companion object {
        private const val SPEC_URL = "https://miot-spec.org/miot-spec-v2/instance"
        private const val CACHE_VERSION = 4
    }
}
