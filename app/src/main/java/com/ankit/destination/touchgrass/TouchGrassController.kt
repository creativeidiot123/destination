package com.ankit.destination.touchgrass

import android.content.Context
import com.ankit.destination.policy.PolicyStore
import java.time.LocalDate

data class TouchGrassUpdate(
    val unlockCount: Int,
    val threshold: Int,
    val breakUntilMs: Long?,
    val breakWasActive: Boolean,
    val breakIsActive: Boolean,
    val breakStateChanged: Boolean
)

class TouchGrassController(context: Context) {
    private val store = PolicyStore(context.applicationContext)

    fun onUserUnlock(nowMs: Long = System.currentTimeMillis()): TouchGrassUpdate {
        val dayKey = LocalDate.now().toString()
        val breakWasActive = (store.getTouchGrassBreakUntilMs() ?: -1L) > nowMs
        val unlockCount = store.incrementUnlockCount(dayKey)
        val threshold = store.getTouchGrassThreshold()
        val breakMinutes = store.getTouchGrassBreakMinutes()

        var breakUntil = store.getTouchGrassBreakUntilMs()
        if (unlockCount >= threshold) {
            val candidateUntil = nowMs + breakMinutes * 60_000L
            if (breakUntil == null || candidateUntil > breakUntil) {
                breakUntil = candidateUntil
                store.setTouchGrassBreakUntilMs(breakUntil)
            }
        }
        val breakIsActive = (breakUntil ?: -1L) > nowMs
        return TouchGrassUpdate(
            unlockCount = unlockCount,
            threshold = threshold,
            breakUntilMs = breakUntil,
            breakWasActive = breakWasActive,
            breakIsActive = breakIsActive,
            breakStateChanged = breakWasActive != breakIsActive
        )
    }
}
