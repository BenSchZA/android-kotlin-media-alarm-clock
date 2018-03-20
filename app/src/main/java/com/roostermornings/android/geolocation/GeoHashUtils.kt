/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.geolocation

import android.content.Context
import android.content.SharedPreferences
import android.location.Geocoder
import android.util.Log
import ch.hsr.geohash.GeoHash
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.domain.database.Channel
import com.roostermornings.android.domain.local.GeoHashChannel
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.keys.PrefsKey
import com.roostermornings.android.util.*
import retrofit.Callback
import retrofit.Response
import retrofit.Retrofit
import java.io.IOException
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * com.roostermornings.android.util
 * Rooster Mornings Android
 *
 * Created by bscholtz on 23/08/17.
 * Copyright © 2017 Roosta Media. All rights reserved.
 */

class GeoHashUtils(val context: Context) {

    /*
    One limitation of the Geohash algorithm is in attempting to utilize it to
    find points in proximity to each other based on a common prefix.
    Edge case locations close to each other but on opposite sides of the 180 degree meridian
    will result in Geohash codes with no common prefix (different longitudes for
    near physical locations). Points close by at the North and South poles will have
    very different geohashes (different longitudes for near physical locations).
     */

    @Inject
    lateinit var sharedPreferences: SharedPreferences
    @Inject
    lateinit var jsonPersistence: JSONPersistence

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    class UserGeoHashEntry {
        var geoHash: String = ""
        var mobileSource: Boolean = false
        var date: Long = 0
    }

    fun checkUserGeoHash() {
        var mUserGeoHashArray: ArrayList<UserGeoHashEntry> = ArrayList()
        if (!jsonPersistence.userGeoHashEntries.isEmpty()) {
            mUserGeoHashArray = jsonPersistence.userGeoHashEntries
        }

        val connectivityUtils = ConnectivityUtils(context)
        if(connectivityUtils.isConnected()) {
            if(connectivityUtils.isConnectedMobile()) {
                //Check that there are no mobile data entries within the last three days before refreshing
                val noMobileDataEntriesLast3Days = mUserGeoHashArray.none {
                    entry -> entry.mobileSource
                        .and(entry.date > Calendar.getInstance().timeInMillis - Constants.TIME_MILLIS_1_DAY *3)
                }
                if(noMobileDataEntriesLast3Days) refreshUserGeoHashArray(mUserGeoHashArray, true)
            } else if(connectivityUtils.isConnectedWifi()) {
                //Check that there are no entries within the last week before refreshing
                val noEntriesLastWeek = mUserGeoHashArray.none {
                    entry -> entry.date > Calendar.getInstance().timeInMillis - Constants.TIME__MILLIS_1_WEEK
                }
                if (noEntriesLastWeek) refreshUserGeoHashArray(mUserGeoHashArray, false)
            }
        }

        //Create a map of user geohash entries to cluster size, and update user's geohash appropriately
        checkUserGeoHashClusterMap(mUserGeoHashArray)
    }

    private fun refreshUserGeoHashArray(mUserGeoHashArray: ArrayList<UserGeoHashEntry>, mobileSource: Boolean) {
        val userGeoHashEntry = UserGeoHashEntry()
        userGeoHashEntry.mobileSource = mobileSource
        userGeoHashEntry.date = Calendar.getInstance().timeInMillis

        //Get user location
        val geolocationRequest = GeolocationRequest(context, false)
        val baseApplication = context.applicationContext as BaseApplication
        val call =  baseApplication.mGoogleAPIService.getGeolocation(context.resources.getString(R.string.google_geolocation_api_key), geolocationRequest)

        call.enqueue(object : Callback<GeolocationAPIResult> {
            override fun onResponse(response: Response<GeolocationAPIResult>,
                                    retrofit: Retrofit) {

                val statusCode = response.code()
                val apiResponse = response.body()

                if (statusCode == 200) {

                    //Convert location to geohash string
                    val location = apiResponse.location
                    userGeoHashEntry.geoHash = GeoHash.geoHashStringWithCharacterPrecision(location.lat ?: Double.MIN_VALUE, location.lng ?: Double.MIN_VALUE, 4)

                    //Only append new entry if it contains a geohash
                    if(userGeoHashEntry.geoHash.isNotBlank()) {
                        //Create a temporary array, of less than 5 entries, and insert current entry at beginning
                        val maxUserGeoHashArraySize = 5
                        val tempUserGeoHashArray: ArrayList<UserGeoHashEntry> = ArrayList()
                        tempUserGeoHashArray.addAll(
                                mUserGeoHashArray.filterIndexed { index, userGeoHashEntry -> (index + 1) < maxUserGeoHashArraySize })
                        tempUserGeoHashArray.add(0, userGeoHashEntry)

                        //Persist the user geohashes to shared pref
                        jsonPersistence.userGeoHashEntries = tempUserGeoHashArray
                    }
                }
            }

            override fun onFailure(t: Throwable) {
                Log.i("GeoHashUtils: ", if (StrUtils.notNullOrEmpty(t.localizedMessage)) t.localizedMessage else " ")
            }
        })
    }

    private fun checkUserGeoHashClusterMap(tempUserGeoHashArray: ArrayList<UserGeoHashEntry>) {
        //Create map of UserGeoHashEntry to cluster size
        val geoHashClusterMap: HashMap<UserGeoHashEntry, Int> = HashMap()

        tempUserGeoHashArray.forEach { entry ->
            tempUserGeoHashArray.count { entryCompare ->
                distanceBetweenGeoHash(GeoHash.fromGeohashString(entry.geoHash), GeoHash.fromGeohashString(entryCompare.geoHash)) < 200*10e3 }
                    .let { count -> geoHashClusterMap.put(entry, count) }
        }

        //Get geohash with maximum cluster size, with mobile source having a higher priority
        if(geoHashClusterMap.none { it.key.mobileSource }) {
            geoHashClusterMap.maxBy { it.value }
                    ?.let { updateGeoHash(it.key.geoHash) }
        } else {
            geoHashClusterMap.filter { it.key.mobileSource }.maxBy { it.value }
                    ?.let { updateGeoHash(it.key.geoHash) }
        }
    }

    private fun updateGeoHash(userGeoHash: String) {
        if(userGeoHash.isNotBlank()) {
            sharedPreferences.edit().putString(PrefsKey.USER_GEOHASH.name, userGeoHash).apply()

            LifeCycle.performMethodOnceInDay {
                FirebaseNetwork.updateProfileGeoHashLocation(userGeoHash)
                UserMetrics.updateGeohash(userGeoHash)
            }
        }
    }

    fun getAreaFromCoordinates(location: GeolocationAPIResult.Location): String {
        return try
        {
            Geocoder(context, Locale.getDefault())
                    .getFromLocation(location.lat!!.toDouble(), location.lng!!.toDouble(), 1)[0]
                    ?.let { address ->
                        val locality: String = address.locality
                        val adminArea: String = address.adminArea
                        val countryName: String = address.countryName
                        return "$locality, $adminArea, $countryName"
                    }
            ""
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }

    fun getGeoHashChannelsWithinInfluence() {
        val geoHashUser: String = sharedPreferences.getString(PrefsKey.USER_GEOHASH.name, "zzzz")

        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        if (StrUtils.notNullOrEmpty(fUser?.uid)) {
            val mGeoHashChannelsReference = fDB
                    .child("geohash_channels")
            mGeoHashChannelsReference.keepSynced(true)

            mGeoHashChannelsReference.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    val geoHashChannels: ArrayList<GeoHashChannel> = ArrayList()

                    dataSnapshot.children.forEach { postSnapshot ->
                    postSnapshot.getValue(GeoHashChannel::class.java)
                            ?.let { geoHashChannel -> geoHashChannels.add(geoHashChannel) } }

                    filterGeoHashChannelsWithinInfluence(geoHashChannels, geoHashUser).let {
                        onFlagGeoHashChannelsDataListener?.onDataChange(it)
                    }
                    onFlagGeoHashChannelsDataListener?.onPostExecute(true)
                }

                override fun onCancelled(databaseError: DatabaseError) {
                    onFlagGeoHashChannelsDataListener?.onCancelled()
                    onFlagGeoHashChannelsDataListener?.onPostExecute(false)
                }
            })
        }
    }

    companion object {

        // The equatorial radius of the earth in meters
        private val EARTH_EQ_RADIUS = 6378137.0
        // The meridional radius of the earth in meters
        private val EARTH_POLAR_RADIUS = 6357852.3

        //Listener for geohash node fetch
        var onFlagGeoHashChannelsDataListener: OnFlagGeoHashChannelsDataListener? = null

        interface OnFlagGeoHashChannelsDataListener {
            fun onDataChange(geoHashChannels: List<GeoHashChannel>)
            fun onCancelled()
            fun onPostExecute(success: Boolean)
        }

        fun filterGeoHashChannelsWithinInfluence(geoHashChannels: List<GeoHashChannel>, geoHashUser: String): List<GeoHashChannel> {
            //Return all geohashchannels that have at least one matching geohash with the user
            return geoHashChannels.filter {
                geoHashChannel -> geoHashChannel.g.filterNotNull()
                    .any { geohash -> geohash.length <= 3 && geoHashUser.startsWith(geohash, true) }
            }
        }

        fun filterGeoLocatedChannels(channels: ArrayList<Channel>, geoHashChannels: List<GeoHashChannel>): List<Channel> {
            //Return all channels where the user is within the range of influence
            return channels.filter {
                channel -> channel.isGeolocated
                    .and(geoHashChannels.any { geoHashChannel -> geoHashChannel.uid == channel.uid })
                    .or(!channel.isGeolocated)
            }
        }

        fun filterGeoLocatedChannels(channelsMap: HashMap<Channel, Int>, geoHashChannels: List<GeoHashChannel>): HashMap<Channel, Int> {
            //Return all channels where the user is within the range of influence
            val returnedHashMap: HashMap<Channel,Int> = HashMap()

            channelsMap.entries.filter {
                channelEntry -> channelEntry.key.isGeolocated
                    .and(geoHashChannels.any { geoHashChannel -> geoHashChannel.uid == channelEntry.key.uid })
                    .or(!channelEntry.key.isGeolocated)
            }.forEach {
                channelsMapEntry -> returnedHashMap.put(channelsMapEntry.key, channelsMapEntry.value)
            }

            return returnedHashMap
        }

        fun distanceBetweenGeoHash(geoHash1: GeoHash, geoHash2: GeoHash): Double {
            return distance(geoHash1.point.latitude, geoHash1.point.longitude, geoHash2.point.latitude, geoHash2.point.longitude)
        }

        private fun distance(lat1: Double, long1: Double, lat2: Double, long2: Double): Double {
            // Earth's mean radius in meters
            val radius = (EARTH_EQ_RADIUS + EARTH_POLAR_RADIUS) / 2
            val latDelta = Math.toRadians(lat1 - lat2)
            val lonDelta = Math.toRadians(long1 - long2)

            val a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(lonDelta / 2) * Math.sin(lonDelta / 2)
            return radius * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }
}