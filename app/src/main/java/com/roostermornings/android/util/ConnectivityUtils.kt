/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.AsyncTask
import android.telephony.TelephonyManager
import android.widget.Toast

class ConnectivityUtils(val context: Context) {

    // Get the network info
    private fun getNetworkInfo(): NetworkInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo
    }

    //Check if there is any connectivity
    fun isConnected(): Boolean {
        return getNetworkInfo()?.isConnectedOrConnecting ?:false
    }

    fun isActive(makeToast: Boolean = false, operation: (Boolean) -> Unit) {

        class TaskThread : AsyncTask<String, Void, Boolean>() {
            override fun doInBackground(vararg params: String): Boolean {
                return try {
                    //InetAddress.getByName("google.com").isReachable(5000)
                    val command = "ping -c 1 google.com"
                    return Runtime.getRuntime().exec(command).waitFor() == 0
                } catch(exception: Exception) {
                    exception.printStackTrace()
                    false
                }
            }
            override fun onPostExecute(result: Boolean) {
                operation(result)
                if(makeToast && !result) {
                    Toaster.makeToast(context, "No active internet connection was found, please connect and try again.", Toast.LENGTH_LONG)
                }
            }
        }

        TaskThread().execute()
    }

    //Check if there is any connectivity to a Wifi network
    fun isConnectedWifi(): Boolean {
        return getNetworkInfo()?.isConnectedOrConnecting ?:false && getNetworkInfo()?.type == ConnectivityManager.TYPE_WIFI
    }

    //Check if there is any connectivity to a mobile network
    fun isConnectedMobile(): Boolean {
        return getNetworkInfo()?.isConnectedOrConnecting ?:false && getNetworkInfo()?.type == ConnectivityManager.TYPE_MOBILE
    }

    //Check if there is fast connectivity
    fun isConnectedFast(): Boolean {
        return getNetworkInfo()?.isConnectedOrConnecting ?:false && isConnectionFast(getNetworkInfo()?.type, getNetworkInfo()?.subtype)
    }

    //Check if the connection is fast
    fun isConnectionFast(type: Int? = -1, subType: Int? = -1): Boolean {
        when (type) {
            ConnectivityManager.TYPE_WIFI -> return true
            ConnectivityManager.TYPE_MOBILE -> when (subType) {
                TelephonyManager.NETWORK_TYPE_1xRTT -> return false // ~ 50-100 kbps
                TelephonyManager.NETWORK_TYPE_CDMA -> return false // ~ 14-64 kbps
                TelephonyManager.NETWORK_TYPE_EDGE -> return false // ~ 50-100 kbps
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> return true // ~ 400-1000 kbps
                TelephonyManager.NETWORK_TYPE_EVDO_A -> return true // ~ 600-1400 kbps
                TelephonyManager.NETWORK_TYPE_GPRS -> return false // ~ 100 kbps
                TelephonyManager.NETWORK_TYPE_HSDPA -> return true // ~ 2-14 Mbps
                TelephonyManager.NETWORK_TYPE_HSPA -> return true // ~ 700-1700 kbps
                TelephonyManager.NETWORK_TYPE_HSUPA -> return true // ~ 1-23 Mbps
                TelephonyManager.NETWORK_TYPE_UMTS -> return true // ~ 400-7000 kbps
            /*
                 * Above API level 7, make sure to set android:targetSdkVersion
                 * to appropriate level to use these
                 */
                TelephonyManager.NETWORK_TYPE_EHRPD // API level 11
                -> return true // ~ 1-2 Mbps
                TelephonyManager.NETWORK_TYPE_EVDO_B // API level 9
                -> return true // ~ 5 Mbps
                TelephonyManager.NETWORK_TYPE_HSPAP // API level 13
                -> return true // ~ 10-20 Mbps
                TelephonyManager.NETWORK_TYPE_IDEN // API level 8
                -> return false // ~25 kbps
                TelephonyManager.NETWORK_TYPE_LTE // API level 11
                -> return true // ~ 10+ Mbps
            // Unknown
                TelephonyManager.NETWORK_TYPE_UNKNOWN
                -> return false
                else
                -> return false
            }
            else -> return false
        }
    }
}