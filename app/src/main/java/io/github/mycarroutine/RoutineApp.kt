package io.github.mycarroutine

import android.app.Application
import android.content.Intent

class RoutineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RoutineEngine.start(this)
        runCatching { startForegroundService(Intent(this, RoutineService::class.java)) }
            .onFailure { RoutineEngine.log("백그라운드 실행 시작 실패: ${it.message}") }
    }
}
