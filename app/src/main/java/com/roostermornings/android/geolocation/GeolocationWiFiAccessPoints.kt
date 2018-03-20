/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.geolocation

import android.content.Context
import android.net.wifi.WifiManager
import com.google.firebase.database.IgnoreExtraProperties
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName


/**
 * com.roostermornings.android.domain
 * Rooster Mornings Android
 *
 * Created by bscholtz on 21/08/17.
 * Copyright © 2017 Roosta Media. All rights reserved.
 */
@IgnoreExtraProperties
class GeolocationWiFiAccessPoints(context: Context) {
    @Expose
    @SerializedName("macAddress")
    private var macAddress = ""
    @Expose
    @SerializedName("signalStrength")
    private var signalStrength = 0
    @Expose
    @SerializedName("age")
    private var age = 0
    @Expose
    @SerializedName("channel")
    private var channel = 0
    @Expose
    @SerializedName("signalToNoiseRatio")
    private var signalToNoiseRatio = 0

    init {
        val wifiMan = context.applicationContext.getSystemService(
                Context.WIFI_SERVICE) as WifiManager?
        val wifiInf = wifiMan?.connectionInfo
        this.macAddress = wifiInf?.macAddress ?: ""
    }
}