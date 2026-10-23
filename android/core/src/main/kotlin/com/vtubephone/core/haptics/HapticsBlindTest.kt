package com.vtubephone.core.haptics

class HapticsBlindTest {
    private var expectedYes: Boolean? = null
    val awaitingAnswer: Boolean get() = expectedYes != null
    var correct = 0; private set
    var total = 0; private set
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    fun begin(expectedYes: Boolean): Boolean {
        if (awaitingAnswer) return false
        this.expectedYes = expectedYes
        return true
    }
    fun answer(yes: Boolean): Boolean {
        val expected = expectedYes ?: return false
        if (yes == expected) correct++
        total++; cancel(); return true
    }
    fun cancel() { expectedYes = null }
}
