package com.wonseok.babyvoice

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BabyTimeAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 10

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!PendingActionQueue.hasNext()) return
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
            is ActionStep.ClickMostRecentEntry -> clickSecondTopmostWithText(root, step.category)
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
            PendingActionQueue.clear()
            return
        }
        PendingActionQueue.push(step)
        handler.postDelayed({ tryExecuteNext() }, 500)
    }

    private fun clickNodeWithText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false

        var bestNode: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        val bounds = android.graphics.Rect()

        for (node in nodes) {
            val clickable = findClickableSelfOrParent(node) ?: continue
            clickable.getBoundsInScreen(bounds)
            if (bounds.top < bestTop) {
                bestTop = bounds.top
                bestNode = clickable
            }
        }

        return bestNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

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

    private fun clickSecondTopmostWithText(root: AccessibilityNodeInfo, category: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(category) ?: return false

        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val bounds = android.graphics.Rect()
        for (node in nodes) {
            val clickable = findClickableSelfOrParent(node) ?: continue
            clickable.getBoundsInScreen(bounds)
            candidates.add(bounds.top to clickable)
        }

        if (candidates.size < 2) return false

        val secondTopmost = candidates.sortedBy { it.first }[1]
        return secondTopmost.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

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
