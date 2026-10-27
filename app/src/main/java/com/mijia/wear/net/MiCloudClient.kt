package com.mijia.wear.net

import com.mijia.wear.crypto.MiCrypto
import com.mijia.wear.data.AuthStore
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 云端返回 code != 0 或缺少 result。 */
class MiApiException(val code: Int, message: String) : IOException("code=$code $message")

/** 凭据缺失或刷新失败，需要重新扫码。 */
class AuthExpiredException(message: String = "登录已失效，请重新扫码") : IOException(message)

/**
 * 米家云端签名客户端，对应 mijia-api 的 `mijiaAPI._request()`：
 * POST https://api.mijia.tech/app{uri}，表单参数经 RC4+SHA1 签名加密，
 * 响应可能是明文 JSON，也可能是 RC4 密文（必要时内嵌 gzip）。
 */
class MiCloudClient(private val store: AuthStore, private val auth: XiaomiAuth) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 不跟随重定向的客户端：get_icon 靠 302 的 Location 返回图片地址。 */
    private val noRedirect: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    @Volatile private var availableCache: Boolean? = null
    @Volatile private var availableAt: Long = 0L

    /**
     * 确认凭据可用：先用 check_new_msg 轻量探测，失败则尝试静默刷新 token；
     * 两者都失败抛 [AuthExpiredException]（上层据此跳回扫码页）。
     */
    @Throws(AuthExpiredException::class)
    @Synchronized
    fun ensureAvailable() {
        if (!store.hasSession()) throw AuthExpiredException("尚未登录")
        val now = System.currentTimeMillis()
        if (availableCache == true && now - availableAt < 60_000) return

        val probe = try {
            request(
                "/v2/message/v2/check_new_msg",
                JSONObject().put("begin_at", now / 1000 - 3600),
                refresh = false,
            )
            true
        } catch (e: AuthExpiredException) {
            false
        } catch (e: Exception) {
            false
        }
        if (!probe) {
            if (!auth.refreshIfPossible()) {
                availableCache = false
                throw AuthExpiredException()
            }
        }
        availableCache = true
        availableAt = now
    }

    /** 凭据被云端拒绝时调用，让下一次请求重新走刷新流程。 */
    fun invalidate() {
        availableCache = null
    }

    /** 发起签名请求，返回响应的 result 字段。 */
    @Throws(IOException::class)
    fun request(uri: String, data: JSONObject, refresh: Boolean = true): JSONObject {
        if (refresh) ensureAvailable()
        return execute(uri, data.toString(), refresh)
    }

    /** result 为数组的接口（设备列表、属性读写等）用这个。 */
    @Throws(IOException::class)
    fun requestArray(uri: String, data: JSONObject, refresh: Boolean = true): JSONArray {
        if (refresh) ensureAvailable()
        val res = execute(uri, data.toString(), refresh)
        return when (val r = res.opt(SCALAR_KEY)) {
            is JSONArray -> r
            is JSONObject -> JSONArray().put(r)
            else -> JSONArray()
        }
    }

    /**
     * 取官方设备图标地址：POST `/v2/productconfig/get_icon`，
     * 数据 `{"icon_name":"icon_real","model":...}`。该接口不返回 JSON，
     * 而是 302 重定向，`Location` 即米家 CDN 的 PNG（Expires 到 9999 年）。
     * 与上游 MiHome-Windows 的 `_fetch_icon_url` 一致。
     */
    @Throws(IOException::class)
    fun iconUrl(model: String): String? {
        ensureAvailable()
        val ssecurity = store.getString("ssecurity")
        if (ssecurity.isEmpty()) throw AuthExpiredException("缺少 ssecurity")

        val uri = "/v2/productconfig/get_icon"
        val nonce = MiCrypto.genNonce()
        val signedNonce = MiCrypto.signedNonce(ssecurity, nonce)
        val dataJson = JSONObject().put("icon_name", "icon_real").put("model", model).toString()
        val enc = MiCrypto.generateEncParams(
            uri = uri,
            method = "POST",
            signedNonce = signedNonce,
            nonce = nonce,
            plain = mapOf("data" to dataJson),
            ssecurity = ssecurity,
        )
        val form = FormBody.Builder()
        for ((k, v) in enc) form.add(k, v)

        val req = Request.Builder()
            .url(BASE + uri)
            .header("User-Agent", auth.userAgent)
            .header("accept-encoding", "identity")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("miot-accept-encoding", "GZIP")
            .header("miot-encrypt-algorithm", "ENCRYPT-RC4")
            .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
            .header("Cookie", auth.apiCookie())
            .post(form.build())
            .build()

        noRedirect.newCall(req).execute().use { resp ->
            if (resp.code == 302 || resp.code == 301) {
                return resp.header("Location")
            }
            android.util.Log.d("MiHomeIcon", "$model 未重定向: HTTP ${resp.code}")
        }
        return null
    }

    private fun execute(uri: String, dataJson: String, refresh: Boolean): JSONObject {
        val ssecurity = store.getString("ssecurity")
        if (ssecurity.isEmpty()) throw AuthExpiredException("缺少 ssecurity")

        val nonce = MiCrypto.genNonce()
        val signedNonce = MiCrypto.signedNonce(ssecurity, nonce)
        val enc = MiCrypto.generateEncParams(
            uri = uri,
            method = "POST",
            signedNonce = signedNonce,
            nonce = nonce,
            plain = mapOf("data" to dataJson),
            ssecurity = ssecurity,
        )
        val form = FormBody.Builder()
        for ((k, v) in enc) form.add(k, v)

        val req = Request.Builder()
            .url(BASE + uri)
            .header("User-Agent", auth.userAgent)
            .header("accept-encoding", "identity")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("miot-accept-encoding", "GZIP")
            .header("miot-encrypt-algorithm", "ENCRYPT-RC4")
            .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
            .header("Cookie", auth.apiCookie())
            .post(form.build())
            .build()

        val text = http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (resp.code != 200) throw MiApiException(resp.code, body ?: "HTTP ${resp.code}")
            body
        } ?: throw MiApiException(-1, "空响应")

        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            // 明文解析失败 → 按 RC4 密文解（与上游 _request 的兜底一致）
            JSONObject(MiCrypto.decrypt(ssecurity, nonce, text))
        }

        val code = obj.optInt("code", 0)
        if (code != 0 || !obj.has("result")) {
            if (code in AUTH_ERROR_CODES) {
                invalidate()
                throw AuthExpiredException("云端拒绝凭据（code=$code）")
            }
            throw MiApiException(code, obj.optString("message", obj.optString("desc", "未知错误")))
        }
        // result 多数是对象；个别接口返回标量，统一包一层便于调用方取值
        return when (val r = obj.get("result")) {
            is JSONObject -> r
            else -> JSONObject().put(SCALAR_KEY, r)
        }
    }

    companion object {
        const val BASE = "https://api.mijia.tech/app"
        const val SCALAR_KEY = "__scalar"

        /** 与登录态相关的错误码：触发 token 重新探测。 */
        private val AUTH_ERROR_CODES = setOf(-4, -10001, -6001, 401)
    }
}
