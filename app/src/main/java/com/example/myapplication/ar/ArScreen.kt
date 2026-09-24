package com.example.myapplication.ar

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.R
import com.example.myapplication.ui.rememberCharacterScene

/**
 * AR 模式：摄像头实时画面作为背景，Filament 渲染的角色叠在上面。
 * 层级从下到上：PreviewView 的 SurfaceView → Filament 的 SurfaceView（MediaOverlay、透明）→ Compose 控件。
 */
@Composable
fun ArScreen(onBack: () -> Unit) {
    CameraPermissionGate(onBack = onBack) {
        var cameraFailed by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreview(onError = { cameraFailed = true }, modifier = Modifier.fillMaxSize())
            val characterScene = rememberCharacterScene(transparentBackground = true)
            AndroidView(
                factory = { context -> SurfaceView(context).also { characterScene.attachTo(it) } },
                modifier = Modifier.fillMaxSize(),
            )
            if (cameraFailed) {
                Text(
                    text = stringResource(R.string.camera_open_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
