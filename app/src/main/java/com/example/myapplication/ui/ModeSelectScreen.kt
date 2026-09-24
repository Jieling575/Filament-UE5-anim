package com.example.myapplication.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R

/** 启动页：选择进入普通 3D 模式还是 AR 模式。 */
@Composable
fun ModeSelectScreen(onNormal3d: () -> Unit, onAr: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = onNormal3d, modifier = Modifier.size(width = 220.dp, height = 56.dp)) {
                Text(text = stringResource(R.string.mode_normal_3d), fontSize = 18.sp)
            }
            OutlinedButton(onClick = onAr, modifier = Modifier.size(width = 220.dp, height = 56.dp)) {
                Text(text = stringResource(R.string.mode_ar), fontSize = 18.sp)
            }
        }
    }
}
