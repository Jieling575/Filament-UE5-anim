package com.example.myapplication.ar

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
import com.example.myapplication.R

/** AR 模式：摄像头实时画面作为背景。 */
@Composable
fun ArScreen(onBack: () -> Unit) {
    CameraPermissionGate(onBack = onBack) {
        var cameraFailed by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreview(onError = { cameraFailed = true }, modifier = Modifier.fillMaxSize())
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
