package com.example.myapplication.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.filament.CharacterScene

private const val MODEL_PATH = "models/character.glb"

/**
 * 创建一个随当前界面存在的 [CharacterScene]：进入界面时创建，离开时销毁，
 * 期间跟随 Activity 的 onResume / onPause 开始、停止逐帧渲染。
 */
@Composable
fun rememberCharacterScene(
    transparentBackground: Boolean = false,
    hiddenUntilPlaced: Boolean = false,
): CharacterScene {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scene = remember { CharacterScene(context, MODEL_PATH, transparentBackground, hiddenUntilPlaced) }
    DisposableEffect(lifecycle, scene) {
        // addObserver 会补发到当前状态为止的事件，已处于 RESUMED 时会立即收到 ON_RESUME
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scene.resume()
                Lifecycle.Event.ON_PAUSE -> scene.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            scene.destroy()
        }
    }
    return scene
}
