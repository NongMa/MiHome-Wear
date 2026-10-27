package com.mijia.wear.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 登录凭据持久化，对应上游的 `~/.config/mijia-api/auth.json`。
 *
 * 手表上放在应用私有目录 filesDir/auth.json（等价于 Windows 版的
 * `%LOCALAPPDATA%` 之外的凭据文件），卸载应用才会清除。
 * 字段与 mijiaAPI 保持一致，便于排查问题时对照。
 */
class AuthStore(context: Context) {

    private val file = File(context.filesDir, "auth.json")
    private val lock = Any()
    private var data: JSONObject = loadFromDisk()

    private fun loadFromDisk(): JSONObject = synchronized(lock) {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun hasSession(): Boolean = synchronized(lock) {
        listOf("ua", "ssecurity", "userId", "cUserId", "serviceToken")
            .all { data.has(it) && !data.optString(it).isNullOrEmpty() }
    }

    fun getString(key: String): String = synchronized(lock) { data.optString(key, "") }

    fun getLong(key: String): Long = synchronized(lock) { data.optLong(key, 0L) }

    fun set(key: String, value: Any?) = synchronized(lock) {
        if (value == null) data.remove(key) else data.put(key, value)
        saveLocked()
    }

    fun putAll(values: Map<String, Any?>) = synchronized(lock) {
        for ((k, v) in values) {
            if (v == null) data.remove(k) else data.put(k, v)
        }
        saveLocked()
    }

    /** 退出登录：清空全部凭据。 */
    fun clear() = synchronized(lock) {
        data = JSONObject()
        saveLocked()
    }

    private fun saveLocked() {
        data.put("saveTime", System.currentTimeMillis())
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(data.toString(2), Charsets.UTF_8)
        }
    }
}
