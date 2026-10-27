package com.mijia.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.vm.AppViewModel
import kotlinx.coroutines.delay

private const val MAX_LEN = 120

/**
 * 文本指令输入页：拉起系统输入法（如搜狗输入法手表版）打字，
 * 发送后走 spec 的 `execute-text-directive` 下发给小爱音箱。
 *
 * 国行手表没有系统语音识别器（RECOGNIZE_SPEECH 无响应 Activity），
 * 因此文本输入是小爱指令的主要输入方式。
 */
@Composable
fun TextInputScreen(vm: AppViewModel, nav: NavHostController) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 进页面即聚焦并拉起输入法，省一次点按。
    // IME 窗口在首次 show 时往往还没绑定完成会被忽略，这里做短重试。
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        repeat(3) {
            delay(300)
            keyboard?.show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        ScreenHeader("文本指令")

        BasicTextField(
            value = text,
            onValueChange = { text = it.take(MAX_LEN) },
            modifier = Modifier
                .focusRequester(focusRequester)
                .fillMaxWidth()
                .background(
                    color = Mj.Surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                )
                .border(1.dp, Mj.Stroke, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(10.dp)
                .height(64.dp),
            textStyle = TextStyle(
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Start,
            ),
            cursorBrush = SolidColor(Mj.AccentSoft),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text = "对小爱说点什么…",
                        color = Mj.TextDim,
                        fontSize = 13.sp,
                    )
                }
                inner()
            },
        )

        Text(
            text = if (text.isEmpty()) "最多 $MAX_LEN 字" else "${text.length} / $MAX_LEN",
            color = Mj.TextDim,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            textAlign = TextAlign.End,
        )

        Button(
            onClick = {
                val cmd = text.trim()
                if (cmd.isEmpty()) return@Button
                keyboard?.hide()
                vm.sendVoice(cmd)
                nav.popBackStack()
            },
            enabled = text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Text(text = "发送给小爱", fontSize = 14.sp)
        }
    }
}
