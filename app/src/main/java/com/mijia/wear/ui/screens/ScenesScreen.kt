package com.mijia.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.mijia.wear.ui.Mj
import com.mijia.wear.ui.ScreenHeader
import com.mijia.wear.ui.mjChipColors
import com.mijia.wear.vm.AppViewModel

/** 场景列表：米家 APP「智能 → 手动控制」里创建的场景，点按即执行。 */
@Composable
fun ScenesScreen(vm: AppViewModel, nav: NavHostController) {
    val scenes by vm.scenes.collectAsState()
    val loading by vm.loading.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ScreenHeader("场景") }

        if (scenes.isEmpty()) {
            item { Text("暂无手动场景", color = Mj.TextDim) }
        }

        items(scenes.size) { index ->
            val scene = scenes[index]
            Chip(
                label = { Text(scene.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = {
                    Text(
                        text = scene.homeName.ifBlank { "点按执行" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                onClick = { vm.runScene(scene) },
                colors = mjChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Chip(
                label = { Text("刷新", color = Mj.AccentSoft) },
                onClick = { vm.loadScenes() },
                enabled = !loading,
                colors = mjChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
