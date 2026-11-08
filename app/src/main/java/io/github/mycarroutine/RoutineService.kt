package io.github.mycarroutine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** 주행 중 화면이 가려져도 규칙이 계속 돌아가도록 앱 프로세스를 유지한다. */
class RoutineService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("routine", "내 차 루틴", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "routine")
            .setContentTitle("내 차 루틴 실행 중")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        RoutineEngine.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
