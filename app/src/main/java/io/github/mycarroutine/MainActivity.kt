package io.github.mycarroutine

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private lateinit var leftPane: LinearLayout
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var captionView: TextView
    private var editing = false
    private var renderedKey = ""
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F4F5F6"))
            setPadding(48, 40, 48, 40)
        }
        leftPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(leftPane) }, LinearLayout.LayoutParams(0, -1, 1.1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 0, 0, 0) }
        right.addView(text("지금 차 상태", 28f, true), margins(12))
        stateView = text("", 24f, false)
        right.addView(card(stateView), margins(24))
        right.addView(text("실행 기록", 28f, true), margins(12))
        logView = text("", 21f, false, "#27313A")
        right.addView(ScrollView(this).apply { addView(logView); background = rounded(); setPadding(28, 24, 28, 24) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        // 시연 녹화용 자막. 디버그 빌드에서 자막을 받았을 때만 보인다.
        captionView = TextView(this).apply {
            textSize = 26f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#14181C"))
            setPadding(48, 22, 48, 22); typeface = Typeface.DEFAULT_BOLD; visibility = View.GONE
        }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(captionView, LinearLayout.LayoutParams(-1, -2))
        page.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        showList()
        runDebugHook(intent)
    }

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        runDebugHook(newIntent)
    }

    private fun runDebugHook(source: android.content.Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        source.getStringExtra("test_pcm")?.let { name -> RoutineEngine.recognizeFile(File(filesDir, name)) }
        source.getStringExtra("caption")?.let { text ->
            captionView.text = text
            captionView.visibility = View.VISIBLE
        }
    }

    override fun onStart() { super.onStart(); RoutineEngine.observe(refresh); render() }
    override fun onStop() { RoutineEngine.stopObserving(refresh); super.onStop() }

    // ---------- 규칙 목록 ----------

    private fun showList() {
        editing = false
        renderedKey = listKey()
        leftPane.removeAllViews()
        leftPane.addView(text("내 차 루틴", 40f, true))
        leftPane.addView(text("한 번 만들어 두면, 조건이 맞을 때 차가 알아서 움직입니다.", 22f, false, "#5C6670"), margins(24))
        RoutineEngine.rules.forEach { leftPane.addView(ruleCard(it), margins(18)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("+ 규칙 만들기") { showEditor(null) })
        row.addView(button("말로 실행") { RoutineEngine.listen() })
        leftPane.addView(row)
        if (!RoutineEngine.isParked) leftPane.addView(text("규칙 만들기와 편집은 기어가 P일 때만 할 수 있습니다.", 20f, false, "#5C6670"))
    }

    private fun listKey() = RoutineEngine.rules.joinToString { "${it.id}${it.name}${it.enabled}" } + RoutineEngine.isParked

    private fun ruleCard(rule: Rule): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(); setPadding(32, 26, 32, 26) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(rule.name, 28f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (rule.trigger == Trigger.VOICE) head.addView(button("지금 실행") { RoutineEngine.run(rule) })
        head.addView(Switch(this).apply {
            scaleX = 1.4f; scaleY = 1.4f; setPadding(24, 0, 0, 0)
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, on -> RoutineEngine.setEnabled(rule, on) }
        })
        box.addView(head)
        val condition = if (rule.trigger == Trigger.VOICE) "“${rule.phrase}”라고 말하면" else rule.trigger.label
        box.addView(text("$condition  ·  ${rule.actions.joinToString(", ") { it.label }}", 22f, false, "#5C6670"), margins(10))
        if (RoutineEngine.isParked) {
            val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            tools.addView(button("편집") { showEditor(rule) })
            tools.addView(button("삭제") { RoutineEngine.deleteRule(rule) })
            box.addView(tools)
        }
        return box
    }

    // ---------- 규칙 만들기 ----------

    private fun showEditor(existing: Rule?) {
        if (!RoutineEngine.isParked) { RoutineEngine.log("규칙은 기어가 P일 때만 만들 수 있습니다"); return }
        editing = true
        leftPane.removeAllViews()
        leftPane.addView(text(if (existing == null) "규칙 만들기" else "규칙 편집", 36f, true), margins(20))

        leftPane.addView(text("이럴 때", 26f, true), margins(8))
        val triggers = RadioGroup(this)
        val triggerIds = mutableMapOf<Int, Trigger>()
        Trigger.entries.filter { RoutineEngine.isSupported(it) || existing?.trigger == it }.forEach { t ->
            val radio = RadioButton(this).apply { text = t.label; textSize = 24f; id = View.generateViewId() }
            triggerIds[radio.id] = t
            triggers.addView(radio)
            if ((existing?.trigger ?: Trigger.VOICE) == t) radio.isChecked = true
        }
        leftPane.addView(triggers, margins(16))

        leftPane.addView(text("차가 이렇게", 26f, true), margins(8))
        val checks = Action.entries.filter { RoutineEngine.isSupported(it) || existing?.actions?.contains(it) == true }.associateWith { a ->
            CheckBox(this).apply { text = a.label; textSize = 24f; isChecked = existing?.actions?.contains(a) == true }.also { leftPane.addView(it) }
        }

        leftPane.addView(text("규칙 이름", 26f, true), margins(8))
        val nameInput = EditText(this).apply { textSize = 24f; hint = "비워 두면 자동으로 만듭니다"; setText(existing?.name ?: "") }
        leftPane.addView(nameInput, margins(12))
        leftPane.addView(text("부를 말(말로 실행할 때)", 26f, true), margins(8))
        val phraseInput = EditText(this).apply { textSize = 24f; hint = "예: 세차 준비"; setText(existing?.phrase ?: "") }
        leftPane.addView(phraseInput, margins(12))

        val error = text("", 22f, false, "#B3261E")
        leftPane.addView(error)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("저장") {
            val trigger = triggerIds[triggers.checkedRadioButtonId] ?: return@button
            val actions = checks.filterValues { it.isChecked }.keys.toList()
            val phrase = phraseInput.text.toString().trim()
            val problem = when {
                actions.isEmpty() -> "동작을 하나 이상 고르세요."
                trigger == Trigger.VOICE && phrase.isBlank() -> "말로 실행하려면 부를 말을 적으세요."
                else -> null
            }
            if (problem != null) { error.text = problem; return@button }
            val name = nameInput.text.toString().trim().ifEmpty { "${trigger.label} ${actions.first().label}" }
            val saved = RoutineEngine.saveRule(Rule(existing?.id ?: RoutineEngine.newId(), name, trigger, actions, phrase, existing?.enabled ?: true))
            if (saved) showList() else error.text = "기어가 P일 때만 저장할 수 있습니다."
        })
        row.addView(button("취소") { showList() })
        leftPane.addView(row)
    }

    // ---------- 화면 공통 ----------

    private fun render() {
        val s = RoutineEngine.state
        val windows = if (s.windows.isEmpty()) "-" else s.windows.entries.sortedBy { it.key }.joinToString("  ") { "${it.key} ${it.value}" }
        stateView.text = "기어  ${s.gear}\n와이퍼  ${s.wiper}\n공기  ${s.recirc}\n창문(0=닫힘)  $windows" + if (s.listening) "\n듣는 중…" else ""
        logView.text = RoutineEngine.logs.joinToString("\n").ifEmpty { "아직 실행된 규칙이 없습니다." }
        if (!editing && listKey() != renderedKey) showList()
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; textSize = 22f; isAllCaps = false; setOnClickListener { click() } }
    private fun card(view: View) = LinearLayout(this).apply { background = rounded(); setPadding(28, 24, 28, 24); addView(view) }
    private fun rounded() = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 20f }
    private fun margins(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }
    private fun text(value: String, size: Float, bold: Boolean, color: String = "#14181C") = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.parseColor(color)); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(8f, 1f)
    }
}
