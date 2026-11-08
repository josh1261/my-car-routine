package io.github.mycarroutine

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 규칙으로 만들 수 있는 동작. 주행 중에도 실행될 수 있으므로 닫기, 잠그기, 끄기처럼
 * 위험이 낮은 동작만 허용한다. 창문 열기, 문 열기 같은 동작은 의도적으로 넣지 않았다.
 */
enum class Action(val label: String) {
    CLOSE_WINDOWS("창문 닫기"),
    LOCK_REAR_WINDOWS("뒷창문 잠그기"),
    RECIRCULATION_ON("내기순환 켜기"),
    WIPER_OFF("와이퍼 끄기"),
    AMBIENT_OFF("무드등 끄기"),
}

enum class Trigger(val label: String) {
    WIPER_ON("와이퍼가 켜지면"),
    GEAR_PARK("기어를 P에 넣으면"),
    REAR_OCCUPIED("뒷좌석에 사람이 타면"),
    VOICE("말로 실행하면"),
}

data class Rule(val id: String, val name: String, val trigger: Trigger, val actions: List<Action>, val phrase: String, val enabled: Boolean)

object RoutineEngine {
    data class CarState(var wiper: String = "-", var gear: String = "-", var windows: Map<String, Int> = emptyMap(), var recirc: String = "-", var listening: Boolean = false)

    val state = CarState()
    val logs = CopyOnWriteArrayList<String>()
    var rules: List<Rule> = emptyList()
        private set
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    // 차량 SDK 호출은 내부 처리가 무거워 화면 스레드를 막을 수 있으므로 별도 스레드에서 처리한다.
    private val worker = Handler(HandlerThread("vehicle-worker").apply { start() }.looper)
    private lateinit var appContext: Context
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private var stt: SpeechToText? = null
    private var tts: TextToSpeech? = null
    private var started = false
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private var windowAreas: List<WindowArea> = emptyList()
    private var rearLockAreas: List<WindowArea> = emptyList()
    private var seatAreas: List<SeatArea> = emptyList()
    private var ambientAreas: List<SeatArea> = emptyList()
    private var wiperSupported = false
    private var rearSeatSupported = false
    private val rearOccupied = mutableMapOf<String, Boolean>()
    private var lastGear: Int? = null
    private var onSpeechDone: (() -> Unit)? = null

    val isParked: Boolean get() = lastGear == VehicleGear.GEAR_PARK

    /** 이 차가 지원하는 동작과 조건만 편집 화면에 보여주기 위해 쓴다. */
    fun isSupported(action: Action) = taskCount(action) > 0
    fun isSupported(trigger: Trigger) = when (trigger) {
        Trigger.WIPER_ON -> wiperSupported
        Trigger.REAR_OCCUPIED -> rearSeatSupported
        Trigger.GEAR_PARK, Trigger.VOICE -> true
    }

    fun observe(listener: () -> Unit) { observers.add(listener) }
    fun stopObserving(listener: () -> Unit) { observers.remove(listener) }
    private fun changed() { main.post { observers.forEach { it() } } }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        logs.add(0, "$time  $message")
        while (logs.size > 60) logs.removeAt(logs.size - 1)
        debugLog( message)
        changed()
    }

    /** 배포 빌드에서는 시스템 로그를 남기지 않는다. */
    private fun debugLog(message: String) {
        val debuggable = ::appContext.isInitialized && appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) Log.i("ROUTINE", message)
    }

    // ---------- 규칙 저장 ----------

    private fun prefs() = appContext.getSharedPreferences("rules", Context.MODE_PRIVATE)

    private fun defaults() = listOf(
        Rule("rain", "비 오면 창문 닫기", Trigger.WIPER_ON, listOf(Action.CLOSE_WINDOWS, Action.RECIRCULATION_ON), "", true),
        Rule("wash", "세차 준비", Trigger.VOICE, listOf(Action.CLOSE_WINDOWS, Action.WIPER_OFF, Action.RECIRCULATION_ON), "세차 준비", true),
    )

    private fun loadRules() {
        val raw = prefs().getString("rules_json", null)
        rules = if (raw == null) defaults() else runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val actions = o.getJSONArray("actions")
                Rule(o.getString("id"), o.getString("name"), Trigger.valueOf(o.getString("trigger")),
                    List(actions.length()) { Action.valueOf(actions.getString(it)) }, o.optString("phrase"), o.getBoolean("enabled"))
            }
        }.getOrElse { log("저장된 규칙을 읽지 못해 기본 규칙을 사용합니다"); defaults() }
    }

    private fun saveRules() {
        val array = JSONArray()
        rules.forEach { r ->
            array.put(JSONObject().put("id", r.id).put("name", r.name).put("trigger", r.trigger.name)
                .put("actions", JSONArray(r.actions.map { it.name })).put("phrase", r.phrase).put("enabled", r.enabled))
        }
        prefs().edit().putString("rules_json", array.toString()).apply()
        changed()
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** 규칙 편집은 정차 중에만 허용한다. */
    fun saveRule(rule: Rule): Boolean {
        if (!isParked) { log("규칙은 기어가 P일 때만 저장할 수 있습니다"); return false }
        rules = if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule
        saveRules()
        log("규칙 저장: ${rule.name}")
        return true
    }

    fun deleteRule(rule: Rule) {
        if (!isParked) { log("규칙은 기어가 P일 때만 삭제할 수 있습니다"); return }
        rules = rules.filterNot { it.id == rule.id }
        saveRules()
        log("규칙 삭제: ${rule.name}")
    }

    fun setEnabled(rule: Rule, on: Boolean) {
        rules = rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }
        saveRules()
        log("${rule.name} ${if (on) "켜짐" else "꺼짐"}")
    }

    // ---------- 시작 ----------

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        loadRules()
        worker.post {
            vehicle = Vehicle(appContext)
            vehicle.initialize()
            worker.postDelayed({ connectVehicle() }, 2000)
        }
        // Car 연결은 끝날 때까지 스레드를 잡을 수 있어 차량 작업 스레드와 분리한다.
        Thread({ connectGear() }, "gear-connect").start()
        Thread({ connectVoice() }, "voice-connect").start()
    }

    private fun connectVehicle() {
        vehicle.getWindow().checkWindowPositionCapabilities({ map: Map<WindowArea, *> ->
            windowAreas = map.keys.filter { a -> a.values.none { it == WindowValue.FRONT_WINDSHIELD || it == WindowValue.REAR_WINDSHIELD } }
            windowAreas.forEach { area ->
                val name = areaName(area)
                vehicle.getWindow().getCurrentWindowPosition(area, { v -> if (v != null) setWindow(name, v) }, { log("창문 읽기 실패: ${it.message}") })
                vehicle.getWindow().registerWindowPosition(area, object : WindowPositionListener {
                    override fun onWindowPositionUpdated(value: Int?) { if (value != null) setWindow(name, value) }
                    override fun onFailed(exception: Exception) = log("창문 신호 오류: ${exception.message}")
                })
            }
        }, { log("이 차는 창문 제어를 지원하지 않습니다: ${it.message}") })

        vehicle.getWindow().checkWindowLockCapabilities({ list: List<WindowArea> -> rearLockAreas = list.filter { a -> a.values.all { it == WindowValue.ROW_2_LEFT || it == WindowValue.ROW_2_RIGHT } } }, { log("이 차는 뒷창문 잠금을 지원하지 않습니다") })

        vehicle.getHVAC().checkHvacRecirculationCapabilities({ list: List<SeatArea> ->
            seatAreas = list
            list.firstOrNull()?.let { area ->
                vehicle.getHVAC().getHvacRecirculationState(area, { on -> state.recirc = if (on == true) "내기" else "외기"; changed() }, { log("내기순환 읽기 실패: ${it.message}") })
            }
        }, { log("이 차는 내기순환 제어를 지원하지 않습니다") })

        vehicle.getLight().checkAmbientLightSwitchCapabilities({ _, map: Map<SeatArea, *> -> ambientAreas = map.keys.toList(); changed() }, { log("이 차는 무드등 제어를 지원하지 않습니다") })

        connectSeats()

        wiperSupported = true
        vehicle.getWiper().registerWiperState(front, object : WiperStateListener {
            override fun onWiperStateUpdated(value: WiperState?) {
                state.wiper = if (value == WiperState.ON) "작동" else "꺼짐"
                changed()
                if (value == WiperState.ON) fire(Trigger.WIPER_ON)
            }
            override fun onFailed(exception: Exception) = log("와이퍼 신호 오류: ${exception.message}")
        })
    }

    /**
     * 뒷좌석 탑승 신호를 직접 구독한다. 읽기에 성공한 좌석만 지원으로 표시한다.
     * Vehicle SDK 2.3.2와 Pleos 에뮬레이터에서 차량 값 1(빈 좌석)이 true, 2(탑승)가 false로 전달되어 반대로 해석한다.
     * 실차에서도 같은지는 확인이 필요하다.
     */
    private fun connectSeats() {
        listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT).forEach { value ->
            val area = SeatArea(value)
            val key = value.name
            vehicle.getSeat().getCurrentSeatOccupancy(area, { on ->
                rearOccupied[key] = on == false
                rearSeatSupported = true
                debugLog( "뒷좌석 $key 시작 신호: $on")
                changed()
                vehicle.getSeat().registerSeatOccupancy(area, object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(value: Boolean?) {
                        val now = value == false
                        val before = rearOccupied.put(key, now)
                        debugLog( "뒷좌석 $key 탑승: $before -> $now")
                        if (now && before != true) fire(Trigger.REAR_OCCUPIED)
                    }
                    override fun onFailed(exception: Exception) = log("좌석 신호 오류: ${exception.message}")
                })
            }, { debugLog( "뒷좌석 $key 읽기 실패: ${it.message}") })
        }
    }

    private fun connectGear() {
        runCatching {
            val c = Car.createCar(appContext)
            car = c
            val manager = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            manager.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(v: CarPropertyValue<*>) {
                    val gear = v.value as? Int ?: return
                    val previous = lastGear
                    lastGear = gear
                    state.gear = when (gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; VehicleGear.GEAR_DRIVE -> "D"; else -> "$gear" }
                    changed()
                    if (gear == VehicleGear.GEAR_PARK && previous != null && previous != gear) fire(Trigger.GEAR_PARK)
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) = log("기어 신호 오류")
            }, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { log("기어 연결 실패: ${it.message}") }
    }

    private fun connectVoice() {
        // 서버 등록 정보 없이 쓰도록 ON_DEVICE 모드를 사용한다.
        val speech = TextToSpeech(appContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
        speech.initialize(object : TtsInitListener {
            override fun onSuccess() {
                speech.setLocale(Locale.KOREA)
                speech.addEventListener(object : EventListener {
                    override fun onStart() = Unit
                    override fun onReady() = Unit
                    override fun onDone() { debugLog( "음성 안내 완료"); finishSpeech() }
                    override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) { log("음성 안내 오류: $errorType"); finishSpeech() }
                    override fun onStop() { finishSpeech() }
                    override fun onUpdatedRms(rms: Double) = Unit
                })
                tts = speech
                log("음성 안내 준비 완료")
            }
            override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) = log("음성 안내 시작 실패: $errorType")
        })

        val recognizer = SpeechToText(appContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
        recognizer.initialize(object : SttInitListener {
            override fun onSuccess() {
                recognizer.setLocale(Locale.KOREA)
                recognizer.addListener(object : ResultListener {
                    override fun onReady() = Unit
                    override fun onStartedRecognition() { state.listening = true; changed() }
                    override fun onUpdated(stt: String, completed: Boolean) { if (completed) handleSpeech(stt) }
                    override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                    override fun onEndedRecognition() { state.listening = false; changed() }
                    override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) { state.listening = false; log("음성 인식 오류: $errorType") }
                })
                stt = recognizer
                log("음성 인식 준비 완료")
            }
            override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) = log("음성 인식 시작 실패: $errorType")
        })
    }

    // ---------- 음성 ----------

    fun listen() {
        val recognizer = stt ?: run { log("음성 인식이 아직 준비되지 않았습니다"); return }
        worker.post { runCatching { recognizer.request() }.onFailure { log("음성 인식 요청 실패: ${it.message}") } }
    }

    /** 개발 시험용: 마이크 대신 16kHz 16bit 모노 PCM 파일로 인식한다. */
    fun recognizeFile(file: File) {
        val recognizer = stt ?: run { log("음성 인식이 아직 준비되지 않았습니다"); return }
        Thread {
            runCatching {
                file.readBytes().asList().chunked(3200).forEach { recognizer.sendAudio(it.toByteArray()); Thread.sleep(100) }
                recognizer.completeAudioSend()
            }.onFailure { log("음성 파일 인식 실패: ${it.message}") }
        }.start()
    }

    /**
     * 한국어 음성 인식은 발음이 같은 "세차"와 "새차"를 구분하지 못할 수 있다.
     * 그래서 공백과 문장부호를 지우고, 모음 ㅐ·ㅔ와 ㅒ·ㅖ를 같은 소리로 맞춘 뒤 비교한다.
     */
    private fun normalize(text: String): String = text.replace(Regex("[\\s.,!?]"), "").map { c ->
        if (c in '\uAC00'..'\uD7A3') {
            val code = c - '\uAC00'
            val jung = (code / 28) % 21
            val fixed = when (jung) { 1 -> 5; 3 -> 7; else -> jung }
            '\uAC00' + (code - jung * 28 + fixed * 28)
        } else c
    }.joinToString("")

    private fun handleSpeech(text: String) {
        log("들은 말: $text")
        val heard = normalize(text)
        val rule = rules.firstOrNull { it.enabled && it.trigger == Trigger.VOICE && it.phrase.isNotBlank() && heard.contains(normalize(it.phrase)) }
        if (rule != null) run(rule, "음성") else { log("일치하는 규칙이 없습니다"); speak("일치하는 규칙이 없습니다.") }
    }

    private fun speak(text: String) { runCatching { tts?.speak(text, true) }.onFailure { log("음성 안내 실패: ${it.message}") } }

    // ---------- 실행 ----------

    private fun fire(trigger: Trigger) {
        rules.filter { it.trigger == trigger && it.enabled }.forEach { run(it, trigger.label) }
    }

    private fun finishSpeech() {
        val next = onSpeechDone ?: return
        onSpeechDone = null
        worker.post(next)
    }

    /** 차가 움직이기 전에 먼저 알린다. 안내가 끝나거나 3초가 지나면 동작을 실행한다. */
    fun run(rule: Rule, reason: String = "직접 실행") { worker.post { runOnWorker(rule, reason) } }

    private fun runOnWorker(rule: Rule, reason: String) {
        log("▶ ${rule.name} ($reason)")
        val available = rule.actions.filter { isSupported(it) }
        if (available.isEmpty()) { log("  이 차에서 실행할 수 있는 동작이 없습니다"); return }
        var started = false
        val start = {
            if (!started) {
                started = true
                execute(rule, available)
            }
        }
        if (tts == null) { start(); return }
        onSpeechDone = start
        speak("${available.joinToString(", ") { it.label }}를 실행합니다.")
        worker.postDelayed({ if (!started) { onSpeechDone = null; start() } }, 3000)
    }

    private fun execute(rule: Rule, actions: List<Action>) {
        val tasks = actions.sumOf { taskCount(it) }
        val remaining = AtomicInteger(tasks)
        val failed = AtomicInteger(0)
        val finish = { ok: Boolean ->
            if (!ok) failed.incrementAndGet()
            if (remaining.decrementAndGet() == 0) {
                val f = failed.get()
                log(if (f == 0) "  완료: ${actions.joinToString(", ") { it.label }}" else "  일부 실패 ${f}건 / 전체 ${tasks}건")
            }
        }
        actions.forEach { perform(it, finish) }
    }

    private fun taskCount(action: Action) = when (action) {
        Action.CLOSE_WINDOWS -> windowAreas.size
        Action.LOCK_REAR_WINDOWS -> rearLockAreas.size
        Action.RECIRCULATION_ON -> seatAreas.size
        Action.WIPER_OFF -> if (wiperSupported) 1 else 0
        Action.AMBIENT_OFF -> ambientAreas.size
    }

    private fun perform(action: Action, done: (Boolean) -> Unit) {
        val fail: (Exception) -> Unit = { log("  ${action.label} 실패: ${it.message}"); done(false) }
        when (action) {
            Action.CLOSE_WINDOWS -> windowAreas.forEach { vehicle.getWindow().setWindowPosition(it, 0, { _, _ -> done(true) }, fail) }
            Action.LOCK_REAR_WINDOWS -> rearLockAreas.forEach { area ->
                vehicle.getWindow().setWindowLock(area, true, { _, _ ->
                    vehicle.getWindow().getCurrentWindowLockState(area, { v -> debugLog( "뒷창문 잠금 상태 ${area.values.joinToString()}: $v") }, { debugLog( "잠금 읽기 실패: ${it.message}") })
                    done(true)
                }, fail)
            }
            Action.RECIRCULATION_ON -> seatAreas.forEach { vehicle.getHVAC().setHvacRecirculation(it, true, { _, _ -> state.recirc = "내기"; changed(); done(true) }, fail) }
            Action.AMBIENT_OFF -> ambientAreas.forEach { area ->
                vehicle.getLight().setAmbientLightSwitch(area, LightSwitch.OFF, { _, _ ->
                    vehicle.getLight().getAmbientLightSwitch(area, { v -> debugLog( "무드등 상태: $v") }, { debugLog( "무드등 읽기 실패: ${it.message}") })
                    done(true)
                }, fail)
            }
            Action.WIPER_OFF -> vehicle.getWiper().setWiperSwitch(front, WiperSwitch.OFF, { _, _ -> done(true) }, fail)
        }
    }

    private fun setWindow(name: String, value: Int) {
        state.windows = state.windows + (name to value)
        changed()
    }

    private fun areaName(area: WindowArea) = when (area.values.firstOrNull()) {
        WindowValue.ROW_1_LEFT -> "운전석"
        WindowValue.ROW_1_RIGHT -> "동승석"
        WindowValue.ROW_2_LEFT -> "뒷왼쪽"
        WindowValue.ROW_2_RIGHT -> "뒷오른쪽"
        else -> "기타"
    }
}
