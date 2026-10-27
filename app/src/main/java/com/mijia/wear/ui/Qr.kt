package com.mijia.wear.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 用 zxing 生成登录二维码位图（白底黑码，留足静区以便手机识别）。 */
fun renderQr(text: String, size: Int): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val w = matrix.width
    val h = matrix.height
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    for (x in 0 until w) {
        for (y in 0 until h) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    bmp
} catch (e: Exception) {
    null
}
