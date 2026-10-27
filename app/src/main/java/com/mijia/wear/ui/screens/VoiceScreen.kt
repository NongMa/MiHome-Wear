package com.mijia.wear.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.Route
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.ui.mjChipColors
import com.mijia.wear.vm.AppViewModel

/** 常用指令：直接发给小爱音箱执行（自然语言由小爱理解）。 */
private val PRESETS = listOf(
    "打开所有灯",
    "关闭所有灯",
    "打开空调",
    "关闭空调",
    "我回来了",
    "晚安",
)

/**
 * 小爱同学：选音箱 → 语音输入（系统识别器）或点常用指令，
 * 走 spec 的 `execute-text-directive` 动作下发。
 */
@Composable
fun VoiceScreen(vm: AppViewModel, nav: NavHostController) {
    val speakers by vm.speakers.collectAsState()
    val active by vm.activeSpeaker.collectAsState()
    val activeSpeaker = speakers.firstOrNull { it.did == active } ?: speakers.firstOrNull()

    // 国行手表通常没有系统语音识别器；只在设备真有识别器时才显示语音入口
    val context = LocalContext.current
    val hasSpeechRecognizer = remember(context) {
        context.packageManager.queryIntentActivities(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).isNotEmpty()
    }

    val recognizerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            vm.sendVoice(text)
        }
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            recognizerLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.showToast("手表不支持语音输入，请用下方常用指令")
        } catch (e: Exception) {
            vm.showToast("无法启动语音输入：${e.message}")
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ScreenHeader("小爱同学") }

        item {
            Chip(
                label = { Text("文本输入") },
                secondaryLabel = {
                    Text(
                        text = activeSpeaker?.name ?: "没有在线音箱",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                onClick = { nav.navigate(Route.TEXT_INPUT.route) },
                colors = mjChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (hasSpeechRecognizer) {
            item {
                Chip(
                    label = { Text("语音输入") },
                    secondaryLabel = {
                        Text(
                            text = activeSpeaker?.name ?: "没有在线音箱",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { startVoiceInput() },
                    enabled = activeSpeaker != null,
                    colors = mjChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (speakers.size > 1) {
            item {
                Chip(
                    label = { Text("切换音箱") },
                    secondaryLabel = { Text("当前：${activeSpeaker?.name ?: "自动"}") },
                    onClick = { vm.cycleSpeaker() },
                    colors = mjChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        items(PRESETS.size) { index ->
            val cmd = PRESETS[index]
            Chip(
                label = { Text(cmd, color = Mj.AccentSoft) },
                secondaryLabel = { Text("发送", color = Mj.TextDim) },
                onClick = { vm.sendVoice(cmd) },
                enabled = activeSpeaker != null,
                colors = mjChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
