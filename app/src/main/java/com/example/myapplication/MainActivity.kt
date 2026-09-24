package com.example.myapplication

import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.ar.ArScreen
import com.example.myapplication.ui.AttackButton
import com.example.myapplication.ui.ModeSelectScreen
import com.example.myapplication.ui.rememberCharacterScene
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                App()
            }
        }
    }
}

private enum class Screen { MODE_SELECT, NORMAL_3D, AR }

@Composable
private fun App() {
    var screen by rememberSaveable { mutableStateOf(Screen.MODE_SELECT) }
    when (screen) {
        Screen.MODE_SELECT -> ModeSelectScreen(
            onNormal3d = { screen = Screen.NORMAL_3D },
            onAr = { screen = Screen.AR },
        )
        Screen.NORMAL_3D -> {
            BackHandler { screen = Screen.MODE_SELECT }
            ComboScreen()
        }
        Screen.AR -> {
            BackHandler { screen = Screen.MODE_SELECT }
            ArScreen(onBack = { screen = Screen.MODE_SELECT })
        }
    }
}

/** 普通 3D 模式：纯色背景上的角色 + 攻击按钮。 */
@Composable
fun ComboScreen() {
    val characterScene = rememberCharacterScene()
    Box(modifier = Modifier.fillMaxSize()) {
        // Filament 渲染到 SurfaceView 上，通过 AndroidView 嵌进 Compose
        AndroidView(
            factory = { context -> SurfaceView(context).also { characterScene.attachTo(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        // 唯一的输入：模拟游戏里的攻击键
        AttackButton(
            onClick = characterScene::attack,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
