package com.mijia.wear.crypto

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * 小米 IoT 云端请求签名与加解密，逐行对应 mijia-api 的 miutils.py：
 *
 * - gen_nonce():          8 字节随机数 + 分钟时间戳（最小大端编码），base64
 * - get_signed_nonce():   SHA256(ssecurity ∥ nonce)，base64
 * - gen_enc_signature():  SHA1("POST&uri&k=v&...&signedNonce")，base64
 * - encrypt_rc4():        RC4 加密前先丢弃 1024 字节（RC4-drop/1024）
 * - decrypt():            RC4 解密 → 优先 UTF-8，失败则按 gzip 解压
 *
 * 任何一处与上游不一致都会导致云端返回鉴权失败，改动需与上游对照回归。
 */
object MiCrypto {

    private const val RC4_DROP = 1024

    /** 生成请求 nonce。 */
    fun genNonce(): String {
        val buf = ByteArray(16)
        for (i in 0 until 8) buf[i] = Random.nextInt(256).toByte()
        val minutes = System.currentTimeMillis() / 60_000L
        // python: part2.to_bytes(((part2.bit_length()+7)//8), "big") —— 最小大端、无符号
        var len = 0
        var m = minutes
        while (m > 0) {
            len++
            m = m ushr 8
        }
        for (i in 0 until len) {
            buf[7 + len - i] = ((minutes ushr (8 * i)) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(buf.copyOf(8 + len))
    }

    /** SHA256(ssecurity ∥ nonce) */
    fun signedNonce(ssecurity: String, nonce: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(Base64.getDecoder().decode(ssecurity))
        md.update(Base64.getDecoder().decode(nonce))
        return Base64.getEncoder().encodeToString(md.digest())
    }

    /** 请求签名：METHOD&uri&k1=v1&k2=v2&signedNonce 的 SHA1。 */
    fun signature(uri: String, method: String, signedNonce: String, params: Map<String, String>): String {
        val sb = StringBuilder(method.uppercase()).append('&').append(uri)
        for ((k, v) in params) {
            sb.append('&').append(k).append('=').append(v)
        }
        sb.append('&').append(signedNonce)
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(sb.toString().toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** 组装加密后的表单参数（含 rc4_hash__ / signature / ssecurity / _nonce）。 */
    fun generateEncParams(
        uri: String,
        method: String,
        signedNonce: String,
        nonce: String,
        plain: Map<String, String>,
        ssecurity: String,
    ): Map<String, String> {
        val params = LinkedHashMap(plain)
        params["rc4_hash__"] = signature(uri, method, signedNonce, params)
        val encrypted = LinkedHashMap<String, String>(params.size + 3)
        for ((k, v) in params) {
            encrypted[k] = encryptRc4(signedNonce, v)
        }
        encrypted["signature"] = signature(uri, method, signedNonce, encrypted)
        encrypted["ssecurity"] = ssecurity
        encrypted["_nonce"] = nonce
        return encrypted
    }

    /** RC4-drop1024 加密，结果 base64。 */
    fun encryptRc4(passwordB64: String, payload: String): String {
        val key = Base64.getDecoder().decode(passwordB64)
        val out = rc4(key, payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(out)
    }

    /** RC4 解密响应体（payload 为 base64 密文），必要时按 gzip 解压。 */
    fun decrypt(ssecurity: String, nonce: String, payload: String): String {
        val key = Base64.getDecoder().decode(signedNonce(ssecurity, nonce))
        val cipher = Base64.getDecoder().decode(payload)
        val plain = rc4(key, cipher)
        decodeUtf8Strict(plain)?.let { return it }
        // python: 解码失败时按 GzipFile 再试一次（云端对大响应会 gzip）
        return try {
            String(GZIPInputStream(ByteArrayInputStream(plain)).readBytes(), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("响应解密失败", e)
        }
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * RC4 流密码，前 [drop] 个密钥流字节丢弃不输出（RC4-drop/1024）。
     * 加密与解密共用：对称密码，XOR 同样作用于密文。
     */
    private fun rc4(key: ByteArray, data: ByteArray, drop: Int = RC4_DROP): ByteArray {
        require(key.isNotEmpty()) { "RC4 密钥为空" }
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.size]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var i = 0
        var k = 0
        var generated = 0
        var written = 0
        val total = drop + data.size
        while (generated < total) {
            i = (i + 1) and 0xFF
            k = (k + s[i]) and 0xFF
            val t = s[i]; s[i] = s[k]; s[k] = t
            val rnd = s[(s[i] + s[k]) and 0xFF]
            if (generated >= drop) {
                out[written++] = ((data[generated - drop].toInt() and 0xFF) xor rnd).toByte()
            }
            generated++
        }
        return out
    }
}
