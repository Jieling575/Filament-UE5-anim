package com.example.myapplication.ar

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val TAG = "CameraPreview"

/**
 * 后置摄像头的实时预览。onResume 时打开摄像头，onPause 时释放，
 * 应用退到后台（包括多窗口下失去焦点）时不会继续占用摄像头。
 */
@Composable
fun CameraPreview(onError: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // 必须用 SurfaceView 实现：TextureView 属于应用窗口，会盖住叠在上面的 Filament SurfaceView
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val controller = CameraPreviewController(context, lifecycleOwner, previewView, onError)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.start()
                Lifecycle.Event.ON_PAUSE -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * 管理 CameraX Preview 用例的绑定。ProcessCameraProvider 是异步获取的，
 * 在拿到之前调用的 start / stop 只记录期望状态，拿到后再按期望状态绑定。
 *
 * 绑定用的 lifecycleOwner 只是满足 CameraX 接口要求，真正的开关由 start / stop 控制：
 * CameraX 自身按 onStart / onStop 开关摄像头，这里提前到 onResume / onPause。
 */
private class CameraPreviewController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onError: () -> Unit,
) {
    private val preview = Preview.Builder().build()
    private var provider: ProcessCameraProvider? = null
    private var wantRunning = false

    init {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "获取 ProcessCameraProvider 失败", e)
                onError()
                return@addListener
            }
            if (wantRunning) bind()
        }, ContextCompat.getMainExecutor(context))
    }

    fun start() {
        wantRunning = true
        bind()
    }

    fun stop() {
        wantRunning = false
        provider?.unbind(preview)
    }

    private fun bind() {
        val provider = provider ?: return
        try {
            provider.unbind(preview)
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        } catch (e: Exception) {
            // 没有后置摄像头、摄像头被其他应用占用等
            Log.e(TAG, "绑定摄像头预览失败", e)
            onError()
        }
    }
}
