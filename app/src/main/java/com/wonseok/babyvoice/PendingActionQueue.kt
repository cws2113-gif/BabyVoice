package com.wonseok.babyvoice

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 실행할 단계를 표현. 필요에 따라 종류를 늘려가면 됨.
 */
sealed class ActionStep {
    data class ClickText(val text: String) : ActionStep()      // 화면에서 이 텍스트를 가진 요소를 찾아 클릭
    data class InputNumber(val value: String) : ActionStep()   // 포커스된(혹은 첫 번째) 숫자 입력창에 값 입력
    object ClickNumericField : ActionStep()                    // 숫자(ml 등)가 표시된 필드를 탭해서 키패드 열기
    object ClickMostRecentEntry : ActionStep()                 // 방금 자동 기록된 리스트 최상단 항목을 열기 (현재 시각 텍스트로 탐색)
}

/**
 * 프로세스 내 간단한 큐. MainActivity가 push, AccessibilityService가 poll.
 * 앱이 하나의 프로세스에서 돌기 때문에 static 객체로 충분함.
 */
object PendingActionQueue {
    private val queue = ConcurrentLinkedQueue<ActionStep>()

    fun push(step: ActionStep) {
        queue.add(step)
    }

    fun poll(): ActionStep? = queue.poll()

    fun hasNext(): Boolean = queue.isNotEmpty()

    fun clear() = queue.clear()
}
