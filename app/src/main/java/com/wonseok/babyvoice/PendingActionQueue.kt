package com.wonseok.babyvoice

import java.util.concurrent.ConcurrentLinkedQueue

sealed class ActionStep {
    data class ClickText(val text: String) : ActionStep()
    data class InputNumber(val value: String) : ActionStep()
    object ClickNumericField : ActionStep()
    data class ClickMostRecentEntry(val category: String) : ActionStep()
    object DismissKeyboard : ActionStep()
}

object PendingActionQueue {
    private val queue = ConcurrentLinkedQueue<ActionStep>()

    fun push(step: ActionStep) {
        queue.add(step)
    }

    fun poll(): ActionStep? = queue.poll()

    fun hasNext(): Boolean = queue.isNotEmpty()

    fun clear() = queue.clear()
}
