package com.wonseok.babyvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 화면 구성은 activity_main.xml 참고.
 * 흐름: 마이크 버튼 -> 음성 인식 -> 명령 파싱 -> BabyTime 실행
 *       -> BabyTimeAccessibilityService 에 실행할 액션 큐 전달
 */
class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var statusText: TextView

    private val babyTimePackage = "yducky.application.babytime"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val micButton: Button = findViewById(R.id.micButton)
        val settingsButton: Button = findViewById(R.id.openAccessibilitySettings)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        micButton.setOnClickListener { startListening() }

        // 접근성 서비스 활성화 화면으로 바로 이동 (BabyTimeAccessibilityService를 목록에서 켜야 함)
        settingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: ""
                statusText.text = "인식됨: $text"
                handleCommand(text)
            }

            override fun onError(error: Int) {
                statusText.text = "인식 실패 (오류 코드 $error)"
            }

            // 아래는 사용하지 않지만 인터페이스 구현 필수
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        statusText.text = "듣는 중..."
        speechRecognizer.startListening(intent)
    }

    /**
     * 음성 텍스트를 파싱해서 (행동, 하위옵션, 값) 형태로 변환하고,
     * BabyTime 앱을 실행한 뒤 접근성 서비스가 수행할 액션 목록을 넘긴다.
     *
     * 베이비타임 실제 동작 방식 (원석님 확인):
     *  - 홈 화면 아이콘을 탭하면 그 즉시 "기본값(마지막에 쓴 값 등)"으로 자동 기록됨
     *  - 값을 바꾸고 싶으면, 방금 생긴 리스트 최상단 항목을 열어서 상세 수정 후 저장
     *  => 값/하위옵션 지정이 없으면 아이콘 한 번 탭으로 끝, 있으면 방금 생긴 항목을 열어 수정
     *
     * 카테고리별 화면 구조:
     *  - 분유 / 이유식 / 간식 / 우유 / 물 : "80 ml" 숫자 영역 탭 → 키패드로 직접 입력
     *  - 체온 : "37.0" 탭하면 키패드 뜸 (확인 완료)
     *  - 기저귀 : 종류(소변/대변/둘다)
     *  - 수면 : 종류(밤잠/낮잠)
     *  - 이유식 : 하위 태그(소고기/미음/과일)
     *  - 간식 : 하위 태그(우유/퓨레/아기치즈)
     *  - 투약 : 하위 태그(해열제/항생제/지사제/기침약)
     *  - 목욕 : 별도 입력 없이 아이콘 탭만으로 기록 완료
     *  - 병원 / 놀이 : 지원 안 함
     *
     * 예시:
     *  "목욕"               -> action=목욕, 아이콘 탭만으로 끝
     *  "분유 120"           -> action=분유, value=120 → 상세 진입 후 수정
     *  "기저귀 대변"        -> action=기저귀, subtype=대변 → 상세 진입 후 수정
     *  "기저귀"             -> action=기저귀, 아이콘 탭만으로 끝 (기본값 그대로 저장됨)
     */
    private fun handleCommand(text: String) {
        // 우선순위 순서로 검사 (예: "간식 우유" 에서 "간식"을 먼저 잡아야 "우유"로 오인하지 않음)
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
            Toast.makeText(this, "명령을 이해하지 못했어요: $text", Toast.LENGTH_SHORT).show()
            return
        }

        // 하위 태그(옵션) 인식
        val subtypeMap: Map<String, String> = when (matchedAction) {
            "기저귀" -> mapOf("소변" to "소변", "대변" to "대변", "둘다" to "둘다")
            "수면" -> mapOf("낮잠" to "낮잠", "밤잠" to "밤잠")
            "이유식" -> mapOf("소고기" to "소고기", "미음" to "미음", "과일" to "과일")
            "간식" -> mapOf("퓨레" to "퓨레", "아기치즈" to "아기치즈", "우유" to "우유")
            "투약" -> mapOf("해열제" to "해열제", "항생제" to "항생제", "지사제" to "지사제", "기침약" to "기침약")
            else -> emptyMap()
        }
        val matchedSubtype = subtypeMap.entries.firstOrNull { text.contains(it.key) }?.value

        // 숫자(ml/g/도) 입력이 있는 카테고리
        val numericCategories = setOf("분유", "이유식", "간식", "우유", "물", "체온")
        val numberRegex = Regex("""\d+(\.\d+)?""")
        val value = if (matchedAction in numericCategories) numberRegex.find(text)?.value else null

        Log.d("BabyVoice", "action=$matchedAction subtype=$matchedSubtype value=$value")

        // 1. BabyTime 실행
        val launchIntent = packageManager.getLaunchIntentForPackage(babyTimePackage)
        if (launchIntent == null) {
            Toast.makeText(this, "베이비타임을 찾을 수 없어요. 패키지명을 확인하세요.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(launchIntent)

        // 2. 접근성 서비스에 수행할 액션을 큐로 전달
        val needsDetail = matchedSubtype != null || value != null

        // 2-1. 아이콘 탭 -> 기본값으로 즉시 자동 기록됨
        PendingActionQueue.push(ActionStep.ClickText(matchedAction))

        if (needsDetail) {
            // 2-2. 커스터마이징이 필요하면, 방금 생긴 리스트 최상단 항목을 열어 수정
            PendingActionQueue.push(ActionStep.ClickMostRecentEntry)
            if (matchedSubtype != null) {
                PendingActionQueue.push(ActionStep.ClickText(matchedSubtype))
            }
            if (value != null) {
                // 분유/이유식 등은 숫자(ml) 표시 영역을 한 번 탭해야 키패드가 뜸
                PendingActionQueue.push(ActionStep.ClickNumericField)
                PendingActionQueue.push(ActionStep.InputNumber(value))
            }
            PendingActionQueue.push(ActionStep.ClickText("저장"))
        }
        // needsDetail == false 인 경우, 아이콘 탭만으로 이미 기록이 끝났으므로 추가 액션 없음

        statusText.text = "\"$matchedAction" +
            (matchedSubtype?.let { " $it" } ?: "") +
            (value?.let { " $it" } ?: "") + "\" 기록을 시도합니다..."
    }

    override fun onDestroy() {
        speechRecognizer.destroy()
        super.onDestroy()
    }
}
