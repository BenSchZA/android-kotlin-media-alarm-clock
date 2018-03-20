/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.content.ContentValues.TAG
import android.content.Context.ACCOUNT_SERVICE
import android.net.TrafficStats
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.crashlytics.android.Crashlytics
import com.facebook.FacebookSdk.getApplicationContext
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.StorageReference
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.BuildConfig
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.adapter_data.ChannelManager
import com.roostermornings.android.domain.database.Channel
import com.roostermornings.android.domain.database.ChannelRooster
import com.roostermornings.android.domain.database.SocialRooster
import com.roostermornings.android.domain.local.MetricsSyncEvent
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.geolocation.GeoHashUtils
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Flag
import com.roostermornings.android.sqlutil.AudioTableManager
import com.roostermornings.android.sqlutil.DeviceAlarmTableManager
import com.roostermornings.android.sqlutil.DeviceAudioQueueItem
import com.roostermornings.android.util.Constants
import com.roostermornings.android.util.Constants.ACCOUNT
import com.roostermornings.android.util.Constants.ACCOUNT_TYPE
import com.roostermornings.android.util.JSONPersistence
import com.roostermornings.android.util.RoosterUtils
import com.roostermornings.android.util.Toaster
import com.squareup.picasso.Picasso
import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.util.*
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Handle the transfer of data between a server and an
 * app, using the Android sync adapter framework.
 */
class DownloadSyncAdapter : AbstractThreadedSyncAdapter {

    private var channelSyncActive: Boolean = false
    private var socialSyncActive: Boolean = false

    //Firebase SDK
    @Inject lateinit var mStorageRef: StorageReference
    @Inject lateinit var mDatabaseRef: DatabaseReference
    @Inject lateinit var audioTableManager: AudioTableManager
    @Inject lateinit var deviceAlarmTableManager: DeviceAlarmTableManager
    @Inject lateinit var geoHashUtils: GeoHashUtils
    @Inject lateinit var jsonPersistence: JSONPersistence
    @Inject lateinit var channelManager: ChannelManager
    @Inject lateinit var sharedPreferences: SharedPreferences

    var firebaseUser: FirebaseUser? = null
    @Inject
    fun DownloadSyncAdapter(firebaseUser: FirebaseUser?) {
        this.firebaseUser = firebaseUser
    }

    // Global variables
    // Define a variable to contain a content resolver instance
    private var mContentResolver: ContentResolver? = null

    interface OnChannelDownloadListener {
        fun onChannelDownloadStarted(channelId: String)
        fun onChannelDownloadComplete(valid: Boolean, channelId: String)
    }

    /**
     * Set up the sync adapter
     */
    constructor(context: Context, autoInitialize: Boolean) : super(context, autoInitialize) {

        BaseApplication.roosterApplicationComponent.inject(this)

        /*
         * If your app uses a content resolver, get an instance of it
         * from the incoming Context
         */
        mContentResolver = context.contentResolver
    }


    /**
     * Set up the sync adapter. This form of the
     * constructor maintains compatibility with Android 3.0
     * and later platform versions
     */
    constructor(
            context: Context,
            autoInitialize: Boolean,
            allowParallelSyncs: Boolean) : super(context, autoInitialize, allowParallelSyncs) {
        /*
         * If your app uses a content resolver, get an instance of it
         * from the incoming Context
         */
        mContentResolver = context.contentResolver
    }

    /*
     * Specify the code you want to run in the sync adapter. The entire
     * sync adapter runs in a background thread, so you don't have to set
     * up your own background processing.
     */
    override fun onPerformSync(
            account: Account,
            extras: Bundle,
            authority: String,
            provider: ContentProviderClient,
            syncResult: SyncResult) {

        BaseApplication.roosterApplicationComponent.inject(this)

        /*
         * Put the data transfer code here.
         */
        Log.d("SyncAdapter: ", "onPerformSync()")

        firebaseUser?.let {

            UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis)
                    .setEventAndType(
                            MetricsSyncEvent.Companion.Event.SYNC(
                                    MetricsSyncEvent.Companion.Event.SYNC.Type.STARTED)))

            //Get channel and social audio content
            retrieveSocialRoosterData(getApplicationContext())
            retrieveChannelContentData(getApplicationContext())

            //Update badge count for roosters received
            BaseActivity.setBadge(getApplicationContext(), BaseApplication.getNotificationFlag(Flag.ROOSTER_COUNT.name))
            audioTableManager.updateRoosterCount()

            //Check if the user's geohash location entry is still valid
            geoHashUtils.checkUserGeoHash()
        }
    }

    private fun retrieveChannelContentData(context: Context) {
        //The oneInstanceTask creates a new thread, only if one doesn't exist already - implements 2 minute time limit
        val executor = ThreadPoolExecutor(1, 1, 2, TimeUnit.MINUTES,
                SynchronousQueue(),
                ThreadPoolExecutor.DiscardPolicy())

        class OneInstanceTask : Runnable {
            override fun run() {
                //Retrieve firebase audio files and cache to be played for next alarm

                //Check Firebase auth
                if (firebaseUser == null) {
                    Log.d(TAG, "User not authenticated on FB!")
                    return
                }

                //Get next pending alarm
                val deviceAlarm = deviceAlarmTableManager.nextPendingAlarm ?: return

                //If there is no pending alarm, don't retrieve channel content
                //Check if channel has a valid ID, else next pending alarm has no channel
                val channelId = deviceAlarm.channel
                if (channelId.isNullOrBlank()) return

                val channelReference = mDatabaseRef
                        .child("channels").child(channelId)
                channelReference.keepSynced(true)

                val channelListener = object : ValueEventListener {
                    override fun onDataChange(dataSnapshot: DataSnapshot) {

                        val channel = dataSnapshot.getValue(Channel::class.java) ?: return

                        //Check if channel exists
                        //Check if channel is active
                        if (!channel.isActive) return

                        //Check if channel has content and whether a story or not
                        //For time dependant channels, iteration should be the iteration for the day of alarm
                        val iteration: Int?
                        var tempIteration: Int?

                        if (channel.isNew_alarms_start_at_first_iteration) {
                            tempIteration = jsonPersistence.getStoryIteration(channelId)
                            if (tempIteration <= 0) tempIteration = 1
                            iteration = tempIteration
                        } else {
                            val currentCalendar = Calendar.getInstance()
                            val nextPendingCalendar = Calendar.getInstance()
                            nextPendingCalendar.timeInMillis = deviceAlarm.millis

                            tempIteration = if (channel.getCurrent_rooster_cycle_iteration() > 0)
                                channel.getCurrent_rooster_cycle_iteration()
                            else 1

                            val daysUntilAlarm = nextPendingCalendar.get(Calendar.DAY_OF_MONTH) - currentCalendar.get(Calendar.DAY_OF_MONTH)
                            iteration = if (daysUntilAlarm > 0) tempIteration + daysUntilAlarm else tempIteration
                        }

                        val channelRoosterUploadsReference = mDatabaseRef
                                .child("channel_rooster_uploads").child(channelId)

                        //Ensure latest data is pulled
                        channelRoosterUploadsReference.keepSynced(true)

                        val channelRoosterUploadsListener = object : ValueEventListener {
                            override fun onDataChange(dataSnapshot: DataSnapshot) {
                                val channelIterationMap = TreeMap<Int, ChannelRooster>()
                                //Check if node has children i.e. channelId content exists
                                if (dataSnapshot.childrenCount == 0L) return
                                //Iterate over all content children
                                for (postSnapshot in dataSnapshot.children) {
                                    val channelRooster = postSnapshot.getValue(ChannelRooster::class.java)
                                    if (channelRooster?.isActive == true) {
                                        channelIterationMap.put(channelRooster.rooster_cycle_iteration, channelRooster)
                                    }
                                }

                                if (channelIterationMap.isEmpty()) return

                                //Check head and tail of naturally sorted TreeMap for next valid channel content
                                //Check if iteration is valid in the context of channelIterationMap keys
                                val actualIterationKey = if (iteration > channelIterationMap.lastKey()
                                        || iteration < channelIterationMap.firstKey()) {
                                    channelIterationMap.firstKey()
                                } else {
                                    iteration
                                }

                                val validChannelRooster = channelManager.findNextValidChannelRooster(
                                        channelIterationMap, channel, actualIterationKey)
                                retrieveChannelContentAudio(validChannelRooster, context)
                            }

                            override fun onCancelled(databaseError: DatabaseError) {
                                //Show that an attempted download has occurred - this is used when "streaming" alarm content in AudioService
                                deviceAlarmTableManager.updateAlarmLabel(Constants.ALARM_CHANNEL_DOWNLOAD_FAILED)
                            }
                        }
                        channelRoosterUploadsReference.addListenerForSingleValueEvent(channelRoosterUploadsListener)
                    }

                    override fun onCancelled(databaseError: DatabaseError) {
                        Log.w(TAG, "loadPost:onCancelled", databaseError.toException())
                        //Show that an attempted download has occurred - this is used when "streaming" alarm content in AudioService
                        deviceAlarmTableManager.updateAlarmLabel(Constants.ALARM_CHANNEL_DOWNLOAD_FAILED)
                    }
                }
                channelReference.addListenerForSingleValueEvent(channelListener)
            }
        }
        executor.execute(OneInstanceTask())
    }

    private fun retrieveSocialRoosterData(context: Context) {
        //The oneInstanceTask creates a new thread, only if one doesn't exist already - implements 1 minute time limit
        val executor = ThreadPoolExecutor(1, 1, 2, TimeUnit.MINUTES,
                SynchronousQueue(),
                ThreadPoolExecutor.DiscardPolicy())

        class OneInstanceTask : Runnable {
            override fun run() {
                //Retrieve firebase audio files and cache to be played for next alarm

                //Check Firebase auth
                if (firebaseUser == null) {
                    Log.d(TAG, "User not authenticated on FB!")
                    return
                }

                val queueReference = mDatabaseRef
                        .child("social_rooster_queue").child(firebaseUser?.uid)
                //Ensure latest data is pulled
                queueReference.keepSynced(true)

                val socialQueueListener = object : ValueEventListener {
                    override fun onDataChange(dataSnapshot: DataSnapshot) {
                        dataSnapshot.children
                                .map { it.getValue(SocialRooster::class.java) }
                                .forEach { retrieveSocialRoosterAudio(it, context) }
                    }

                    override fun onCancelled(databaseError: DatabaseError) {
                        Log.w(TAG, "loadPost:onCancelled", databaseError.toException())
                    }
                }
                queueReference.addListenerForSingleValueEvent(socialQueueListener)
            }
        }
        executor.execute(OneInstanceTask())
    }

    fun retrieveChannelContentAudio(channelRooster: ChannelRooster?, context: Context) {

        channelRooster?.channel_uid ?: return

        //Check if audio in db, if so return and don't download
        //An issue here is that it's asynchronous AF, so you get lots of download tasks queued,
        //and then the db entry is relied upon to check for existing audio files,
        //but it is only entered on completion...
        //If download fails...? Remove db entry? but then could loop
        //New solution: ensure only one download task
        //Previous solution: insert into db and then download, updating filename on completion using:
        //mAudioTableManager.setChannelAudioFileName(channelRooster.getChannel_uid(), audioFileUniqueName);

        if (channelSyncActive) return
        if (audioTableManager.isChannelAudioURLFresh(channelRooster.channel_uid, channelRooster.audio_file_url)) {

            UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                    channel_uid = channelRooster.channel_uid,
                    audio_file_url = channelRooster.audio_file_url)
                    .setEventAndType(
                            MetricsSyncEvent.Companion.Event.SYNC(
                                    MetricsSyncEvent.Companion.Event.SYNC.Type.FRESH)))

            //Notify any listeners
            onChannelDownloadListener?.onChannelDownloadComplete(true, channelRooster.channel_uid)
            return
        }

        UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                channel_uid = channelRooster.channel_uid,
                audio_file_url = channelRooster.audio_file_url)
                .setEventAndType(
                        MetricsSyncEvent.Companion.Event.SYNC(
                                MetricsSyncEvent.Companion.Event.SYNC.Type.NOT_FRESH)))

        try {
            //https://firebase.google.com/docs/storage/android/download-files
            val audioFileRef = mStorageRef.storage.getReferenceFromUrl(channelRooster.audio_file_url)
            val audioFileUniqueName = "${Constants.FILENAME_PREFIX_ROOSTER_CONTENT}${RoosterUtils.createRandomUID(5)}.3gp"

            Crashlytics.log("Channel pre-download UID: ${channelRooster.channel_uid}")
            Crashlytics.log("Channel pre-download URL: ${channelRooster.audio_file_url}")

            //Pre-cache image to display on alarm screen, in case no internet connection
            if (!channelRooster.photo.isNullOrBlank())
                Picasso.with(getApplicationContext()).load(channelRooster.photo).fetch()

            channelSyncActive = true

            //Notify any listeners
            onChannelDownloadListener?.onChannelDownloadStarted(channelRooster.getChannel_uid())

            //Check for oversize files and report to Crashlytics
            if (isOversize(channelRooster.audio_file_url)) return

            audioFileRef.getBytes(Constants.ABSOLUTE_MAX_FILE_SIZE).addOnSuccessListener { bytes ->
                try {
                    val outputStream = context.openFileOutput(audioFileUniqueName, Context.MODE_PRIVATE)
                    outputStream.write(bytes)
                    outputStream.close()

                    //AudioService report logging
                    val file = File(context.filesDir.toString() + "/" + audioFileUniqueName)
                    Crashlytics.log("Rooster download URL: ${channelRooster.audio_file_url}")
                    Crashlytics.log("Rooster file path: ${file.path}")
                    Crashlytics.log("Rooster is file?: ${file.isFile}")
                    Crashlytics.log("Rooster channel source: ${channelRooster.name}")
                    Crashlytics.log("Rooster file size (kb): ${file.length() / 1024}")

                    Crashlytics.log("insertChannelAudioFile() started")
                    //Create new object for storing in SQL db
                    val deviceAudioQueueItem = DeviceAudioQueueItem()
                    deviceAudioQueueItem.fromChannelRooster(channelRooster, audioFileUniqueName)
                    //Could use channelRooster.getUpload_date(), but then can't use for purging files
                    deviceAudioQueueItem.date_uploaded = System.currentTimeMillis()
                    audioTableManager.insertChannelAudioFile(deviceAudioQueueItem)
                    Crashlytics.log("insertChannelAudioFile() completed")

                    Crashlytics.logException(Throwable("Sync Adapter Channel Download Report"))

                    //Send broadcast message to notify all receivers of download finished
                    val intent = Intent(Action.CHANNEL_DOWNLOAD_FINISHED.name)
                    getApplicationContext().sendBroadcast(intent)

                    UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                            channel_uid = channelRooster.channel_uid,
                            audio_file_url = channelRooster.audio_file_url,
                            audio_file_uid = audioFileUniqueName)
                            .setEventAndType(
                                    MetricsSyncEvent.Companion.Event.SYNC(
                                            MetricsSyncEvent.Companion.Event.SYNC.Type.REFRESHED)))

                    //Notify any listeners
                    onChannelDownloadListener?.onChannelDownloadComplete(true, channelRooster.getChannel_uid())

                    channelSyncActive = false
                } catch (e: Exception) {

                    UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                            channel_uid = channelRooster.channel_uid,
                            audio_file_url = channelRooster.audio_file_url,
                            audio_file_uid = audioFileUniqueName,
                            details = e.toString())
                            .setEventAndType(
                                    MetricsSyncEvent.Companion.Event.SYNC(
                                            MetricsSyncEvent.Companion.Event.SYNC.Type.FAILURE)))

                    e.printStackTrace()
                    //Show that an attempted download has occurred - this is used when "streaming" alarm content in AudioService
                    deviceAlarmTableManager.updateAlarmLabel(Constants.ALARM_CHANNEL_DOWNLOAD_FAILED)
                    channelSyncActive = false
                }
            }.addOnFailureListener {

                UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                        channel_uid = channelRooster.channel_uid,
                        audio_file_url = channelRooster.audio_file_url,
                        audio_file_uid = audioFileUniqueName,
                        details = "OnFailureListener")
                        .setEventAndType(
                                MetricsSyncEvent.Companion.Event.SYNC(
                                        MetricsSyncEvent.Companion.Event.SYNC.Type.FAILURE)))

                // Handle any errors
                //Show that an attempted download has occurred - this is used when "streaming" alarm content in AudioService
                deviceAlarmTableManager.updateAlarmLabel(Constants.ALARM_CHANNEL_DOWNLOAD_FAILED)
                channelSyncActive = false
            }

            if (BuildConfig.DEBUG) Toaster.makeToast(context, "I'm running", Toast.LENGTH_SHORT)

        } catch (e: Exception) {

            UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                    channel_uid = channelRooster.channel_uid,
                    audio_file_url = channelRooster.audio_file_url,
                    details = e.toString())
                    .setEventAndType(
                            MetricsSyncEvent.Companion.Event.SYNC(
                                    MetricsSyncEvent.Companion.Event.SYNC.Type.FAILURE)))

            Log.e(TAG, e.message)
            //Show that an attempted download has occurred - this is used when "streaming" alarm content in AudioService
            deviceAlarmTableManager.updateAlarmLabel(Constants.ALARM_CHANNEL_DOWNLOAD_FAILED)
            channelSyncActive = false
        }

    }

    private fun isOversize(fileUrl: String): Boolean {
        //Check for oversize files and report to Crashlytics
        val uri: URL
        try {
            uri = URL(fileUrl)
        } catch (e: MalformedURLException) {
            e.printStackTrace()
            return false
        }

        val ucon: URLConnection
        try {
            ucon = uri.openConnection()
            ucon.connect()
            val contentLengthStr = ucon.getHeaderField("content-length")
            val contentLength = java.lang.Long.valueOf(contentLengthStr)

            if (contentLength > Constants.MAX_ROOSTER_FILE_SIZE) {
                Crashlytics.log("File URL: $fileUrl")
                Crashlytics.log("File size: ${contentLength / 1024}")
                Crashlytics.logException(Throwable("Sync Adapter Oversize File Report"))
            }

            // If file size is greater than absolute max, abort download
            if (contentLength > Constants.ABSOLUTE_MAX_FILE_SIZE) return true
        } catch (e: Exception) {
            // File content length not readable
            Crashlytics.log("File URL: $fileUrl")
            Crashlytics.log("Content length not readable!")
            Crashlytics.logException(Throwable("Sync Adapter Oversize File Report"))
        } finally {
            try {
                val editor = sharedPreferences.edit()

                // Check and log device data usage
                val appUid = android.os.Process.myUid()
                val rxBytes = TrafficStats.getUidRxBytes(appUid)
                val txBytes = TrafficStats.getUidTxBytes(appUid)
                val previousRxBytes = sharedPreferences.getLong(Constants.APP_CUMULATIVE_RX_BYTES, 0)
                val previousTxBytes = sharedPreferences.getLong(Constants.APP_CUMULATIVE_TX_BYTES, 0)
                var appCumulativeRxBytes: Long
                var appCumulativeTxBytes: Long

                if (rxBytes > previousRxBytes)
                    appCumulativeRxBytes = previousRxBytes + (rxBytes - previousRxBytes)
                else
                    appCumulativeRxBytes = previousRxBytes + rxBytes

                if (txBytes > previousTxBytes)
                    appCumulativeTxBytes = previousTxBytes + (txBytes - previousTxBytes)
                else
                    appCumulativeTxBytes = previousTxBytes + txBytes

                // Clear cumulative data on first day of month
                if (Calendar.getInstance().get(Calendar.DAY_OF_MONTH) == 1) {
                    appCumulativeRxBytes = 0
                    appCumulativeTxBytes = 0
                }

                editor.putLong(Constants.APP_CUMULATIVE_RX_BYTES, appCumulativeRxBytes)
                editor.putLong(Constants.APP_CUMULATIVE_TX_BYTES, appCumulativeTxBytes)
                editor.apply()

                Crashlytics.log("App cumulative RX MiB: ${appCumulativeRxBytes / 1000000}")
                Crashlytics.log("App cumulative TX MiB: ${appCumulativeTxBytes / 1000000}")
                Crashlytics.logException(Throwable("App Cumulative Monthly Data Usage"))
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }
        return false
    }

    private fun retrieveSocialRoosterAudio(socialRooster: SocialRooster?, context: Context) {

        socialRooster ?: return
        if (socialSyncActive) return

        if (audioTableManager.isSocialAudioInDatabase(socialRooster.queue_id)) return

        try {

            val audioFileRef = mStorageRef.child("social_rooster_uploads/${socialRooster.audio_file_url}")
            val audioFileUniqueName = "${Constants.FILENAME_PREFIX_ROOSTER_CONTENT}${RoosterUtils.createRandomUID(5)}.3gp"
            val queueRecordReference = mDatabaseRef
                    .child("social_rooster_queue").child(firebaseUser?.uid).child(socialRooster.queue_id)

            socialSyncActive = true

            audioFileRef.getBytes(Constants.MAX_ROOSTER_FILE_SIZE).addOnSuccessListener { bytes ->
                try {
                    val outputStream = context.openFileOutput(audioFileUniqueName, Context.MODE_PRIVATE)
                    outputStream.write(bytes)
                    outputStream.close()

                    //Create new object for storing in SQL db
                    val deviceAudioQueueItem = DeviceAudioQueueItem()
                    deviceAudioQueueItem.fromSocialRooster(socialRooster, audioFileUniqueName)

                    //store in local SQLLite database
                    if (audioTableManager.insertSocialAudioFile(deviceAudioQueueItem)) {
                        //remove record of queue from FB database
                        queueRecordReference.removeValue()
                    }

                    //Pre-cache image to display on alarm screen, in case no internet connection
                    if (!socialRooster.profile_pic.isEmpty())
                        Picasso.with(getApplicationContext()).load(socialRooster.profile_pic).fetch()

                    socialSyncActive = false
                } catch (e: Exception) {
                    e.printStackTrace()
                    socialSyncActive = false
                }
            }.addOnFailureListener {
                // Handle any errors
                Toaster.makeToast(context, "Error downloading rooster.", Toast.LENGTH_SHORT)
                //remove record of queue from FB database on error
                queueRecordReference.removeValue()
                socialSyncActive = false
            }

            // For our recurring task, we'll just display a message
            if (BuildConfig.DEBUG) Toaster.makeToast(context, "I'm running", Toast.LENGTH_SHORT)

        } catch (e: Exception) {
            Log.e(TAG, e.message)
        }

    }

    companion object {

        var onChannelDownloadListener: OnChannelDownloadListener? = null

        val forceBundle: Bundle
            get() {
                val forceBundle = Bundle()
                forceBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                forceBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                return forceBundle
            }

        /**
         * Create a new dummy account for the sync adapter
         *
         * @param context The application context
         */
        fun createSyncAccount(context: Context): Account {
            // Create the account type and default account
            val newAccount = Account(
                    ACCOUNT, ACCOUNT_TYPE)
            // Get an instance of the Android account manager
            val accountManager = context.getSystemService(
                    ACCOUNT_SERVICE) as AccountManager
            /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
            if (accountManager.addAccountExplicitly(newAccount, null, null)) {
                /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call context.setIsSyncable(account, AUTHORITY, 1)
             * here.
             */
            } else {
                /*
             * The account exists or some other error occurred. LogMany this, report it,
             * or handle it internally.
             */
            }
            return newAccount
        }
    }
}
