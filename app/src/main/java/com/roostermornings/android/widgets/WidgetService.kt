/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.widgets

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.roostermornings.android.util.KotlinUtils

class WidgetService : Service() {

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        throw UnsupportedOperationException("Not yet implemented")
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when(intent.action) {
                Intent.ACTION_TIME_TICK -> {
                    //Update app widget
                    AlarmToggleWidget.sendUpdateBroadcast(ctx)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Register system 1 minute tick broadcast receiver
        KotlinUtils.catchAll(false) { unregisterReceiver(receiver) }
        registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK))

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        KotlinUtils.catchAll(false) { unregisterReceiver(receiver) }
    }
}
