package com.mijia.wear.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.mijia.wear.net.MiCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 官方设备图标：model → 米家 CDN PNG。
 *
 * 地址来自 `/v2/productconfig/get_icon` 的 302 Location（见 [MiCloudClient.iconUrl]），
 * 该 CDN 地址 Expires 到 9999 年，可长期缓存——与上游 MiHome-Windows 的
 * `icon_store` 同源：URL 落 SharedPreferences，图片落 filesDir/icons/，内存再缓存 Bitmap。
 * 拿不到图标的型号记入 [failed]，本会话不再重试，避免反复打接口。
 */
class IconRepository(
    private val context: Context,
    private val client: MiCloudClient,
) {

    private val prefs = context.getSharedPreferences("icon_urls", Context.MODE_PRIVATE)
    private val mem = ConcurrentHashMap<String, Bitmap>()

    /** 本会话内确认拿不到图标的型号。 */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 已有缓存的图标（内存或磁盘），无则 null。 */
    fun cached(model: String): Bitmap? {
        mem[model]?.let { return it }
        if (model in failed) return null
        val f = file(model)
        if (!f.exists()) return null
        return try {
            decode(f.readBytes())?.also { mem[model] = it }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 补齐 [models] 的官方图标，返回本次新取到的 model → Bitmap。
     * 网络调用在 IO 上执行，可安全地在协程里 await。
     */
    suspend fun ensure(models: List<String>): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        val added = HashMap<String, Bitmap>()
        for (model in models.distinct()) {
            // 已有缓存（内存/磁盘）也放进结果，便于冷启动时把磁盘缓存回填到状态
            val hit = cached(model)
            if (hit != null) {
                added[model] = hit
                continue
            }
            if (model in failed) continue
            try {
                val url = prefs.getString(model, null) ?: client.iconUrl(model)?.also { u ->
                    prefs.edit().putString(model, u).apply()
                }
                if (url == null) {
                    failed.add(model)
                    continue
                }
                val bmp = download(url, model)
                if (bmp == null) failed.add(model) else added[model] = bmp
            } catch (e: Exception) {
                // 网络抖动等：本次跳过，不进 failed，下轮刷新还会重试
                Log.w("MiHomeIcon", "取图标失败 $model: $e")
            }
        }
        added
    }

    private fun download(url: String, model: String): Bitmap? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val bytes = resp.body?.bytes()
            if (resp.code != 200 || bytes == null || bytes.isEmpty()) {
                Log.w("MiHomeIcon", "下载图标失败 $model: HTTP ${resp.code}")
                return null
            }
            file(model).writeBytes(bytes)
            decode(bytes)?.also { mem[model] = it }
        }
    } catch (e: Exception) {
        Log.w("MiHomeIcon", "下载图标异常 $model: $e")
        null
    }

    private fun file(model: String): File {
        val dir = File(context.filesDir, "icons").apply { mkdirs() }
        val safe = model.map { if (it in "/\\:*?\"<>|") '_' else it }.joinToString("")
        return File(dir, "$safe.png")
    }

    /** 解码并按需降采样，图标显示尺寸很小，超过 256px 没有意义。 */
    private fun decode(bytes: ByteArray): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val max = 256
        var sample = 1
        while (opts.outWidth / sample > max || opts.outHeight / sample > max) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Exception) {
        null
    }
}
