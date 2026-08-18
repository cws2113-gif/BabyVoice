package com.wonseok.babyvoice

import android.content.Context
import android.util.Log
import android.widget.Toast

object CommandProcessor {

    private const val BABYTIME_PACKAGE = "yducky.application.babytime"

    fun process(context: Context, text: String): String? {
        val primaryOrder = listOf(
            "분유", "이유식", "기저귀", "수면", "목욕",
            "체온", "투약", "간식", "우유", "물"
        )
        val synonyms = mapOf("낮잠" to "수면", "밤잠" to "수면", "잠" to "수면", "열" to "체온", "약" to "투약")

        var matchedAction = primaryOrder.firstOrNull { text.contains(it) }
        if (matchedAction == null) {
            matchedAction = synonyms.entries.firstOrNull { text.contains(it.key) }?.value
        }

        if (matchedAction == null) {
            Toast.makeText(context, "명령을 이해하지 못했어요: $text", Toast.LENGTH_SHORT).show()
            return null
        }

        val subtypeMap: Map<String, String> = when (matchedAction) {
            "기저귀" -> mapOf("소변" to "소변", "대변" to "대변", "둘다" to "둘다")
            "수면" -> mapOf("낮잠" to "낮잠", "밤잠" to "밤잠")
            "이유식" -> mapOf("소고기" to "소고기", "미음" to "미음", "과일" to "과일")
            "간식" -> mapOf("퓨레" to "퓨레", "아기치즈" to "아기치즈", "우유" to "우유")
            "투약" -> mapOf("해열제" to "해열제", "항생제" to "항생제", "지사제" to "지사제", "기침약" to "기침약")
            else -> emptyMap()
        }
        val matchedSubtype = subtypeMap.entries.firstOrNull { text.contains(it.key) }?.value

        val timeOffsetRegex = Regex("""(\d+)\s*(분|시간)\s*전""")
        val timeOffsetMatch = timeOffsetRegex.find(text)
        val timeOffsetMinutes = timeOffsetMatch?.let { m ->
            val amount = m.groupValues[1].toIntOrNull() ?: 0
            if (m.groupValues[2] == "시간") amount * 60 else amount
        }
        val textForValueSearch = if (timeOffsetMatch != null) text.removeRange(timeOffsetMatch.range) else text

        val numericCategories = setOf("분유", "이유식", "간식", "우유", "물", "체온")
        val numberRegex = Regex("""\d+(\.\d+)?""")
        val value = if (matchedAction in numericCategories) numberRegex.find(textForValueSearch)?.value else null

        Log.d("BabyVoice", "action=$matchedAction subtype=$matchedSubtype value=$value timeOffsetMinutes=$timeOffsetMinutes")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(BABYTIME_PACKAGE)
        if (launchIntent == null) {
            Toast.makeText(context, "베이비타임을 찾을 수 없어요.", Toast.LENGTH_LONG).show()
            return null
        }
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        val needsDetail = matchedSubtype != null || value != null || timeOffsetMinutes != null

        PendingActionQueue.push(ActionStep.ClickText(matchedAction))

        if (needsDetail) {
            PendingActionQueue.push(ActionStep.ClickMostRecentEntry)
            if (timeOffsetMinutes != null) {
                for (buttonLabel in clicksForOffsetMinutes(timeOffsetMinutes)) {
                    PendingActionQueue.push(ActionStep.ClickText(buttonLabel))
                }
            }
            if (matchedSubtype != null) {
                PendingActionQueue.push(ActionStep.ClickText(matchedSubtype))
            }
            if (value != null) {
                PendingActionQueue.push(ActionStep.ClickNumericField)
                PendingActionQueue.push(ActionStep.InputNumber(value))
            }
        }
        PendingActionQueue.push(ActionStep.ClickText("저장"))

        return "\"$matchedAction" +
            (matchedSubtype?.let { " $it" } ?: "") +
            (value?.let { " $it" } ?: "") +
            (timeOffsetMinutes?.let { " ${it}분전" } ?: "") + "\" 기록을 시도합니다..."
    }

    private fun clicksForOffsetMinutes(totalMinutes: Int): List<String> {
        var remaining = totalMinutes
        val clicks = mutableListOf<String>()

        val hours = remaining / 60
        repeat(hours) { clicks.add("-1H") }
        remaining %= 60

        val tens = remaining / 10
        repeat(tens) { clicks.add("-10분") }
        remaining %= 10

        repeat(remaining) { clicks.add("-1분") }

        return clicks
    }
}
