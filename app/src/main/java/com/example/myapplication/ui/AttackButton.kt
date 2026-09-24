package com.example.myapplication.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R

/** 屏幕底部的"攻击"按钮，模拟游戏里的攻击键。普通 3D 模式和 AR 模式共用，由调用方负责对齐到底部居中。 */
@Composable
fun AttackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 32.dp)
            .size(width = 160.dp, height = 56.dp),
    ) {
        Text(text = stringResource(R.string.attack), fontSize = 20.sp)
    }
}
