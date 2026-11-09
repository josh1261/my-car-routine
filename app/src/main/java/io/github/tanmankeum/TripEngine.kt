package io.github.tanmankeum

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToLong

/** 차에 탄 한 사람. 운전자는 항상 한 명으로 센다. 좌석에서 내렸다가 다시 누가 타면 새 사람으로 센다. */
class Rider(val id: Int, val seat: String) {
    var distanceKm = 0.0
    var energy = 0.0        // 이 사람 몫으로 나눈 에너지 사용량(kWh 또는 L)
    var energyCost = 0.0    // 이 사람 몫의 에너지 비용(원)
    var extraCost = 0.0     // 이 사람 몫의 통행료·주차비(원)
    var onBoard = true
    val total: Long get() = roundTo10(energyCost + extraCost)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("seat", seat).put("km", distanceKm).put("energy", energy)
        .put("cost", energyCost).put("extra", extraCost).put("on", onBoard)

    companion object {
        fun from(o: JSONObject) = Rider(o.getInt("id"), o.getString("seat")).apply {
            distanceKm = o.getDouble("km"); energy = o.getDouble("energy"); energyCost = o.getDouble("cost")
            extraCost = o.getDouble("extra"); onBoard = o.getBoolean("on")
        }
    }
}

/** 10원 단위 반올림. */
fun roundTo10(won: Double): Long = (won / 10.0).roundToLong() * 10

fun won(value: Long): String = NumberFormat.getNumberInstance(Locale.KOREA).format(value) + "원"

class Trip(val startedAt: Long) {
    val riders = mutableListOf<Rider>()
    var distanceKm = 0.0
    var energy = 0.0
    var energyCost = 0.0
    var extraCost = 0.0
    var nextId = 1
    val onBoard: List<Rider> get() = riders.filter { it.onBoard }
    val total: Long get() = riders.sumOf { it.total }

    fun toJson(): JSONObject = JSONObject().put("start", startedAt).put("km", distanceKm).put("energy", energy)
        .put("cost", energyCost).put("extra", extraCost).put("next", nextId).put("riders", JSONArray(riders.map { it.toJson() }))

    companion object {
        fun from(o: JSONObject) = Trip(o.getLong("start")).apply {
            distanceKm = o.getDouble("km"); energy = o.getDouble("energy"); energyCost = o.getDouble("cost")
            extraCost = o.getDouble("extra"); nextId = o.getInt("next")
            val list = o.getJSONArray("riders")
            repeat(list.length()) { riders.add(Rider.from(list.getJSONObject(it))) }
        }
    }
}

/** 동승자가 내렸을 때 화면에 띄우는 정산 카드. */
data class Receipt(val title: String, val lines: List<String>, val amount: Long, val at: Long)

/**
 * 주행 거리(계기판 누적 거리)와 실제 에너지 사용량(전기차는 배터리 잔량 변화, 내연기관은 차가 잰 연비)을
 * 그 구간에 타고 있던 사람 수로 나눠 각자에게 쌓는다. 추정 경로나 평균값이 아니라 차가 잰 값만 쓴다.
 */
object TripEngine {
    const val DRIVER = "운전자"
    private const val MAX_STEP_KM = 50.0
    private const val MAX_STEP_KWH = 20.0
    private val seats = linkedMapOf(SeatValue.ROW_1_RIGHT to "조수석", SeatValue.ROW_2_LEFT to "뒷좌석 왼쪽", SeatValue.ROW_2_RIGHT to "뒷좌석 오른쪽")

    val logs = CopyOnWriteArrayList<String>()
    var trip: Trip? = null; private set
    var lastReceipt: Receipt? = null; private set
    var lastSummary: Receipt? = null; private set
    var gear = "-"; private set
    var odometerKm: Double? = null; private set
    var batteryKwh: Double? = null; private set
    var fuelEconomy: Double? = null; private set
    var isEv = false; private set
    var listening = false; private set
    val occupied = linkedMapOf<String, Boolean>()

    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    // 차량 SDK 호출과 계산은 모두 이 스레드에서만 한다. 화면 스레드를 막지 않고, 값이 섞이지 않게 하기 위해서다.
    private val worker = Handler(HandlerThread("trip-worker").apply { start() }.looper)
    private lateinit var appContext: Context
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private var stt: SpeechToText? = null
    private var tts: TextToSpeech? = null
    private var started = false
    private var lastGear: Int? = null
    private var batteryScale = 1.0

    val isParked: Boolean get() = lastGear == VehicleGear.GEAR_PARK

    // ---------- 설정 ----------

    private fun prefs() = appContext.getSharedPreferences("tanmankeum", Context.MODE_PRIVATE)
    /** 전기차는 원/kWh, 내연기관은 원/L. 운전자가 정차 중에 직접 바꾼다. */
    var unitPrice: Int
        get() = prefs().getInt(if (isEv) "price_ev" else "price_fuel", if (isEv) 350 else 1700)
        set(v) { prefs().edit().putInt(if (isEv) "price_ev" else "price_fuel", v.coerceIn(1, 100000)).apply(); changed() }
    var payLink: String
        get() = prefs().getString("pay_link", "") ?: ""
        set(v) { prefs().edit().putString("pay_link", v.trim()).apply(); changed() }
    val unitLabel: String get() = if (isEv) "원/kWh" else "원/L"
    val energyUnit: String get() = if (isEv) "kWh" else "L"

    // ---------- 공통 ----------

    fun observe(listener: () -> Unit) { observers.add(listener) }
    fun stopObserving(listener: () -> Unit) { observers.remove(listener) }
    private fun changed() { main.post { observers.forEach { it() } } }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        logs.add(0, "$time  $message")
        while (logs.size > 60) logs.removeAt(logs.size - 1)
        debugLog(message)
        changed()
    }

    /** 배포 빌드에서는 시스템 로그를 남기지 않는다. */
    private fun debugLog(message: String) {
        val debuggable = ::appContext.isInitialized && appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) Log.i("TANMANKEUM", message)
    }

    private fun saveTrip() {
        val t = trip
        prefs().edit().putString("trip", t?.toJson()?.toString()).apply()
        changed()
    }

    // ---------- 시작 ----------

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        prefs().getString("trip", null)?.let { raw ->
            runCatching { trip = Trip.from(JSONObject(raw)) }.onSuccess { log("진행 중이던 주행을 이어서 계산합니다") }
        }
        worker.post {
            vehicle = Vehicle(appContext)
            vehicle.initialize()
            worker.postDelayed({ connectVehicle() }, 2000)
        }
        // Car 연결과 음성 연결은 끝날 때까지 스레드를 잡을 수 있어 계산 스레드와 분리한다.
        Thread({ connectGear() }, "gear-connect").start()
        Thread({ connectVoice() }, "voice-connect").start()
    }

    private fun connectVehicle() {
        vehicle.getOdometer().getCurrentDistanceDriven({ v -> worker.post { onOdometer(v) } }, { log("누적 주행 거리를 읽지 못했습니다: ${it.message}") })
        vehicle.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) { worker.post { onOdometer(value) } }
            override fun onFailed(exception: Exception) = log("주행 거리 신호 오류: ${exception.message}")
        })

        // 전기차인지는 배터리 잔량을 읽을 수 있는지로 판단한다.
        vehicle.getEvBattery().getEvBatteryLevel({ v ->
            worker.post {
                isEv = true
                vehicle.getEvBattery().getEvBatteryCapacity({ cap -> worker.post { setBatteryScale(cap); onBattery(v) } }, { worker.post { onBattery(v) } })
                vehicle.getEvBattery().registerEvBatteryLevel(object : EvBatteryLevelListener {
                    override fun onEvBatteryLevelUpdated(value: Float?) { worker.post { onBattery(value) } }
                    override fun onFailed(exception: Exception) = log("배터리 신호 오류: ${exception.message}")
                })
                log("전기차: 배터리 잔량 변화로 실제 사용 전력을 계산합니다")
            }
        }, { worker.post { connectFuel(it) } })

        connectSeats()
    }

    /** 배터리 값이 Wh로 오면 kWh로 바꾼다. 최대 용량이 1,000보다 크면 Wh로 본다. */
    private fun setBatteryScale(capacity: Float?) {
        if (capacity != null && capacity > 1000f) batteryScale = 0.001
        debugLog("배터리 최대 용량 원시값: $capacity, 배율 $batteryScale")
    }

    private fun connectFuel(evError: Exception) {
        debugLog("배터리 잔량 없음(${evError.message}), 연비 방식으로 전환")
        isEv = false
        vehicle.getFuel().getCurrentFuelEconomy({ v -> worker.post { fuelEconomy = v?.toDouble(); changed() } }, { log("연비를 읽지 못했습니다: ${it.message}") })
        vehicle.getFuel().registerCurrentFuelEconomy(object : FuelEconomyListener {
            override fun onFuelEconomyUpdated(value: Float?) { worker.post { fuelEconomy = value?.toDouble(); changed() } }
            override fun onFailed(exception: Exception) = log("연비 신호 오류: ${exception.message}")
        })
        log("내연기관: 차가 잰 연비(km/L)로 실제 사용 연료를 계산합니다")
    }

    /**
     * 좌석 탑승 신호를 구독한다.
     * Vehicle SDK 2.3.2와 Pleos 에뮬레이터에서 차량 값 1(빈 좌석)이 true, 2(탑승)가 false로 전달되어 반대로 해석한다.
     * 실차에서도 같은지는 확인이 필요하다.
     */
    private fun connectSeats() {
        worker.post { seats.values.forEach { occupied.putIfAbsent(it, false) } }
        seats.forEach { (value, name) ->
            val area = SeatArea(value)
            vehicle.getSeat().getCurrentSeatOccupancy(area, { on ->
                worker.post {
                    occupied[name] = on == false
                    debugLog("$name 시작 신호: $on")
                    changed()
                }
                vehicle.getSeat().registerSeatOccupancy(area, object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(value: Boolean?) { worker.post { onSeat(name, value == false) } }
                    override fun onFailed(exception: Exception) = log("$name 좌석 신호 오류: ${exception.message}")
                })
            }, { debugLog("$name 좌석 읽기 실패: ${it.message}") })
        }
    }

    private fun connectGear() {
        runCatching {
            val c = Car.createCar(appContext)
            car = c
            val manager = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            manager.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(v: CarPropertyValue<*>) {
                    val value = v.value as? Int ?: return
                    worker.post { onGear(value) }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) = log("기어 신호 오류")
            }, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { log("기어 연결 실패: ${it.message}") }
    }

    // ---------- 차 신호 처리(계산 스레드) ----------

    private fun onGear(value: Int) {
        val previous = lastGear
        lastGear = value
        gear = when (value) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; VehicleGear.GEAR_DRIVE -> "D"; else -> "$value" }
        if (previous == VehicleGear.GEAR_PARK && value != VehicleGear.GEAR_PARK && trip == null) startTrip()
        changed()
    }

    private fun startTrip() {
        val t = Trip(System.currentTimeMillis())
        t.riders.add(Rider(0, DRIVER))
        occupied.filterValues { it }.keys.forEach { seat -> t.riders.add(Rider(t.nextId++, seat)) }
        trip = t
        lastReceipt = null
        lastSummary = null
        saveTrip()
        log("주행 시작: ${t.onBoard.size}명 탑승 (${t.onBoard.joinToString { it.seat }})")
    }

    private fun onSeat(name: String, nowOccupied: Boolean) {
        val before = occupied.put(name, nowOccupied)
        if (before == nowOccupied) return
        debugLog("$name 탑승: $before -> $nowOccupied")
        val t = trip ?: run { changed(); return }
        if (nowOccupied) {
            t.riders.add(Rider(t.nextId++, name))
            log("$name 탑승: 지금부터 ${t.onBoard.size}명이 나눕니다")
            saveTrip()
            return
        }
        val rider = t.onBoard.lastOrNull { it.seat == name } ?: return
        rider.onBoard = false
        saveTrip()
        val receipt = Receipt("$name 동승자가 내렸습니다", detail(rider), rider.total, System.currentTimeMillis())
        lastReceipt = receipt
        log("$name 하차: ${"%.1f".format(rider.distanceKm)}km 함께 탐, 몫 ${won(rider.total)}")
        speak("$name 동승자는 ${spokenKm(rider.distanceKm)} 함께 타셨고, 몫은 ${won(rider.total)}입니다.")
        changed()
    }

    private fun onOdometer(value: Float?) {
        val km = value?.toDouble() ?: return
        val previous = odometerKm
        odometerKm = km
        val t = trip
        // 한 번에 50km 넘게 늘어난 값은 실제 주행이 아니라 값 초기화나 신호 오류로 보고 넣지 않는다.
        if (t != null && previous != null && km - previous > MAX_STEP_KM) log("누적 거리가 한 번에 ${"%.0f".format(km - previous)}km 바뀌어 계산에서 뺐습니다")
        else if (t != null && previous != null && km > previous) {
            val delta = km - previous
            t.distanceKm += delta
            val people = t.onBoard
            people.forEach { it.distanceKm += delta }
            // 내연기관은 차가 잰 연비로 이 구간의 연료량을 구한다.
            val economy = fuelEconomy
            if (!isEv && economy != null && economy > 0) addEnergy(t, delta / economy)
            saveTrip()
        }
        changed()
    }

    private fun onBattery(value: Float?) {
        val kwh = value?.toDouble()?.times(batteryScale) ?: return
        val previous = batteryKwh
        batteryKwh = kwh
        val t = trip
        // 충전 등으로 잔량이 늘어난 경우는 비용에 넣지 않는다.
        if (t != null && previous != null && previous - kwh > MAX_STEP_KWH) log("배터리 잔량이 한 번에 ${"%.1f".format(previous - kwh)}kWh 바뀌어 계산에서 뺐습니다")
        else if (t != null && previous != null && kwh < previous) { addEnergy(t, previous - kwh); saveTrip() }
        changed()
    }

    /** 이 구간에 쓴 에너지를 지금 타고 있는 사람 수로 똑같이 나눈다. */
    private fun addEnergy(t: Trip, amount: Double) {
        val cost = amount * unitPrice
        t.energy += amount
        t.energyCost += cost
        val people = t.onBoard
        people.forEach { it.energy += amount / people.size; it.energyCost += cost / people.size }
    }

    // ---------- 운전자 조작 ----------

    /** 통행료·주차비처럼 지금 탄 사람들이 함께 낸 비용을 똑같이 나눈다. 정차 중에만 받는다. */
    fun addExtra(amount: Int) = worker.post {
        val t = trip ?: run { log("진행 중인 주행이 없습니다"); return@post }
        if (!isParked) { log("비용 추가는 기어가 P일 때만 할 수 있습니다"); return@post }
        val people = t.onBoard
        t.extraCost += amount
        people.forEach { it.extraCost += amount.toDouble() / people.size }
        saveTrip()
        log("통행료·주차비 ${won(amount.toLong())} 추가: ${people.size}명이 나눔")
    }

    /** 주행을 끝내고 전체 정산을 보여준다. 정차 중에만 끝낼 수 있다. */
    fun finish(reason: String = "직접") = worker.post {
        val t = trip ?: run { log("진행 중인 주행이 없습니다"); speak("진행 중인 주행이 없습니다."); return@post }
        if (!isParked) { log("정산은 기어가 P일 때만 할 수 있습니다"); return@post }
        t.onBoard.forEach { it.onBoard = false }
        val others = t.riders.filter { it.seat != DRIVER }
        val lines = mutableListOf(
            "주행 ${"%.1f".format(t.distanceKm)}km · ${energyText(t.energy)} · 에너지 비용 ${won(roundTo10(t.energyCost))}" +
                if (t.extraCost > 0) " · 통행료·주차비 ${won(t.extraCost.roundToLong())}" else "",
        )
        t.riders.forEach { lines.add("${it.seat}  ${"%.1f".format(it.distanceKm)}km  ${won(it.total)}") }
        val summary = Receipt("이번 주행 정산", lines, others.sumOf { it.total }, System.currentTimeMillis())
        lastSummary = summary
        lastReceipt = null
        trip = null
        saveTrip()
        log("정산 완료($reason): 동승자 몫 합계 ${won(summary.amount)}")
        val spoken = if (others.isEmpty()) "동승자가 없어 나눌 금액이 없습니다."
        else others.joinToString(" ") { "${it.seat} ${won(it.total)}." }
        speak("이번 주행은 ${spokenKm(t.distanceKm)}, 비용은 모두 ${won(t.total)}입니다. $spoken")
    }

    private fun detail(r: Rider) = listOfNotNull(
        "함께 탄 거리  ${"%.1f".format(r.distanceKm)}km",
        "에너지 몫  ${energyText(r.energy)} → ${won(roundTo10(r.energyCost))}",
        if (r.extraCost > 0) "통행료·주차비 몫  ${won(roundTo10(r.extraCost))}" else null,
    )

    fun energyText(amount: Double) = if (isEv) "%.2fkWh".format(amount) else "%.2fL".format(amount)
    private fun spokenKm(km: Double) = "%.1f킬로미터".format(km)

    // ---------- 음성 ----------

    private fun connectVoice() {
        // 서버 등록 정보 없이 쓰도록 ON_DEVICE 모드를 사용한다.
        val speech = TextToSpeech(appContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
        speech.initialize(object : TtsInitListener {
            override fun onSuccess() {
                speech.setLocale(Locale.KOREA)
                speech.addEventListener(object : EventListener {
                    override fun onStart() = Unit
                    override fun onReady() = Unit
                    override fun onDone() = Unit
                    override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) = log("음성 안내 오류: $errorType")
                    override fun onStop() = Unit
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
                    override fun onStartedRecognition() { listening = true; changed() }
                    override fun onUpdated(stt: String, completed: Boolean) { if (completed) handleSpeech(stt) }
                    override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                    override fun onEndedRecognition() { listening = false; changed() }
                    override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) { listening = false; log("음성 인식 오류: $errorType") }
                })
                stt = recognizer
                log("음성 인식 준비 완료")
            }
            override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) = log("음성 인식 시작 실패: $errorType")
        })
    }

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

    /** "정산"이 들어간 말이면 정산하고, "얼마"가 들어가면 지금까지 금액을 읽어 준다. */
    private fun handleSpeech(text: String) {
        log("들은 말: $text")
        val heard = text.replace(Regex("[\\s.,!?]"), "")
        when {
            heard.contains("정산") -> finish("음성")
            heard.contains("얼마") -> worker.post { readCurrent() }
            else -> speak("정산해 줘, 또는 지금 얼마야, 라고 말해 주세요.")
        }
    }

    private fun readCurrent() {
        val t = trip ?: run { speak("진행 중인 주행이 없습니다."); return }
        speak("지금까지 ${spokenKm(t.distanceKm)}, " + t.onBoard.joinToString(" ") { "${it.seat} ${won(it.total)}." })
    }

    private fun speak(text: String) { runCatching { tts?.speak(text, true) }.onFailure { log("음성 안내 실패: ${it.message}") } }
}
