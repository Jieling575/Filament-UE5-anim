package com.example.myapplication.combo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboStateMachineTest {

    // 所有动画时长都取 1 秒，进度 = 时间，方便读断言
    private val durations = Move.entries.associateWith { 1f }

    // 2 的幂次步长，浮点累加没有误差
    private val step = 1f / 64f

    private val transitions = mutableListOf<Pair<Move, Move>>()

    private fun machine(cancelPoint: Float = ComboStateMachine.DEFAULT_CANCEL_POINT) =
        ComboStateMachine(durations, cancelPoint) { from, _, to -> transitions += from to to }

    /** 以 [step] 为帧步长推进，直到当前动作的播放时间达到 [time]（或发生了切换）。 */
    private fun ComboStateMachine.playUntil(time: Float) {
        val move = currentMove
        while (currentMove == move && currentTime < time) update(step)
    }

    @Test
    fun startsInIdleAndLoops() {
        val sm = machine()
        assertEquals(Move.IDLE, sm.currentMove)
        repeat(64 * 3 + 16) { sm.update(step) }
        assertEquals(Move.IDLE, sm.currentMove)
        assertEquals(0.25f, sm.progress, 1e-6f)
    }

    @Test
    fun attackInIdleStartsCombo1Immediately() {
        val sm = machine()
        sm.update(0.5f)
        sm.onAttack()
        assertEquals(Move.COMBO1, sm.currentMove)
        assertEquals(0f, sm.currentTime)
        assertFalse(sm.pendingInput)
    }

    @Test
    fun withoutInputComboPlaysToEndThenReturnsToIdle() {
        val sm = machine()
        sm.onAttack()
        sm.playUntil(63f / 64f)
        assertEquals(Move.COMBO1, sm.currentMove)
        assertTrue(sm.isInRecovery)
        sm.playUntil(1f)
        assertEquals(Move.IDLE, sm.currentMove)
    }

    @Test
    fun inputDuringWindUpIsBufferedAndCancelsAtCancelPoint() {
        val sm = machine()
        sm.onAttack()
        sm.playUntil(0.3f)
        sm.onAttack()
        // 发力段内点击只记录缓冲，不打断
        assertEquals(Move.COMBO1, sm.currentMove)
        assertTrue(sm.pendingInput)

        sm.playUntil(0.79f)
        assertEquals(Move.COMBO1, sm.currentMove)

        // 越过 80% 的那一帧切到 combo2，跳过收招段
        sm.playUntil(0.81f)
        assertEquals(Move.COMBO2, sm.currentMove)
        assertFalse(sm.pendingInput)
    }

    @Test
    fun inputDuringRecoveryDoesNotInterruptAndChainsAtEnd() {
        val sm = machine()
        sm.onAttack()
        sm.playUntil(0.9f)
        assertTrue(sm.isInRecovery)
        sm.onAttack()
        assertTrue(sm.pendingInput)

        // 收招段里的点击不打断收招
        sm.playUntil(63f / 64f)
        assertEquals(Move.COMBO1, sm.currentMove)

        sm.playUntil(1f)
        assertEquals(Move.COMBO2, sm.currentMove)
        assertFalse(sm.pendingInput)
    }

    @Test
    fun fullChainLoopsFromCombo3BackToCombo1() {
        val sm = machine()
        sm.onAttack()
        repeat(3) {
            sm.onAttack()
            sm.playUntil(1f)
        }
        assertEquals(
            listOf(
                Move.IDLE to Move.COMBO1,
                Move.COMBO1 to Move.COMBO2,
                Move.COMBO2 to Move.COMBO3,
                Move.COMBO3 to Move.COMBO1,
            ),
            transitions,
        )
    }

    @Test
    fun bufferIsClearedAfterEachSwitch() {
        val sm = machine()
        sm.onAttack()
        sm.onAttack()
        sm.playUntil(1f)
        assertEquals(Move.COMBO2, sm.currentMove)
        // combo2 期间没有新输入：播完回到 idle，不会沿用 combo1 时的缓冲
        sm.playUntil(1f)
        assertEquals(Move.IDLE, sm.currentMove)
    }

    @Test
    fun attackAfterReturningToIdleStartsFreshFromCombo1() {
        val sm = machine()
        sm.onAttack()
        sm.onAttack()
        sm.playUntil(1f)
        sm.playUntil(1f)
        assertEquals(Move.IDLE, sm.currentMove)
        sm.onAttack()
        assertEquals(Move.COMBO1, sm.currentMove)
    }

    @Test
    fun cancelPointIsConfigurable() {
        val sm = machine(cancelPoint = 0.5f)
        sm.onAttack()
        sm.onAttack()
        sm.playUntil(31f / 64f)
        assertEquals(Move.COMBO1, sm.currentMove)
        sm.playUntil(0.51f)
        assertEquals(Move.COMBO2, sm.currentMove)
    }

    @Test
    fun largeFrameStepCrossingBothCheckpointsStillCancelsAtCancelPoint() {
        val sm = machine()
        sm.onAttack()
        sm.onAttack()
        sm.update(0.5f)
        // 一帧从 50% 跳到 150%：先命中取消点，切到下一段
        sm.update(1f)
        assertEquals(Move.COMBO2, sm.currentMove)
    }

    @Test
    fun reportsTimeOfPreviousMoveOnSwitch() {
        var reportedTime = -1f
        val sm = ComboStateMachine(durations) { _, fromTime, _ -> reportedTime = fromTime }
        sm.onAttack()
        sm.onAttack()
        sm.playUntil(1f)
        assertEquals(Move.COMBO2, sm.currentMove)
        assertEquals(0.8125f, reportedTime, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCancelPointOutOfRange() {
        machine(cancelPoint = 1.2f)
    }
}
