package com.example.myapplication.combo

/**
 * 连招状态机（取消窗口 Cancel Window 方案），不依赖 Filament，只处理时间和输入。
 *
 * 每段连招动画按 [cancelPoint] 分成两部分：
 * - 发力段 [0, cancelPoint)：出招的主体动作
 * - 收招段 [cancelPoint, 1]：收势回到自然站姿
 *
 * 预输入缓冲：连招播放期间的任何一次点击都不会立即打断动画，只是把 [pendingInput] 置为 true，
 * 由 [update] 在两个检查点消费：
 * 1. 进度到达 cancelPoint（取消点）：有缓冲 → 跳过收招段，直接切到下一段；
 *    没有缓冲 → 继续播收招段。
 * 2. 进度到达 100%：有缓冲（在收招段里点的） → 切到下一段；没有 → 回到 idle。
 *
 * 下一段的顺序为 combo1 → combo2 → combo3 → combo1 循环。idle 时点击立即起手 combo1。
 *
 * 用法：每帧调用 [update]，点击时调用 [onAttack]，两者必须在同一线程（主线程）。
 *
 * @param durations 每个动作对应动画的时长（秒）
 * @param cancelPoint 取消点在动画中的进度，取值 (0, 1]，估算值，按实际手感微调
 * @param onMoveChanged 切换动作时回调：(上一个动作, 上一个动作切走时的播放时间, 新动作)，用于做 crossfade
 */
class ComboStateMachine(
    private val durations: Map<Move, Float>,
    val cancelPoint: Float = DEFAULT_CANCEL_POINT,
    private val onMoveChanged: (from: Move, fromTime: Float, to: Move) -> Unit = { _, _, _ -> },
) {
    companion object {
        /** 发力段/收招段分界的默认值：动画进度 80%。 */
        const val DEFAULT_CANCEL_POINT = 0.8f

        private fun nextComboAfter(move: Move): Move = when (move) {
            Move.IDLE, Move.COMBO3 -> Move.COMBO1
            Move.COMBO1 -> Move.COMBO2
            Move.COMBO2 -> Move.COMBO3
        }
    }

    init {
        require(cancelPoint > 0f && cancelPoint <= 1f) { "cancelPoint 必须在 (0, 1] 内，当前: $cancelPoint" }
        Move.entries.forEach { move ->
            val duration = requireNotNull(durations[move]) { "缺少动作 $move 的时长" }
            require(duration > 0f) { "动作 $move 的时长必须大于 0，当前: $duration" }
        }
    }

    /** 当前正在播放的动作。 */
    var currentMove: Move = Move.IDLE
        private set

    /** 当前动作已播放的时间（秒），即传给 Animator.applyAnimation 的时间。 */
    var currentTime: Float = 0f
        private set

    /** 预输入缓冲：本段连招播放期间是否有过点击，还没被消费。 */
    var pendingInput: Boolean = false
        private set

    /** 本段是否已经过了取消点检查，保证每段只在取消点检查一次。 */
    private var cancelPointChecked = false

    /** 当前动作的播放进度，0~1。idle 循环播放，进度每圈从 0 重新开始。 */
    val progress: Float
        get() = (currentTime / durationOf(currentMove)).coerceIn(0f, 1f)

    /** 当前连招是否已经进入收招段（idle 时恒为 false）。 */
    val isInRecovery: Boolean
        get() = currentMove != Move.IDLE && progress >= cancelPoint

    /** 玩家点击"攻击"。 */
    fun onAttack() {
        if (currentMove == Move.IDLE) {
            // idle 下的点击是全新起手，立即从 combo1 开始
            startMove(Move.COMBO1)
        } else {
            // 连招播放中：不论在发力段还是收招段，都只记录缓冲，不打断当前动画
            pendingInput = true
        }
    }

    /** 每帧调用一次，推进 [deltaSeconds] 秒并在检查点处理状态切换。 */
    fun update(deltaSeconds: Float) {
        currentTime += deltaSeconds
        val duration = durationOf(currentMove)

        if (currentMove == Move.IDLE) {
            currentTime %= duration
            return
        }

        // 检查点 1：取消点。一帧的步长可能同时越过取消点和 100%，所以先查取消点再查结尾
        if (!cancelPointChecked && currentTime >= duration * cancelPoint) {
            cancelPointChecked = true
            if (pendingInput) {
                // 发力段内有预输入：跳过收招段，直接出下一招
                startMove(nextComboAfter(currentMove))
                return
            }
            // 没有预输入：继续播收招段，此后的点击留到 100% 时再处理
        }

        // 检查点 2：动画播完
        if (currentTime >= duration) {
            startMove(if (pendingInput) nextComboAfter(currentMove) else Move.IDLE)
        }
    }

    private fun startMove(move: Move) {
        val from = currentMove
        val fromTime = currentTime.coerceAtMost(durationOf(from))
        currentMove = move
        currentTime = 0f
        // 每段连招的缓冲只对这一段有效，切换后清空；回到 idle 后下一次点击是全新起手
        pendingInput = false
        cancelPointChecked = false
        onMoveChanged(from, fromTime, move)
    }

    private fun durationOf(move: Move): Float = durations.getValue(move)
}
