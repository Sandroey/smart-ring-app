package com.rea.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class GestureToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.rea.ring.TEST_ALARM") {
            Ring.attach(context)
            AlarmScheduler.scheduleIn(context, 5000L)
            android.util.Log.d("ReaRing", "test alarm scheduled in 5s")
            return
        }
        if (intent?.action == "com.rea.ring.TEST_PHOTO") {
            Ring.takePhotoByGesture(context)
            return
        }
        val set = intent?.getIntExtra("set", -1) ?: -1
        val mode = if (set in 0..2) {
            GesturePrefs.setMode(context, set)
            Ring.updateGestureMode(context)
            set
        } else GesturePrefs.toggle(context)
        Toast.makeText(context, "体感控制：" + GesturePrefs.label(mode), Toast.LENGTH_SHORT).show()
    }
}