package com.mijia.wear.net

import com.mijia.wear.data.AuthStore
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 扫码登录所需的二维码内容与长轮询地址。 */
data class QrLoginData(
    val loginUrl: String,   // 二维码编码的内容（米家 APP 扫这个）
    val lp: String,         // 长轮询地址
    val qrImage: String,    // 云端二维码图片地址（备用）
)

/**
 * 小米账号扫码登录，逐流程对应 mijia-api 的 apis.py：
 *
 * 1. `serviceLogin?_json=true&sid=mijia` → 拿 location/ssecurity
 *    - passToken 仍有效时直接刷新 serviceToken（无需扫码）
 * 2. `longPolling/loginUrl` → 拿 loginUrl / lp（二维码内容）
 * 3. 长轮询 `lp` 等米家 APP 扫码 → 拿 passToken/ssecurity 等
 * 4. 回调 location → 收 cookie（serviceToken/cUserId）→ 落盘
 */
class XiaomiAuth(private val store: AuthStore) {

    private val jar = MemoryCookieJar()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 长轮询需要 120 秒，读超时必须大于它，否则扫码还没完成就断开
        .readTimeout(130, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(jar)
        .build()

    // ---------- 随机标识（首次生成后持久化，与上游行为一致） ----------

    val locale: String
        get() {
            var l = Locale.getDefault().toString().replace('-', '_')
            if ('_' !in l) l = "zh_CN"
            return l
        }

    private val countryCode: String
        get() = locale.substringAfter('_', "CN")

    val userAgent: String
        get() {
            store.getString("ua").takeIf { it.isNotEmpty() }?.let { return it }
            val ua = "Android-15-11.0.701-Xiaomi-23046RP50C-OS2.0.212.0.VMYCNXM-" +
                "${randomHex(40)}-$countryCode-" +
                "${randomHex(32)}-${randomHex(32)}-SmartHome-MI_APP_STORE-" +
                "${randomHex(40)}|${randomHex(40)}|${passO}-64"
            store.set("ua", ua)
            return ua
        }

    val deviceId: String
        get() {
            store.getString("deviceId").takeIf { it.isNotEmpty() }?.let { return it }
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-"
            val id = buildString(16) { repeat(16) { append(chars[Random.nextInt(chars.length)]) } }
            store.set("deviceId", id)
            return id
        }

    private val passO: String
        get() {
            store.getString("pass_o").takeIf { it.isNotEmpty() }?.let { return it }
            val v = randomHex(16).lowercase(Locale.ROOT)
            store.set("pass_o", v)
            return v
        }

    private fun randomHex(len: Int): String = buildString(len) {
        repeat(len) { append("0123456789ABCDEF"[Random.nextInt(16)]) }
    }

    // ---------- Cookie ----------

    /** 与上游 _init_session 的 Cookie 头逐字段对齐。 */
    fun apiCookie(): String {
        val tz = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val offsetMin = tz.getOffset(now) / 60_000
        val sign = if (offsetMin < 0) "-" else "+"
        val abs = if (offsetMin < 0) -offsetMin else offsetMin
        val gmt = String.format(Locale.ROOT, "GMT%s%02d:%02d", sign, abs / 60, abs % 60)
        val isDaylight = if (tz.inDaylightTime(Date(now))) 1 else 0
        return "cUserId=${store.getString("cUserId")};" +
            "yetAnotherServiceToken=${store.getString("serviceToken")};" +
            "serviceToken=${store.getString("serviceToken")};" +
            "timezone_id=${tz.id};" +
            "timezone=$gmt;" +
            "is_daylight=$isDaylight;" +
            "dst_offset=${if (isDaylight == 1) 3_600_000 else 0};" +
            "channel=MI_APP_STORE;" +
            "countryCode=$countryCode;" +
            "PassportDeviceId=$deviceId;" +
            "locale=$locale"
    }

    /** serviceLogin 阶段使用的 Cookie（含 passToken）。 */
    private fun passportCookie(): String =
        "deviceId=$deviceId;" +
            "pass_o=$passO;" +
            "passToken=${store.getString("passToken")};" +
            "userId=${store.getString("userId")};" +
            "cUserId=${store.getString("cUserId")};" +
            "uLocale=$locale"

    // ---------- 响应解析 ----------

    private fun parseBody(body: String?): JSONObject {
        require(!body.isNullOrEmpty()) { "云端返回空响应" }
        // 小米账号接口统一以 &&&START&&& 作为 JSON 前缀
        val text = body.removePrefix("&&&START&&&")
        return JSONObject(text)
    }

    /**
     * 对应上游 `_get_location()`：
     * - serviceToken 可刷新 → 落盘新凭据，返回刷新成功标记
     * - 否则返回 location 的查询参数（用于拼 loginUrl）
     */
    private fun getServiceLocation(): Map<String, String> {
        val req = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=mijia&_locale=$locale")
            .header("User-Agent", userAgent)
            .header("Cookie", passportCookie())
            .header("Connection", "keep-alive")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            log("serviceLogin HTTP ${resp.code} body=${body?.take(400)}")
            val data = parseBody(body)
            if (data.optInt("code", -1) == 0) {
                val location = data.optString("location")
                if (location.isNotEmpty() && getOk(location) == "ok") {
                    store.putAll(jar.drain() + mapOf("ssecurity" to data.optString("ssecurity")))
                    return REFRESHED
                }
            }
            // 未登录：location 是二维码登录页地址，其查询参数即 loginUrl 所需入参
            val parsed = parseQuery(data.optString("location"))
            log("serviceLogin 未登录态，location 参数: ${parsed.keys}")
            return parsed
        }
    }

    private fun getOk(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Connection", "keep-alive")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { it.body?.string() }
        } catch (e: IOException) {
            null
        }
    }

    private fun parseQuery(url: String): Map<String, String> = try {
        val query = URI(url).rawQuery ?: ""
        query.split('&')
            .filter { it.isNotEmpty() }
            .associate {
                val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                k to URLDecoder.decode(v, "UTF-8")
            }
    } catch (e: Exception) {
        emptyMap()
    }

    // ---------- 对外接口 ----------

    /** 尝试刷新 serviceToken；成功返回 true（无需重新扫码）。 */
    fun refreshIfPossible(): Boolean = runCatching { getServiceLocation() == REFRESHED }.getOrDefault(false)

    /**
     * 开始扫码登录。token 仍有效时返回 null（表示已登录态，无需扫码）；
     * 否则返回二维码数据。
     */
    @Throws(IOException::class)
    fun beginQrLogin(): QrLoginData? {
        val locationData = getServiceLocation()
        if (locationData == REFRESHED) return null

        val params = locationData.toMutableMap().apply {
            put("theme", "")
            put("bizDeviceType", "")
            put("_hasLogo", "false")
            put("_qrsize", "240")
            put("_dc", System.currentTimeMillis().toString())
        }
        val url = "https://account.xiaomi.com/longPolling/loginUrl?" + encode(params)
        log("loginUrl 请求参数: ${params.keys} url=$url")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Connection", "keep-alive")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            log("loginUrl HTTP ${resp.code} body=${body?.take(500)}")
            check(resp.code == 200) { "获取二维码失败：HTTP ${resp.code}" }
            val data = parseBody(body)
            val code = data.optInt("code", 0)
            check(code == 0) { "获取二维码失败：$code ${data.optString("desc")}" }
            return QrLoginData(
                loginUrl = data.getString("loginUrl"),
                lp = data.getString("lp"),
                qrImage = data.optString("qr", ""),
            )
        }
    }

    /** 长轮询等待米家 APP 扫码并保存凭据。超时抛异常。 */
    @Throws(IOException::class)
    fun completeQrLogin(qr: QrLoginData) {
        val req = Request.Builder()
            .url(qr.lp)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Connection", "keep-alive")
            .get()
            .build()
        val data = http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            log("长轮询 HTTP ${resp.code} body=${body?.take(500)}")
            check(resp.code == 200) { "扫码等待失败：HTTP ${resp.code}" }
            parseBody(body)
        }
        val code = data.optInt("code", 0)
        check(code == 0) { "扫码登录失败：$code ${data.optString("desc", data.optString("message", ""))}" }

        for (key in listOf("psecurity", "nonce", "ssecurity", "passToken", "userId", "cUserId")) {
            store.set(key, data.getString(key))
        }
        // 回调地址：以 serviceToken cookie 落地
        val location = data.getString("location")
        getOk(location)
        store.putAll(jar.drain())
        store.set("expireTime", System.currentTimeMillis() + 30L * 24 * 3600 * 1000)
    }

    private fun encode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                java.net.URLEncoder.encode(v, "UTF-8")
        }

    /** 退出登录。 */
    fun logout() = store.clear()

    companion object {
        private val REFRESHED = mapOf("code" to "0", "message" to "刷新Token成功")
    }
}

/** 统一日志出口：扫码登录排障时按 [MiHomeAuth] 过滤 logcat。 */
private fun log(msg: String) {
    Log.d("MiHomeAuth", msg)
}

/** 仅收集服务端 Set-Cookie（供扫码回调落地凭据），不参与请求回填。 */
private class MemoryCookieJar : CookieJar {
    private val collected = LinkedHashMap<String, String>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) collected[c.name] = c.value
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()

    fun drain(): Map<String, String> {
        val out = HashMap(collected)
        collected.clear()
        return out
    }
}
