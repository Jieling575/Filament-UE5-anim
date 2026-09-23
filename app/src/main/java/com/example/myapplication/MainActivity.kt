package com.example.myapplication

import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.filament.CharacterScene
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var characterScene: CharacterScene

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        characterScene = CharacterScene(this, "models/character.glb")
        setContent {
            MyApplicationTheme {
                ComboScreen(characterScene)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        characterScene.resume()
    }

    override fun onPause() {
        super.onPause()
        characterScene.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        characterScene.destroy()
    }
}

@Composable
fun ComboScreen(characterScene: CharacterScene) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Filament 渲染到 SurfaceView 上，通过 AndroidView 嵌进 Compose
        AndroidView(
            factory = { context -> SurfaceView(context).also { characterScene.attachTo(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        // 唯一的输入：模拟游戏里的攻击键
        Button(
            onClick = characterScene::attack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .size(width = 160.dp, height = 56.dp),
        ) {
            Text(text = stringResource(R.string.attack), fontSize = 20.sp)
        }
    }
}
