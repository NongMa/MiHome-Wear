package com.mijia.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.Route
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.ui.mjChipColors
import com.mijia.wear.vm.AppViewModel
import kotlinx.coroutines.launch

/** 主菜单：设备 / 场景 / 小爱 / 退出登录。 */
@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val devices by vm.devices.collectAsState()
    val scenes by vm.scenes.collectAsState()
    var showConfirmLogout by remember { mutableStateOf(false) }

    val mainListState = rememberScalingLazyListState()
    val mainFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // 当弹窗关闭（或初始进入）时，重新为主菜单获取表冠滚轮焦点
    LaunchedEffect(showConfirmLogout) {
        if (!showConfirmLogout) {
            mainFocusRequester.requestFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = mainListState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(mainFocusRequester)
                .onRotaryScrollEvent { e ->
                    coroutineScope.launch {
                        mainListState.scrollBy(e.verticalScrollPixels)
                    }
                    true
                }
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ScreenHeader("米家") }

            item {
                MenuChip(
                    label = "设备",
                    sub = if (devices.isEmpty()) "加载中" else "${devices.size} 台",
                    onClick = { nav.navigate(Route.DEVICES.route) },
                )
            }

            item {
                MenuChip(
                    label = "场景",
                    sub = if (scenes.isEmpty()) "暂无" else "${scenes.size} 个",
                    onClick = { nav.navigate(Route.SCENES.route) },
                )
            }

            item {
                MenuChip(
                    label = "小爱同学",
                    sub = "语音 / 文本指令",
                    onClick = { nav.navigate(Route.VOICE.route) },
                )
            }

            item {
                MenuChip(
                    label = "退出登录",
                    sub = "需要重新扫码",
                    danger = true,
                    onClick = { showConfirmLogout = true },
                )
            }
        }

        // 退出登录二次确认弹窗
        if (showConfirmLogout) {
            val dialogListState = rememberScalingLazyListState()
            val dialogFocusRequester = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                dialogFocusRequester.requestFocus()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                ScalingLazyColumn(
                    state = dialogListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(dialogFocusRequester)
                        .onRotaryScrollEvent { e ->
                            coroutineScope.launch {
                                dialogListState.scrollBy(e.verticalScrollPixels)
                            }
                            true
                        }
                        .focusable(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item { ScreenHeader(title = "确认退出？", subtitle = "退出后需要重新扫码登录") }

                    item {
                        MenuChip(
                            label = "确认退出",
                            sub = "清除当前登录凭据",
                            danger = true,
                            onClick = {
                                showConfirmLogout = false
                                vm.logout()
                            },
                        )
                    }

                    item {
                        MenuChip(
                            label = "取消",
                            sub = "返回主菜单",
                            onClick = { showConfirmLogout = false },
                        )
                    }
                }
            }
        }
    }
}

/** 主菜单条目：统一深色卡片样式，危险项用红色标题区分。 */
@Composable
private fun MenuChip(
    label: String,
    sub: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Chip(
        label = {
            Text(label, color = if (danger) Mj.Danger else Mj.Text, fontWeight = FontWeight.Medium)
        },
        secondaryLabel = { Text(sub, color = Mj.TextDim) },
        onClick = onClick,
        colors = mjChipColors(
            background = if (danger) Color(0xFF241A1A) else Mj.Surface,
            content = if (danger) Mj.Danger else Mj.Text,
            secondary = Mj.TextDim,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
