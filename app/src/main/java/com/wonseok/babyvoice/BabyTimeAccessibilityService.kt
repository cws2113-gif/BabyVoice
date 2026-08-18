package com.wonseok.babyvoice

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 베이비타임 화면이 바뀔 때마다 호출됨.
 * PendingActionQueue 에 쌓인 다음 액션을 시도하고,
 * 성공하면 다음 액션으로 넘어가고 실패하면 잠시 후 재시도한다.
 */
class BabyTimeAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 10 // 약 5초 (500ms * 10) 동안 요소를 못 찾으면 포기

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!PendingActionQueue.hasNext()) return
        // 화면이 바뀔 때마다 살짝 지연을 주고 시도 (렌더링 완료 대기)
        handler.postDelayed({ tryExecuteNext() }, 300)
    }

    private fun tryExecuteNext() {
        val step = PendingActionQueue.poll() ?: return
        val root = rootInActiveWindow

        if (root == null) {
            retryLater(step)
            return
        }

        val success = when (step) {
            is ActionStep.ClickText -> clickNodeWithText(root, step.text)
            is ActionStep.InputNumber -> inputNumberIntoFirstEditText(root, step.value)
            is ActionStep.ClickNumericField -> clickFirstNumericField(root)
            is ActionStep.ClickMostRecentEntry -> clickMostRecentEntry(root)
        }

        if (!success) {
            retryLater(step)
        } else {
            retryCount = 0
            Log.d("BabyVoice", "단계 성공: $step")
        }
    }

    private fun retryLater(step: ActionStep) {
        retryCount++
        if (retryCount > maxRetries) {
            Log.w("BabyVoice", "요소를 찾지 못해 포기: $step")
            retryCount = 0
            PendingActionQueue.clear() // 실패 시 남은 큐도 비워서 엉뚱한 곳을 누르지 않도록
            return
        }
        // 실패한 단계를 다시 큐 맨 앞에 넣고 재시도
        PendingActionQueue.push(step)
        handler.postDelayed({ tryExecuteNext() }, 500)
    }

    /** 화면에서 지정한 텍스트를 포함하는 클릭 가능한 노드를 찾아 클릭 */
    private fun clickNodeWithText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (node in nodes) {
            val clickable = findClickableSelfOrParent(node)
            if (clickable != null) {
                return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    /** 노드 자신 또는 조상 중 클릭 가능한 것을 찾음 (아이콘 텍스트가 버튼 안에 있는 경우 대비) */
    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * 분유/이유식 화면의 "195 ml" 같은 숫자 표시 영역을 찾아 탭한다.
     * 실기기 확인 결과 이 영역을 탭하면 키패드가 뜨면서 직접 입력 가능한 상태가 됨.
     * 텍스트가 순수 숫자(공백 포함 가능)인 클릭 가능 노드를 화면에서 재귀 탐색.
     */
    private fun clickFirstNumericField(root: AccessibilityNodeInfo): Boolean {
        val numericRegex = Regex("""^\d+\s*(ml)?$""")
        val target = findNodeMatching(root) { node ->
            val t = node.text?.toString()?.trim() ?: return@findNodeMatching false
            numericRegex.matches(t)
        } ?: return false

        val clickable = findClickableSelfOrParent(target) ?: return false
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeMatching(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeMatching(child, predicate)
            if (result != null) return result
        }
        return null
    }

    /**
     * 아이콘 탭 직후 홈 화면 리스트 맨 위에 자동으로 생기는 "방금 기록된 항목"을 찾아 클릭한다.
     * 항목을 구분할 고유 텍스트가 없어서, 리스트에 표시되는 시각("08:38 PM" 형식)을
     * 현재 시각으로 계산해 찾는 방식을 쓴다. 분 단위가 막 바뀌는 경계에서는 실패할 수 있어
     * 재시도 로직(tryExecuteNext의 retryLater)이 다음 분으로 다시 시도해준다.
     */
    private fun clickMostRecentEntry(root: AccessibilityNodeInfo): Boolean {
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
        val timeText = timeFormat.format(Date())
        return clickNodeWithText(root, timeText)
    }

    /** 화면에서 첫 번째로 보이는 편집 가능한 입력창을 찾아 숫자를 입력 */
    private fun inputNumberIntoFirstEditText(root: AccessibilityNodeInfo, value: String): Boolean {
        val editable = findFirstEditable(root) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
        )
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() {
        Log.w("BabyVoice", "접근성 서비스 중단됨")
    }
}
