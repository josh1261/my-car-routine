package io.github.tanmankeum

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

class MainActivity : Activity() {
    private lateinit var leftPane: LinearLayout
    private lateinit var stateView: TextView
    private lateinit var settingsPane: LinearLayout
    private lateinit var logView: TextView
    private lateinit var captionView: TextView
    private var renderedKey = ""
    private var settingsKey = ""
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F4F5F6"))
            setPadding(48, 36, 48, 36)
        }
        leftPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(leftPane) }, LinearLayout.LayoutParams(0, -1, 1.25f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 0, 0, 0) }
        right.addView(text("차가 잰 값", 26f, true), margins(10))
        stateView = text("", 22f, false)
        right.addView(card(stateView), margins(20))
        settingsPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(settingsPane, margins(20))
        right.addView(text("기록", 26f, true), margins(10))
        logView = text("", 19f, false, "#27313A")
        right.addView(ScrollView(this).apply { addView(logView); background = rounded(); setPadding(28, 20, 28, 20) }, LinearLayout.LayoutParams(-1, 0, 1f))
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
        runDebugHook(intent)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        runDebugHook(newIntent)
    }

    private fun runDebugHook(source: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        source.getStringExtra("test_pcm")?.let { name -> TripEngine.recognizeFile(File(filesDir, name)) }
        // 시연 녹화용: 글자나 안내 문구로 화면의 버튼 위치를 찾아 로그로 남긴다. 녹화 스크립트가 이 위치를 실제로 누른다.
        source.getStringExtra("find")?.let { label -> window.decorView.postDelayed({ findView(window.decorView, label) }, 300) }
        source.getStringExtra("caption")?.let { text ->
            captionView.text = text
            captionView.visibility = View.VISIBLE
        }
    }

    private fun findView(view: View, label: String): Boolean {
        if (view is TextView && (view.text.toString() == label || view.hint?.toString() == label)) {
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            android.util.Log.i("TANMANKEUM_UI", "$label ${at[0] + view.width / 2} ${at[1] + view.height / 2}")
            return true
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) if (findView(view.getChildAt(i), label)) return true
        return false
    }

    override fun onStart() { super.onStart(); TripEngine.observe(refresh); renderedKey = ""; settingsKey = ""; render() }
    override fun onStop() { TripEngine.stopObserving(refresh); super.onStop() }

    // ---------- 왼쪽: 주행과 정산 ----------

    private fun leftKey(): String {
        val t = TripEngine.trip
        return listOf(t?.startedAt, t?.riders?.joinToString { "${it.id}${it.onBoard}${it.total}${"%.1f".format(it.distanceKm)}" },
            t?.let { "%.1f".format(it.distanceKm) }, TripEngine.lastReceipt?.at, TripEngine.lastSummary?.at, TripEngine.isParked,
            TripEngine.occupied.toString(), TripEngine.payLink).joinToString("|")
    }

    private fun renderLeft() {
        leftPane.removeAllViews()
        leftPane.addView(text("탄만큼", 40f, true))
        leftPane.addView(text("함께 탄 거리만큼, 차가 잰 실제 비용을 똑같이 나눕니다.", 21f, false, "#5C6670"), margins(22))

        TripEngine.lastReceipt?.let { leftPane.addView(receiptCard(it, true), margins(18)) }
        TripEngine.lastSummary?.let { leftPane.addView(receiptCard(it, false), margins(18)) }

        val t = TripEngine.trip
        if (t == null) {
            val waiting = TripEngine.occupied.filterValues { it }.keys
            val msg = if (waiting.isEmpty()) "지금은 운전석에만 사람이 있습니다." else "탑승: 운전자, ${waiting.joinToString()}"
            leftPane.addView(card(text("$msg\n기어를 P에서 바꾸면 계산을 시작합니다.", 23f, false)), margins(18))
        } else {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(); setPadding(32, 24, 32, 24) }
            box.addView(text("이번 주행  ${"%.1f".format(t.distanceKm)}km", 30f, true))
            box.addView(text("${TripEngine.energyText(t.energy)} 사용 · 에너지 비용 ${won(roundTo10(t.energyCost))}" +
                if (t.extraCost > 0) " · 통행료·주차비 ${won(Math.round(t.extraCost))}" else "", 21f, false, "#5C6670"), margins(14))
            t.riders.forEach { r ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 8) }
                val state = if (r.onBoard) "타는 중" else "내림"
                row.addView(text("${r.seat}  ·  $state", 23f, r.onBoard, if (r.onBoard) "#14181C" else "#8A939B"), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("${"%.1f".format(r.distanceKm)}km   ", 21f, false, "#5C6670"))
                row.addView(text(won(r.total), 25f, true))
                box.addView(row)
            }
            leftPane.addView(box, margins(18))
            if (TripEngine.isParked) {
                leftPane.addView(text("통행료·주차비 나누기 (지금 탄 사람끼리)", 21f, true), margins(6))
                val extras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                listOf(500, 1000, 3000, 5000).forEach { a -> extras.addView(button("+${won(a.toLong())}") { TripEngine.addExtra(a) }) }
                leftPane.addView(extras, margins(10))
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (t != null && TripEngine.isParked) row.addView(button("정산 마치기") { TripEngine.finish() })
        row.addView(button("말로 하기") { TripEngine.listen() })
        leftPane.addView(row)
        leftPane.addView(text(if (TripEngine.isParked || t == null) "말로: “지금 얼마야”, “정산해 줘”" else "주행 중에는 화면 대신 말로 확인하세요: “지금 얼마야”", 19f, false, "#5C6670"))
    }

    private fun receiptCard(r: Receipt, single: Boolean): View {
        val withQr = r.amount > 0
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (single) "#E8F3EC" else "#FFFFFF"); setPadding(32, 24, 32, 24)
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(r.title, 26f, true))
        r.lines.forEach { body.addView(text(it, 21f, false, "#27313A")) }
        if (single) body.addView(text("보낼 금액  ${won(r.amount)}", 32f, true, "#0B6B3A"), margins(4))
        else body.addView(text("동승자 몫 합계  ${won(r.amount)}  (10원 단위 반올림)", 24f, true, "#0B6B3A"))
        box.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        val link = TripEngine.payLink
        if (withQr && link.isNotBlank()) {
            val qr = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            qr.addView(ImageView(this).apply { setImageBitmap(qrBitmap(link, 240)) }, LinearLayout.LayoutParams(240, 240))
            qr.addView(text("운전자 송금 링크", 17f, false, "#5C6670"))
            box.addView(qr)
        }
        return box
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    // ---------- 오른쪽: 설정 ----------

    private fun renderSettings() {
        settingsPane.removeAllViews()
        settingsPane.addView(text("단가와 송금 링크", 26f, true), margins(10))
        if (!TripEngine.isParked) {
            settingsPane.addView(card(text("${won(TripEngine.unitPrice.toLong())}/${TripEngine.energyUnit}\n설정은 기어가 P일 때 바꿀 수 있습니다.", 21f, false)))
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(); setPadding(28, 18, 28, 18) }
        val priceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val price = EditText(this).apply { textSize = 22f; inputType = InputType.TYPE_CLASS_NUMBER; setText(TripEngine.unitPrice.toString()) }
        priceRow.addView(price, LinearLayout.LayoutParams(0, -2, 1f))
        priceRow.addView(text(" ${TripEngine.unitLabel}", 20f, false))
        box.addView(priceRow)
        val link = EditText(this).apply { textSize = 19f; hint = "예: https://toss.me/내아이디"; setText(TripEngine.payLink); isSingleLine = true }
        box.addView(link)
        box.addView(button("저장") {
            price.text.toString().toIntOrNull()?.let { TripEngine.unitPrice = it }
            TripEngine.payLink = link.text.toString()
            TripEngine.log("단가 ${TripEngine.unitPrice}${TripEngine.unitLabel}, 송금 링크 ${if (TripEngine.payLink.isBlank()) "없음" else "저장"}")
        })
        settingsPane.addView(box)
    }

    // ---------- 화면 공통 ----------

    private fun render() {
        val odo = TripEngine.odometerKm?.let { "%.1fkm".format(it) } ?: "-"
        val energy = if (TripEngine.isEv) "배터리 잔량  ${TripEngine.batteryKwh?.let { "%.2fkWh".format(it) } ?: "-"}"
        else "연비  ${TripEngine.fuelEconomy?.let { "%.1fkm/L".format(it) } ?: "-"}"
        val seats = TripEngine.occupied.entries.joinToString("  ") { "${it.key} ${if (it.value) "●" else "○"}" }
        stateView.text = "기어  ${TripEngine.gear}\n계기판 누적 거리  $odo\n$energy\n$seats" + if (TripEngine.listening) "\n듣는 중…" else ""
        logView.text = TripEngine.logs.joinToString("\n").ifEmpty { "아직 기록이 없습니다." }
        val key = leftKey()
        if (key != renderedKey) { renderedKey = key; renderLeft() }
        val sKey = "${TripEngine.isParked}${TripEngine.isEv}"
        if (sKey != settingsKey) { settingsKey = sKey; renderSettings() }
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; textSize = 21f; isAllCaps = false; setOnClickListener { click() } }
    private fun card(view: View) = LinearLayout(this).apply { background = rounded(); setPadding(28, 22, 28, 22); addView(view) }
    private fun rounded(color: String = "#FFFFFF") = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = 20f }
    private fun margins(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }
    private fun text(value: String, size: Float, bold: Boolean, color: String = "#14181C") = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.parseColor(color)); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(6f, 1f)
    }
}
