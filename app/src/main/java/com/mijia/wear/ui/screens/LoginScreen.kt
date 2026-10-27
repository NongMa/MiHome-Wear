package com.mijia.wear.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.ui.mjChipColors
import com.mijia.wear.ui.renderQr
import com.mijia.wear.vm.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫码登录页：手表上渲染二维码 → 米家 APP 扫码 → 长轮询完成登录。
 * 手表屏幕小，二维码按短边取 160dp 左右并居中，保证可被手机识别。
 */
@Composable
fun LoginScreen(vm: AppViewModel) {
    val qr by vm.qr.collectAsState()
    val loggingIn by vm.loggingIn.collectAsState()
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    // 首次进入自动申请二维码
    LaunchedEffect(Unit) { vm.beginLogin() }

    LaunchedEffect(qr) {
        val url = qr?.loginUrl
        bitmap = if (url == null) {
            null
        } else {
            withContext(Dispatchers.Default) { renderQr(url, 512)?.asImageBitmap() }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ScreenHeader("米家登录") }

        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    // 白底卡片托住二维码：更醒目，也更容易被手机扫到
                    Box(
                        modifier = Modifier
                            .size(172.dp)
                            .background(Color.White, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(150.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                }
            }
        }

        item {
            Text(
                text = if (loggingIn) "正在获取二维码…" else "打开米家 APP 扫一扫",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }

        item {
            Text(
                text = "二维码超时后点下方刷新",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = Mj.TextDim,
            )
        }

        item {
            Chip(
                label = { Text("刷新二维码", color = Mj.AccentSoft) },
                onClick = { vm.beginLogin() },
                enabled = !loggingIn,
                colors = mjChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
