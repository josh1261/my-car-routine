package io.github.tanmankeum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** 주행 중 화면이 가려져도 주행 거리와 에너지 사용량을 계속 나눠 담도록 앱 프로세스를 유지한다. */
class TripService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("trip", "탄만큼", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "trip")
            .setContentTitle("탄만큼 계산 중")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        TripEngine.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
