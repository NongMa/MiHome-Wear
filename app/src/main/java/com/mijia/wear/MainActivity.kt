package com.mijia.wear

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mijia.wear.ui.MiHomeApp
import com.mijia.wear.vm.AppViewModel

/** 米家手表版入口。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 控制页需要停留阅读，避免手表按时自动息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val vm: AppViewModel = viewModel()
            MiHomeApp(vm)
        }
    }

    /** 排障：确认边缘返回手势是否被系统转成了返回键送达应用。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            Log.d("TouchDbg", "BACK key received action=${event.action}")
        }
        return super.dispatchKeyEvent(event)
    }
}
