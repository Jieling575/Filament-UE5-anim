package com.example.myapplication.combo

/**
 * 角色的动作状态，每个状态对应 glb 里一段同名动画。
 * 动画索引在运行时按 [animationName] 查找，不依赖 glb 里的动画顺序。
 */
enum class Move(val animationName: String) {
    IDLE("idle"),
    COMBO1("combo1"),
    COMBO2("combo2"),
    COMBO3("combo3"),
}
