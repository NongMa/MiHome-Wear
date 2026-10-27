package com.mijia.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.mijia.wear.vm.AppViewModel
import com.mijia.wear.vm.Stage
import com.mijia.wear.ui.screens.DeviceScreen
import com.mijia.wear.ui.screens.DevicesScreen
import com.mijia.wear.ui.screens.HomeScreen
import com.mijia.wear.ui.screens.LoginScreen
import com.mijia.wear.ui.screens.ScenesScreen
import com.mijia.wear.ui.screens.TextInputScreen
import com.mijia.wear.ui.screens.VideoScreen
import com.mijia.wear.ui.screens.VoiceScreen
import kotlinx.coroutines.delay

/**
 * 应用外壳：按登录阶段切换「加载 / 扫码 / 主导航」，并在最上层叠加 Toast。
 */
@Composable
fun MiHomeApp(vm: AppViewModel) {
    val stage by vm.stage.collectAsState()
    val toast by vm.toast.collectAsState()

    MiHomeTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 排障用：记录指针手势的起点/终点/位移，tag=TouchDbg（不消费事件）
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var pressed = false
                        var moves = 0
                        var start = Offset.Zero
                        while (true) {
                            val ch = awaitPointerEvent(PointerEventPass.Initial)
                                .changes.firstOrNull() ?: continue
                            if (ch.pressed && !pressed) {
                                pressed = true
                                moves = 0
                                start = ch.position
                            } else if (ch.pressed) {
                                moves++
                            } else if (pressed) {
                                pressed = false
                                android.util.Log.d(
                                    "TouchDbg",
                                    "UP moves=$moves dx=${ch.position.x - start.x} " +
                                        "dy=${ch.position.y - start.y} " +
                                        "start=$start end=${ch.position}"
                                )
                            }
                        }
                    }
                },
        ) {
            when (stage) {
                Stage.CHECKING -> CheckingScreen()
                Stage.NEED_LOGIN -> LoginScreen(vm)
                Stage.LOGGED_IN -> AppNavHost(vm)
            }
            toast?.let { msg ->
                Toast(msg) { vm.clearToast() }
            }
        }
    }
}

@Composable
private fun CheckingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.4f))
            Text("正在检查登录状态…", modifier = Modifier.padding(top = 12.dp), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AppNavHost(vm: AppViewModel) {
    // Wear OS 专用控制器：会注册 WearNavigator，普通 rememberNavController 会崩溃
    val navController: NavHostController = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = Route.HOME.route,
        // 关闭 Wear 库自带的「任意位置拖拽返回」：
        // 1) 它会注册全屏 systemGestureExclusion，屏蔽系统左侧边缘返回（带箭头、可中断）；
        // 2) 需求是用系统手势返回。关闭后系统边缘手势在应用内恢复生效，
        //    系统返回通过 SwipeDismissableNavHost 内部的 BackHandler popBackStack。
        userSwipeEnabled = false,
    ) {
        composable(Route.HOME.route) { HomeScreen(vm, navController) }
        composable(Route.DEVICES.route) { DevicesScreen(vm, navController) }
        composable(
            route = Route.DEVICE.route,
            arguments = listOf(navArgument("did") { type = NavType.StringType }),
        ) { entry ->
            DeviceScreen(vm, entry.arguments?.getString("did").orEmpty(), navController)
        }
        composable(
            route = Route.VIDEO.route,
            arguments = listOf(navArgument("did") { type = NavType.StringType }),
        ) { entry ->
            VideoScreen(vm, entry.arguments?.getString("did").orEmpty())
        }
        composable(Route.SCENES.route) { ScenesScreen(vm, navController) }
        composable(Route.VOICE.route) { VoiceScreen(vm, navController) }
        composable(Route.TEXT_INPUT.route) { TextInputScreen(vm, navController) }
    }
}

/** 应用内路由。 */
enum class Route(val route: String) {
    HOME("home"),
    DEVICES("devices"),
    DEVICE("device/{did}"),
    VIDEO("video/{did}"),
    SCENES("scenes"),
    VOICE("voice"),
    TEXT_INPUT("text-input"),
    ;

    companion object {
        /** 构造设备详情路由。 */
        fun device(did: String) = "device/$did"

        /** 构造摄像头实时视频路由。 */
        fun video(did: String) = "video/$did"
    }}

@Composable
private fun Toast(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(2500)
        onDismiss()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = text,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 22.dp)
                .background(Mj.SurfaceHigh, RoundedCornerShape(50))
                .border(1.dp, Mj.Stroke, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 9.dp)
                .fillMaxWidth(),
            color = Mj.Text,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 通用的滚动列表外边距，让内容在圆屏上不贴边。 */
val ListPadding = PaddingValues(horizontal = 10.dp)

/** 通用页头：强调色小标题，居中、加粗，圆屏上更聚焦；可选副标题（房间等）。 */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    ListHeader {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                textAlign = TextAlign.Center,
                color = Mj.AccentSoft,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    textAlign = TextAlign.Center,
                    color = Mj.TextDim,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}
