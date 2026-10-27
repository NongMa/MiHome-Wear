package com.mijia.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.mijia.wear.ui.Mj
import com.mijia.wear.vm.AppViewModel
import kotlinx.coroutines.delay

/**
 * 摄像头实时视频页。
 *
 * 流地址由 [AppViewModel.startCameraStream] 通过 miot-spec 的
 * `start-rtsp-stream`（首选）/ `start-hls-stream` 取回，
 * 这里交给 ExoPlayer 播放；离开页面时释放播放器并让摄像头停止推流。
 */
@Composable
fun VideoScreen(vm: AppViewModel, did: String) {
    val url by vm.streamUrl.collectAsState()
    val loading by vm.streamLoading.collectAsState()
    val error by vm.streamError.collectAsState()

    LaunchedEffect(did) { vm.startCameraStream(did) }
    // 离开页面（手势返回 / 系统返回）时收尾
    DisposableEffect(Unit) { onDispose { vm.stopCameraStream() } }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            url != null -> Player(url!!)
            error != null -> ErrorView(error!!) { vm.startCameraStream(did) }
            else -> LoadingView(loading)
        }
    }
}

@Composable
private fun LoadingView(loading: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.35f))
        Text(
            text = if (loading) "正在请求视频流…" else "准备中…",
            fontSize = 12.sp,
            color = Mj.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = Mj.Danger,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        ) {
            Text(text = "重试", fontSize = 14.sp)
        }
    }
}

@Composable
private fun Player(url: String) {
    val context = LocalContext.current

    // url 变化时重建播放器；旧实例由下方 DisposableEffect 释放
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(url) { onDispose { player.release() } }

    // 起播后短暂提示操作方式，随后隐藏，避免遮挡画面
    var hintVisible by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(url) {
        hintVisible = true
        delay(3500)
        hintVisible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (hintVisible) {
            Text(
                text = "左滑返回 · 画面来自摄像头实时流",
                fontSize = 11.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .background(Mj.SurfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
