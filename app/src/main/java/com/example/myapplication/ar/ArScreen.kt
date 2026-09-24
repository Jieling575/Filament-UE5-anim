package com.example.myapplication.ar

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.R
import com.example.myapplication.ui.AttackButton
import com.example.myapplication.ui.rememberCharacterScene

/**
 * AR 模式：摄像头实时画面作为背景，Filament 渲染的角色叠在上面。
 * 层级从下到上：PreviewView 的 SurfaceView → Filament 的 SurfaceView（MediaOverlay、透明）→ Compose 控件。
 *
 * 流程：进入时显示"点击屏幕放置角色"提示；首次点击后角色弹出在点击位置，提示消失、攻击按钮出现。
 * 之后每次点击都会把角色重新放到点击位置并重置连招，提示不再出现，攻击按钮保持显示。
 */
@Composable
fun ArScreen(onBack: () -> Unit) {
    CameraPermissionGate(onBack = onBack) {
        var cameraFailed by remember { mutableStateOf(false) }
        val characterScene = rememberCharacterScene(transparentBackground = true, hiddenUntilPlaced = true)
        // 与 characterScene 同生命周期：离开 AR 模式再进来时重新从提示开始
        var hasPlacedOnce by remember(characterScene) { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreview(onError = { cameraFailed = true }, modifier = Modifier.fillMaxSize())
            AndroidView(
                factory = { context -> SurfaceView(context).also { characterScene.attachTo(it) } },
                modifier = Modifier.fillMaxSize(),
            )
            // 盖在 SurfaceView 上面接收点击：SurfaceView 自己不处理触摸，挂在这一层最可靠。
            // 攻击按钮在这一层之上，点按钮不会触发重新放置
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(characterScene) {
                        // 这一层和 SurfaceView 同样铺满，点击坐标即渲染区域内的像素坐标
                        detectTapGestures { offset ->
                            if (characterScene.place(offset.x, offset.y)) hasPlacedOnce = true
                        }
                    },
            )

            if (!hasPlacedOnce && !cameraFailed) {
                Text(
                    text = stringResource(R.string.ar_tap_to_place),
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            if (hasPlacedOnce) {
                AttackButton(
                    onClick = characterScene::attack,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
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
