package io.github.tanmankeum

import android.app.Application
import android.content.Intent

class TripApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TripEngine.start(this)
        runCatching { startForegroundService(Intent(this, TripService::class.java)) }
            .onFailure { TripEngine.log("백그라운드 실행 시작 실패: ${it.message}") }
    }
}
