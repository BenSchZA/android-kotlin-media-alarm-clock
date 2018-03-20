/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.service

import android.accounts.Account
import android.annotation.TargetApi
import android.app.*
import android.content.*
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.media.*
import android.media.AudioAttributes.USAGE_ALARM
import android.net.Uri
import android.os.*
import android.support.v4.app.NotificationCompat
import android.support.v4.content.WakefulBroadcastReceiver
import android.support.v4.media.AudioAttributesCompat
import com.crashlytics.android.Crashlytics
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.crash.FirebaseCrash
import com.google.firebase.storage.FirebaseStorage
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.DeviceAlarmFullScreenActivity
import com.roostermornings.android.activity.MessageStatusFragmentActivity
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.adapter_data.ChannelManager
import com.roostermornings.android.domain.database.ChannelRooster
import com.roostermornings.android.domain.local.MetricsSyncEvent
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Extra
import com.roostermornings.android.keys.NotificationChannelID
import com.roostermornings.android.keys.NotificationID
import com.roostermornings.android.realm.RealmAlarmFailureLog
import com.roostermornings.android.sqlutil.*
import com.roostermornings.android.util.*
import com.roostermornings.android.util.Constants.AUDIO_TYPE_CHANNEL
import com.roostermornings.android.util.Constants.AUDIO_TYPE_SOCIAL
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

// Service to manage playing and pausing audio during Rooster alarm
class AudioService : Service() {

    // Binder given to clients
    private val mBinder = LocalBinder()

    private lateinit var endAudioServiceBroadcastReceiver: BroadcastReceiver
    private lateinit var snoozeAudioServiceBroadcastReceiver: BroadcastReceiver

    private val alarmFinishTimerHandler = Handler()

    private val mediaPlayerDefault = MediaPlayer()
    private val mediaPlayerRooster = MediaPlayer()
    private val streamMediaPlayer = MediaPlayer()
    private var failsafeRingtone: Ringtone? = null

    private var audioItem: DeviceAudioQueueItem? = DeviceAudioQueueItem()

    private val mThis = this

    private var wakefulIntent: Intent? = Intent()
    private var intent: Intent? = Intent()

    private var channelAudioItems: ArrayList<DeviceAudioQueueItem> = ArrayList()
    private var socialAudioItems: ArrayList<DeviceAudioQueueItem> = ArrayList()
    private val audioTableManager = AudioTableManager(this)
    private val deviceAlarmTableManager = DeviceAlarmTableManager(this)
    private val deviceAlarmController = DeviceAlarmController(this)

    private var currentAlarmCycle = 1
    private var limitAlarmTime: Boolean = false

    private var alarmChannelUid = ""
    private var alarmUid = ""
    private var alarmCount = 1
    private var alarmPosition = 1

    private var millisSlot = -1L

    private var currentPositionRooster = 0

    private var activeInternetConnection: Boolean? = null

    @Inject
    lateinit var mAccount: Account
    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject
    @field:Named("default") lateinit var defaultSharedPreferences: SharedPreferences
    @Inject lateinit var jsonPersistence: JSONPersistence
    @Inject
    lateinit var channelManager: ChannelManager
    @Inject lateinit var realmAlarmFailureLog: RealmAlarmFailureLog
    var firebaseUser: FirebaseUser? = null
    @Inject lateinit var connectivityUtils: ConnectivityUtils

    companion object {
        private val audioItems = ArrayList<DeviceAudioQueueItem>()
        private var alarm = DeviceAlarm()
        private var mRunning = false
    }

    private val isAudioPlaying: Boolean
        get() {
            try {
                if (mediaPlayerRooster.isPlaying) return true
            } catch (e: IllegalStateException) {
                logError(e)
            }

            try {
                if (mediaPlayerDefault.isPlaying) return true
            } catch (e: IllegalStateException) {
                logError(e)
            }

            try {
                if (streamMediaPlayer.isPlaying) return true
            } catch (e: IllegalStateException) {
                logError(e)
            }

            return false
        }

    // Return next valid audio item in array
    private val nextAudioItem: DeviceAudioQueueItem
        get() = if (audioItems.size == audioItems.indexOf(audioItem) + 1) {
            audioItems[0]
        } else {
            audioItems[audioItems.indexOf(audioItem) + 1]
        }

    // Return previous valid audio item in array
    private val previousAudioItem: DeviceAudioQueueItem
        get() = if (audioItems.indexOf(audioItem) - 1 < 0) {
            audioItems[audioItems.size - 1]
        } else {
            audioItems[audioItems.indexOf(audioItem) - 1]
        }

    // Set timer to kill alarm after 5 minutes
    private val alarmFinishTimerRunnable = Runnable { notifyActivityTimesUp() }

    @Inject
    fun AudioService(firebaseUser: FirebaseUser?) {
        mThis.firebaseUser = firebaseUser
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        // Return this instance of AudioService so clients can call public methods
        val service: AudioService
            get() = this@AudioService
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    override fun onDestroy() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + ": From destroy.")

        finishTransaction()

        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        mRunning = false

        BaseApplication.roosterApplicationComponent.inject(this)

        foregroundNotification("Alarm audio starting")

        // Catch all uncaught exceptions
        Thread.setDefaultUncaughtExceptionHandler { _, e -> logError(e) }

        failsafeRingtone = RingtoneManager.getRingtone(baseContext,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))

        // Register broadcast receivers for external access
        registerEndAudioServiceBroadcastReceiver()
        registerSnoozeAudioServiceBroadcastReceiver()

        // Register user data for Crashlytics reports
        Crashlytics.setUserIdentifier(firebaseUser?.uid)
        Crashlytics.setUserEmail(firebaseUser?.email?:"")
        Crashlytics.setUserName(firebaseUser?.displayName?:"")
    }

    private fun logError(e: Throwable) {
        e.printStackTrace()
        FirebaseCrash.log(e.toString())
        Crashlytics.log(e.toString())
    }

    private fun registerEndAudioServiceBroadcastReceiver() {
        endAudioServiceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val method = Thread.currentThread().stackTrace[2].methodName
                if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + ": From receiver.")

                endService()
                // Start Rooster to show on wakeup
                val homeIntent = Intent(mThis, MessageStatusFragmentActivity::class.java)
                homeIntent.flags = FLAG_ACTIVITY_NEW_TASK
                startActivity(homeIntent)
            }
        }
        val intentFilter = IntentFilter()
        intentFilter.addAction(Action.END_AUDIO_SERVICE.name)
        registerReceiver(endAudioServiceBroadcastReceiver, intentFilter)
    }

    private fun registerSnoozeAudioServiceBroadcastReceiver() {
        snoozeAudioServiceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                currentAlarmCycle = 1
                startAlarmContent(intent.getStringExtra(Extra.ALARM_SET_ID.name))
                // Start fullscreen alarm activation activity
                val intentAlarmFullscreen = Intent(mThis, DeviceAlarmFullScreenActivity::class.java)
                intentAlarmFullscreen.addFlags(FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                mThis.startActivity(intentAlarmFullscreen)
            }
        }
        val snoozeIntentFilter = IntentFilter()
        snoozeIntentFilter.addAction(Action.SNOOZE_ACTIVATION.name)
        registerReceiver(snoozeAudioServiceBroadcastReceiver, snoozeIntentFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        foregroundNotification("Alarm audio starting")

        /** Unique millis slot for Realm log */
        millisSlot = intent?.getLongExtra(Extra.MILLIS_SLOT.name, -1L)?:-1L

        realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
            it.activated = true
        }

        // Only attempt to start alarm once, in case of multiple conflicting alarms
        if (!mRunning) {
            mRunning = true

            realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                it.running = true
            }

            connectivityUtils.isActive { active ->
                realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                    it.internet = active
                    activeInternetConnection = active
                }
            }

            // Start fullscreen alarm activation activity
            val intentAlarmFullscreen = Intent(mThis, DeviceAlarmFullScreenActivity::class.java)
            intentAlarmFullscreen.addFlags(FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intentAlarmFullscreen.putExtra(Extra.REQUEST_CODE.name, intent?.getIntExtra(Extra.REQUEST_CODE.name, -1))
            intentAlarmFullscreen.putExtra(Extra.MILLIS_SLOT.name, millisSlot)
            mThis.startActivity(intentAlarmFullscreen)

            // Get wakeful intent of DeviceAlarmReceiver to enable finishing it in endService()
            wakefulIntent = intent?.getParcelableExtra(Extra.WAKEFUL_INTENT.name)
            mThis.intent = intent

            // If no audio playing already, start audio content, or default alarm tone
            try {
                if (!isAudioPlaying) {
                    if (intent != null
                            && StrUtils.notNullOrEmpty(intent.getStringExtra(Extra.ALARM_SET_ID.name))) {
                        startAlarmContent(intent.getStringExtra(Extra.ALARM_SET_ID.name))
                    } else {
                        startDefaultAlarmTone()
                    }
                }
            } catch (e: IllegalStateException) {
                logError(e)
                startDefaultAlarmTone()
            }

        }

        return Service.START_STICKY
    }

    fun updateAlarmUI(defaultAlarm: Boolean) {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Send broadcast message to notify all receivers of new data, in this case UI data
        val intent = Intent(Action.ALARM_DISPLAY.name)
        if (!defaultAlarm) {
            val bundle = Bundle()
            bundle.putSerializable("audioItem", audioItem)
            bundle.putInt("alarmPosition", alarmPosition)
            bundle.putInt("alarmCount", alarmCount)
            bundle.putBoolean("multipleAudioFiles", audioItems.size > 1)
            intent.putExtras(bundle)
        }
        sendBroadcast(intent)
    }

    private fun startAlarmContent(alarmUid: String) {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Check if audio already playing
        if (isAudioPlaying) return

        //Check if old content exists
        if (audioItems.size > 0) {
            playAlarmRoosters()
            return
        }

        // Check if relevant alarm data exists
        if (StrUtils.notNullOrEmpty(alarmUid) && deviceAlarmTableManager.getAlarmSet(alarmUid) != null) {
            alarm = deviceAlarmTableManager.getAlarmSet(alarmUid)[0]
            this.alarmChannelUid = alarm.channel
            this.alarmUid = alarmUid
        } else {
            startDefaultAlarmTone()
            return
        }

        UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                channel_uid = alarmChannelUid)
                .setEventAndType(
                        MetricsSyncEvent.Companion.Event.ALARM(
                                MetricsSyncEvent.Companion.Event.ALARM.Type.STARTED)))

        // Check if Social and Channel alarm content exists, else startDefaultAlarmTone
        channelAudioItems = audioTableManager.extractAlarmChannelAudioFiles(mThis.alarmChannelUid)
        socialAudioItems = audioTableManager.extractUnheardSocialAudioFiles()

        if(channelAudioItems.isNotEmpty()) {
            realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                it.content = true
            }
        }

        if(StrUtils.notNullOrEmpty(mThis.alarmChannelUid)) {
            realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                it.channel = true
            }
        }

        // Check if user setting limit alarm time enabled
        this.limitAlarmTime = defaultSharedPreferences.getBoolean(Constants.USER_SETTINGS_LIMIT_ALARM_TIME, true)
        if (limitAlarmTime) startTimer()
        this.currentAlarmCycle = 1

        try {
            // If alarm channel is not empty and channel audioitems is empty, try download
            if (StrUtils.notNullOrEmpty(mThis.alarmChannelUid) && channelAudioItems.isEmpty()) {

                logAlarmActivation(false)

                // Check for an active internet connection
                if(activeInternetConnection != null) {
                    switchOnActiveInternetConnection(activeInternetConnection)
                } else {
                    connectivityUtils.isActive { active ->
                        switchOnActiveInternetConnection(active)
                    }
                }
            } else {
                logAlarmActivation(true)
                compileAudioItemContent()
            }
        } catch (e: NullPointerException) {
            logError(e)
            compileAudioItemContent()
        }
    }

    private fun switchOnActiveInternetConnection(active: Boolean?) {
        if(active == true) {
            // Download any social or channel audio files
            attemptContentUriRetrieval(alarm)

            realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                it.stream = true
            }
        } else {
            compileAudioItemContent()
        }
    }

    private fun logAlarmActivation(dataLoaded: Boolean) {
        if (channelAudioItems.isNotEmpty() && socialAudioItems.isNotEmpty() ) {

            FA.LogMany(FA.Event.alarm_activated::class.java,
                    arrayOf(FA.Event.alarm_activated.Param.channel_content_received,
                            FA.Event.alarm_activated.Param.social_content_received,
                            FA.Event.alarm_activated.Param.data_loaded),
                    arrayOf(channelAudioItems.size, socialAudioItems.size, dataLoaded))

        } else if (channelAudioItems.isNotEmpty() ) {

            FA.LogMany(FA.Event.alarm_activated::class.java,
                    arrayOf(FA.Event.alarm_activated.Param.channel_content_received,
                            FA.Event.alarm_activated.Param.data_loaded),
                    arrayOf(channelAudioItems.size, dataLoaded))

        } else if (socialAudioItems.isNotEmpty() ) {

            FA.LogMany(FA.Event.alarm_activated::class.java,
                    arrayOf(FA.Event.alarm_activated.Param.social_content_received,
                            FA.Event.alarm_activated.Param.data_loaded),
                    arrayOf(socialAudioItems.size, dataLoaded))

        }
    }

    private fun compileAudioItemContent() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // If no content exists to add to audioItems, then return now
        if (mThis.socialAudioItems.isEmpty() && mThis.channelAudioItems.isEmpty()) {
            startDefaultAlarmTone()
            return
        }

        // Try append new content to end of existing content, if it fails - fail safe and play default alarm tone
        try {
            // Reorder channel and social queue according to user preferences
            if (defaultSharedPreferences.getBoolean(Constants.USER_SETTINGS_ROOSTER_ORDER, false)) {
                if (!mThis.channelAudioItems.isEmpty()) {
                    audioItems.addAll(channelAudioItems)
                }
                // If this alarm does not allow social roosters, move on to channel content
                if (!mThis.socialAudioItems.isEmpty() && alarm.isSocial) {
                    audioItems.addAll(socialAudioItems)
                }
            } else {
                // If this alarm does not allow social roosters, move on to channel content
                if (!mThis.socialAudioItems.isEmpty() && alarm.isSocial) {
                    audioItems.addAll(socialAudioItems)
                }
                if (!mThis.channelAudioItems.isEmpty()) {
                    audioItems.addAll(channelAudioItems)
                }
            }
            if (audioItems.isEmpty()) {
                startDefaultAlarmTone()
                return
            }
        } catch (e: NullPointerException) {
            logError(e)
            startDefaultAlarmTone()
            return
        }

        playAlarmRoosters()
    }

    private fun attemptContentUriRetrieval(deviceAlarm: DeviceAlarm) {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        try {
            // Check if channel has a valid ID, else next pending alarm has no channel
            val channelId = deviceAlarm.channel
            if (!StrUtils.notNullOrEmpty(channelId)) {
                startDefaultAlarmTone()
                return
            }

            val channelRoosters = jsonPersistence.newAlarmChannelRoosters

            ChannelManager.onFlagChannelManagerDataListener = object : ChannelManager.Companion.OnFlagChannelManagerDataListener {
                override fun onChannelRoosterDataChanged(freshChannelRoosters: ArrayList<ChannelRooster>) {
                    channelRoosters.clear()
                    channelRoosters.addAll(freshChannelRoosters)
                }

                override fun onSyncFinished() {
                    for (channelRooster in channelRoosters) {
                        if (channelId == channelRooster.channel_uid) {
                            streamChannelContent(channelRooster.getAudio_file_url())
                            return
                        }
                    }
                    startDefaultAlarmTone()
                }
            }
            channelManager.refreshChannelData(channelRoosters!!)

        } catch (e: Exception) {
            logError(e)
            startDefaultAlarmTone()
        }

    }

    private fun streamChannelContent(url: String) {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        foregroundNotification("Alarm content streaming")

        // Check that URL is notNullOrEmpty
        if (!StrUtils.notNullOrEmpty(url)) {
            startDefaultAlarmTone()
            return
        }

        // Check if audio already playing
        if (isAudioPlaying) return

        val mStorageRef = FirebaseStorage.getInstance().reference
        val audioFileRef = mStorageRef.storage.getReferenceFromUrl(url)

        audioFileRef.downloadUrl.addOnSuccessListener(OnSuccessListener { downloadUrl ->
            try {
                streamMediaPlayer.reset()

                @TargetApi(21)
                if(RoosterUtils.hasLollipop()) {
                    val builder = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(USAGE_ALARM)
                            .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    streamMediaPlayer.setAudioAttributes(builder.build())
                } else {
                    val builder = AudioAttributes.Builder()
                            .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                            .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    streamMediaPlayer.setAudioAttributes(builder.build())
                }

                streamMediaPlayer.isLooping = true
                streamMediaPlayer.setDataSource(downloadUrl.toString())
            } catch (e: Exception) {
                logError(e)
                startDefaultAlarmTone()
                return@OnSuccessListener
            }

            streamMediaPlayer.setOnPreparedListener(MediaPlayer.OnPreparedListener {
                if (streamMediaPlayer.isPlaying) return@OnPreparedListener
                streamMediaPlayer.start()

                UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                        channel_uid = alarmChannelUid,
                        audio_file_url = url)
                        .setEventAndType(
                                MetricsSyncEvent.Companion.Event.ALARM(
                                        MetricsSyncEvent.Companion.Event.ALARM.Type.STREAM)))

                realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                    it.heard = true
                }

                checkStreamVolume(AudioManager.STREAM_ALARM)
                softStartAudio()

                streamMediaPlayer.setOnCompletionListener {
                    mThis.currentAlarmCycle++
                    //Increment currentAlarmCycle, only loop rooster content 5 times
                    //after which control handed to activity to endService and turn off screen
                    //                                if(currentAlarmCycle > 5) {
                    //                                    notifyActivityTimesUp();
                    //                                    streamMediaPlayer.stop();
                    //                                }
                }
            })

            streamMediaPlayer.setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.reset()
                startDefaultAlarmTone()
                true
            }

            streamMediaPlayer.prepareAsync()
        }).addOnFailureListener {
            // Handle any errors
            streamMediaPlayer.reset()
            startDefaultAlarmTone()
        }
    }

    private fun playAlarmRoosters() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Check if audio already playing
        if (isAudioPlaying) return

        // Show the number of social and channel roosters combined
        this.alarmCount = audioItems.size

        // Check conditions for playing default tone: people must wake up!
        if (audioItems.isEmpty()) {
            startDefaultAlarmTone()
            return
        }

        if (currentPositionRooster < 1) {
            alarmPosition = 0
            playRooster(audioItems[0])
        } else {
            try {
                mediaPlayerRooster.seekTo(currentPositionRooster)
                mediaPlayerRooster.start()
                // Slowly increase volume from low to current volume
                softStartAudio()
                // Send broadcast to DeviceAlarmFullScreenActivity with UI data
                updateAlarmUI(false)
            } catch (e: NullPointerException) {
                logError(e)
                playRooster(audioItems[0])
            }
        }
    }

    private fun playRooster(audioItem: DeviceAudioQueueItem?) {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        foregroundNotification("Alarm content playing")

        mThis.audioItem = audioItem

        if (audioItem == null) {
            startDefaultAlarmTone()
            return
        }

        //Increment currentAlarmCycle, only loop rooster content 5 times
        //after which control handed to activity to endService and turn off screen
        //        if(currentAlarmCycle > 5) {
        //            notifyActivityTimesUp();
        //            return;
        //        }

        // Set media player to alarm volume
        @TargetApi(21)
        if(RoosterUtils.hasLollipop()) {
            val builder = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(USAGE_ALARM)
                    .setLegacyStreamType(AudioManager.STREAM_ALARM)
            mediaPlayerRooster.setAudioAttributes(builder.build())
        } else {
            val builder = AudioAttributes.Builder()
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_ALARM)
            mediaPlayerRooster.setAudioAttributes(builder.build())
        }

        // Check stream volume above minimum
        checkStreamVolume(AudioManager.STREAM_ALARM)

        // Set alarm count display
        alarmPosition = audioItems.indexOf(mThis.audioItem) + 1
        // Send broadcast to DeviceAlarmFullScreenActivity with UI data
        updateAlarmUI(false)

        val file = File(filesDir.toString() + "/" + audioItem.filename)

        try {
            // AudioService report logging
            Crashlytics.log("Rooster file path: " + file.path)
            Crashlytics.log("Rooster is file?: " + file.isFile.toString())
            Crashlytics.log("Rooster type: " + audioItem.type.toString())
            if (audioItem.type == Constants.AUDIO_TYPE_CHANNEL && StrUtils.notNullOrEmpty(alarm.channel)) Crashlytics.log("Rooster channel source: " + alarm.channel)
            Crashlytics.log("Rooster file size (kb): " + (file.length() / 1024).toString())

            mediaPlayerRooster.reset()
            mediaPlayerRooster.setDataSource(file.path)

            mediaPlayerRooster.setOnPreparedListener {
                mediaPlayerRooster.start()

                realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                    it.heard = true
                }

                if (alarmPosition == 1 && currentAlarmCycle == 1) {
                    // Slowly increase volume from low to current volume
                    softStartAudio()
                    when(audioItem.type) {
                        AUDIO_TYPE_SOCIAL -> {
                            FA.Log(FA.Event.social_rooster_unique_play::class.java, null, null)
                            FA.Log(FA.Event.social_rooster_play::class.java, null, null)
                        }
                        AUDIO_TYPE_CHANNEL -> {
                            FA.Log(FA.Event.channel_unique_play::class.java,
                                    FA.Event.channel_unique_play.Param.channel_title, audioItem.queue_id)
                            FA.Log(FA.Event.channel_play::class.java,
                                    FA.Event.channel_unique_play.Param.channel_title, audioItem.queue_id)
                        }
                    }

                    UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                            channel_uid = alarmChannelUid,
                            audio_file_url = audioItem.source_url,
                            audio_file_uid = audioItem.filename)
                            .setEventAndType(
                                    MetricsSyncEvent.Companion.Event.ALARM(
                                            MetricsSyncEvent.Companion.Event.ALARM.Type.ACTIVATION)))
                } else {
                    when(audioItem.type) {
                        AUDIO_TYPE_SOCIAL -> {
                            FA.Log(FA.Event.social_rooster_play::class.java, null, null)
                        }
                        AUDIO_TYPE_CHANNEL -> {
                            FA.Log(FA.Event.channel_play::class.java,
                                    FA.Event.channel_unique_play.Param.channel_title, audioItem.queue_id)
                        }
                    }
                }

                mediaPlayerRooster.setOnCompletionListener(MediaPlayer.OnCompletionListener {
                    // Set audio file entry in SQL db as listened; to be removed when AudioService ends
                    audioTableManager.setListened(mThis.audioItem)
                    // Check if at end of queue, else play next file
                    if (audioItems.isEmpty()) {
                        startDefaultAlarmTone()
                        return@OnCompletionListener
                    }
                    if (audioItems.size == audioItems.indexOf(audioItem) + 1) {
                        playRooster(audioItems[0])
                        mThis.currentAlarmCycle++
                    } else {
                        playRooster(nextAudioItem)
                    }
                })
            }

            mediaPlayerRooster.setOnErrorListener(MediaPlayer.OnErrorListener { mediaPlayer, what, extra ->
                // set audio file entry in SQL db as listened; to be removed when AudioService ends
                audioTableManager.setListened(mThis.audioItem)
                // Check if at end of queue, else play next file
                if (audioItems.isEmpty()) {
                    startDefaultAlarmTone()
                    return@OnErrorListener true
                }
                if (audioItems.size == audioItems.indexOf(audioItem) + 1) {
                    // If an error occurs on the last queue item, assume error on all and start default alarm tone as fail safe
                    startDefaultAlarmTone()
                } else {
                    playRooster(nextAudioItem)
                }
                true
            })

            // Prepare mediaplayer on new thread: onCompletion or onError listener called
            mediaPlayerRooster.prepareAsync()

        } catch (e: IOException) {
            logError(e)
            // Social rooster will never play... let's not go here
            // Delete file
            // Delete record from AudioTable SQL DB
            audioTableManager.setListened(audioItem)

            // Delete record from arraylist
            audioItems.remove(audioItem)

            // If an error occurs on the last queue item, assume error on all and start default alarm tone as fail safe
            startDefaultAlarmTone()
        }

    }

    fun skipNext() {
        if (audioItems.isEmpty()) {
            startDefaultAlarmTone()
            return
        }
        try {
            if (mediaPlayerRooster.isPlaying) mediaPlayerRooster.stop()
            currentPositionRooster = 0
            // Set audio file entry in SQL db as listened; to be removed when AudioService ends
            audioTableManager.setListened(mThis.audioItem)
            // Play next rooster
            playRooster(nextAudioItem)
        } catch (e: NullPointerException) {
            logError(e)
        }

    }

    fun skipPrevious() {
        if (audioItems.isEmpty()) {
            startDefaultAlarmTone()
            return
        }
        try {
            if (mediaPlayerRooster.isPlaying) mediaPlayerRooster.stop()
            currentPositionRooster = 0
            // Set audio file entry in SQL db as listened; to be removed when AudioService ends
            audioTableManager.setListened(mThis.audioItem)
            // Play previous rooster
            playRooster(previousAudioItem)
        } catch (e: NullPointerException) {
            logError(e)
        }

    }

    private fun processAudioEntries() {
        // Ensure partially listened channels and roosters are set as listened
        try {
            if (mThis.audioItem!!.type == Constants.AUDIO_TYPE_CHANNEL) {
                audioTableManager.setListened(mThis.audioItem)
            } else if (mThis.audioItem!!.type == Constants.AUDIO_TYPE_SOCIAL) {
                audioTableManager.setListened(mThis.audioItem)
            }
        } catch (e: NullPointerException) {
            logError(e)
        }

        // For all listened channels
        for (audioItem in audioTableManager.selectListenedByChannel(alarm.channel)) {
            try {
                if (audioItem.type == Constants.AUDIO_TYPE_CHANNEL) {
                    channelManager.incrementChannelStoryIteration(audioItem.queue_id)
                }
            } catch (e: NullPointerException) {
                logError(e)
            }

        }
    }

    private fun purgeAudioFiles() {
        // Delete all files not contained in SQL db
        val files = filesDir.listFiles { _, name -> name.contains(Constants.FILENAME_PREFIX_ROOSTER_CONTENT) }
        val audioFileNames = audioTableManager.extractAllAudioFileNames()
        if (audioFileNames != null) {
            files.filterNot { audioFileNames.contains(it.name) }.forEach { file -> file.delete() }
        }
    }

    fun dismissAlarm() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Log status of alarm activation to Firebase event
        try {
            FA.LogMany(FA.Event.alarm_dismissed::class.java,
                    arrayOf(FA.Event.alarm_dismissed.Param.alarm_activation_cycle_count, FA.Event.alarm_dismissed.Param.alarm_activation_index, FA.Event.alarm_dismissed.Param.alarm_activation_total_roosters),
                    arrayOf<Any>(currentAlarmCycle, audioItems.indexOf(audioItem) + 1, channelAudioItems.size + socialAudioItems.size))
        } catch (e: NullPointerException) {
            logError(e)
        }

        endService()
    }

    private fun finishTransaction() {
        // AudioService report logging
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)
        Crashlytics.logException(Throwable("AudioService Report"))

        /* Process audio files - set channel persisted iterations,
            delete all channel audio files/SQL entries,
            remove files not contained in SQL db */
        processAudioEntries()
        purgeAudioFiles()

        // Unregister all broadcast receivers
        try {
            unregisterReceiver(endAudioServiceBroadcastReceiver)
        } catch (e: IllegalArgumentException) {
            logError(e)
        }

        try {
            unregisterReceiver(snoozeAudioServiceBroadcastReceiver)
        } catch (e: IllegalArgumentException) {
            logError(e)
        }

        // Close Realm object
        realmAlarmFailureLog.closeRealm()

        // Delete audio records from arraylist
        audioItems.clear()
        // Clear variables
        alarmCount = 0
        alarmPosition = 0
        currentPositionRooster = 0

        // Release media player instances
        releaseMediaPlayers()
    }

    private fun endService() {
        //https://stackoverflow.com/questions/17146822/when-is-a-started-and-bound-service-destroyed

        // AudioService report logging
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Ensure no alarms still playing...
        stopVibrate()
        stopAlarmAudio()

        // Clear timer callbacks - this timer puts a limit on default or streamed audio duration, whereas normal operation is limited to 5 cycles
        alarmFinishTimerHandler.removeCallbacks(alarmFinishTimerRunnable)

        // Clear forground notifications that attempt to keep service alive
        stopForeground(true)

        // Complete DeviceAlarmReceiver wakeful intent
        if (wakefulIntent != null) {
            WakefulBroadcastReceiver.completeWakefulIntent(wakefulIntent)
        }

        // Stop service
        this.stopSelf()
    }

    // Set timer to kill alarm after 5 minutes
    private fun startTimer() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        alarmFinishTimerHandler.postDelayed(alarmFinishTimerRunnable, Constants.ALARM_DEFAULTTIME.toLong())
    }

    private fun notifyActivityTimesUp() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Send broadcast message to notify receiver of end of alarm to clear window hold
        val intent = Intent(Action.ALARM_TIME_UP.name)
        sendBroadcast(intent)
        snoozeAudioState()
    }

    private fun startVibrate() {
        val vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val vibratePattern = Constants.VIBRATE_PATTERN
        val vibrateRepeat = 2
        vibrator.vibrate(vibratePattern, vibrateRepeat)
    }

    private fun stopVibrate() {
        // If vibrating then cancel
        val vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            vibrator.cancel()
        }
    }

    fun snoozeAudioState() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        alarmFinishTimerHandler.removeCallbacks(alarmFinishTimerRunnable)

        deviceAlarmController.snoozeAlarm(alarmUid, false)

        val strSnoozeTime = defaultSharedPreferences.getString(Constants.USER_SETTINGS_SNOOZE_TIME, "10")

        snoozeNotification("Alarm snoozed $strSnoozeTime minutes - touch to dismiss")
        try {
            if (mediaPlayerRooster.isPlaying) {
                mediaPlayerRooster.pause()
                currentPositionRooster = mediaPlayerRooster.currentPosition
            }
        } catch (e: Exception) {
            logError(e)
            stopAlarmAudio()
        }

        try {
            if (streamMediaPlayer.isPlaying) {
                streamMediaPlayer.stop()
            }
        } catch (e: Exception) {
            logError(e)
            stopAlarmAudio()
        }

        try {
            if (mediaPlayerDefault.isPlaying) {
                mediaPlayerDefault.stop()
            }
        } catch (e: Exception) {
            logError(e)
            stopAlarmAudio()
        }

        // Stop default audio fail-safe
        stopFailsafe()

        // If vibrating then cancel
        val vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            vibrator.cancel()
        }

        try {
            if (!audioItems.isEmpty()) {
                FA.LogMany(FA.Event.alarm_snoozed::class.java,
                        arrayOf(FA.Event.alarm_snoozed.Param.alarm_activation_cycle_count, FA.Event.alarm_snoozed.Param.alarm_activation_index, FA.Event.alarm_snoozed.Param.alarm_activation_total_roosters),
                        arrayOf<Any>(currentAlarmCycle, audioItems.indexOf(audioItem) + 1, channelAudioItems.size + socialAudioItems.size))
            }
        } catch (e: NullPointerException) {
            logError(e)
        }

    }

    private fun startDefaultAlarmTone() {
        var method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + " not started.")

        try {
            // Check if audio already playing
            if (isAudioPlaying) return
            if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + " started.")

            realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                it.def = true
            }

            UserMetrics.logSyncEvent(MetricsSyncEvent(timestamp = Calendar.getInstance().timeInMillis,
                    channel_uid = alarmChannelUid)
                    .setEventAndType(
                            MetricsSyncEvent.Companion.Event.ALARM(
                                    MetricsSyncEvent.Companion.Event.ALARM.Type.DEFAULT)))

            // If channel UID attached to alarm, content should have played
            val failure = StrUtils.notNullOrEmpty(alarm.channel) && !InternetHelper.noInternetConnection(this)

            foregroundNotification("Alarm ringtone playing")

            updateAlarmUI(true)

            val strRingtonePreference = defaultSharedPreferences.getString(Constants.USER_SETTINGS_DEFAULT_TONE, "android.intent.extra.ringtone.DEFAULT_URI")

            val ringtoneManager = RingtoneManager(this)
            ringtoneManager.setType(RingtoneManager.TYPE_ALL)

            var notification: Uri? = Uri.parse(strRingtonePreference)
            // In case alarm tone URI does not exist
            // Ringtonemanager used to check if exists: from Android docs:
            // If the given URI cannot be opened for any reason, this method will attempt to fallback on another sound.
            // If it cannot find any, it will return null.
            if (RingtoneManager.getRingtone(this, notification) == null || ringtoneManager.getRingtonePosition(notification) < 0) {
                notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (notification == null) {
                    notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    if (notification == null) {
                        notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    }
                }
            }

            // Check stream volume above minimum
            checkStreamVolume(AudioManager.STREAM_ALARM)

            // Start audio stream
            try {
                mediaPlayerDefault.reset()

                @TargetApi(21)
                if(RoosterUtils.hasLollipop()) {
                    val builder = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(USAGE_ALARM)
                            .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    mediaPlayerDefault.setAudioAttributes(builder.build())
                } else {
                    val builder = AudioAttributes.Builder()
                            .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                            .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    mediaPlayerDefault.setAudioAttributes(builder.build())
                }

                mediaPlayerDefault.setDataSource(this, notification!!)
                mediaPlayerDefault.isLooping = true

                mediaPlayerDefault.setOnPreparedListener(MediaPlayer.OnPreparedListener {
                    method = Thread.currentThread().stackTrace[2].methodName
                    if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + " prepared listener")

                    if (mediaPlayerRooster.isPlaying) return@OnPreparedListener
                    mediaPlayerDefault.start()

                    realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                        it.heard = true
                    }

                    // Start timer to kill after 5 minutes
                    startTimer()
                })
                mediaPlayerDefault.setOnErrorListener { mediaPlayer, what, extra ->
                    method = Thread.currentThread().stackTrace[2].methodName
                    if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + " error listener")

                    startFailsafe()
                    true
                }

                // Must be called after listeners created
                mediaPlayerDefault.prepareAsync()

            } catch (e: Exception) {
                method = Thread.currentThread().stackTrace[2].methodName
                if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + " catch 1")
                logError(e)

                startFailsafe()
            }

        } catch (e: Exception) {
            method = Thread.currentThread().stackTrace[2].methodName
            if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method + " catch 2")
            logError(e)

            startFailsafe()
        }

    }

    private fun startFailsafe() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        checkStreamVolume(AudioManager.STREAM_RING)
        // Do everything possible to wake user up at this stage
        startVibrate()

        // Start failsafe ringtone
        if (failsafeRingtone == null) {
            Crashlytics.log("Is null")
            failsafeRingtone = RingtoneManager.getRingtone(baseContext,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            Crashlytics.log(failsafeRingtone?.getTitle(baseContext)?:"")
            failsafeRingtone?.play()
        }
        // Check if playing, else try again
        if (failsafeRingtone != null && !failsafeRingtone!!.isPlaying) {
            Crashlytics.log("Not null and not playing " + failsafeRingtone!!.getTitle(baseContext))
            failsafeRingtone?.play()
        }

        if(failsafeRingtone != null && failsafeRingtone!!.isPlaying) {
            realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                it.heard = true
                it.failsafe = true
            }
        } else {
            realmAlarmFailureLog.getAlarmFailureLogMillisSlot(millisSlot) {
                it.failsafe = true
            }
        }
    }

    private fun stopFailsafe() {
        if (failsafeRingtone != null && failsafeRingtone!!.isPlaying) failsafeRingtone?.stop()
    }

    private fun checkStreamVolume(streamType: Int) {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Ensure audio volume is acceptable - as set in user settings
        // Max volume seems to be integer 7
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audio.getStreamVolume(streamType)
        val maxVolume = audio.getStreamMaxVolume(streamType)
        val minVolume = (maxVolume * returnUserSettingAlarmMinimumVolume()).toInt()
        if (currentVolume < minVolume) {
            audio.setStreamVolume(streamType, minVolume, 0)
        }
    }

    private fun returnUserSettingAlarmMinimumVolume(): Float {
        // Ensure alarm volume at least equal to user's minimum alarm volume setting
        try {
            val alarmVolumeArrayEntries = resources.getStringArray(R.array.user_settings_alarm_volume_entry_values)

            var alarmMinimumVolume = 0.4f

            alarmMinimumVolume = when {
                alarmVolumeArrayEntries[0] == defaultSharedPreferences.getString(Constants.USER_SETTINGS_ALARM_VOLUME, "") -> 0.0f
                alarmVolumeArrayEntries[1] == defaultSharedPreferences.getString(Constants.USER_SETTINGS_ALARM_VOLUME, "") -> 0.2f
                alarmVolumeArrayEntries[2] == defaultSharedPreferences.getString(Constants.USER_SETTINGS_ALARM_VOLUME, "") -> 0.4f
                alarmVolumeArrayEntries[3] == defaultSharedPreferences.getString(Constants.USER_SETTINGS_ALARM_VOLUME, "") -> 0.6f
                alarmVolumeArrayEntries[4] == defaultSharedPreferences.getString(Constants.USER_SETTINGS_ALARM_VOLUME, "") -> 0.8f
                alarmVolumeArrayEntries[5] == defaultSharedPreferences.getString(Constants.USER_SETTINGS_ALARM_VOLUME, "") -> 1.0f
                else -> 0.4f
            }

            return alarmMinimumVolume
        } catch (e: NullPointerException) {
            logError(e)
            return 0.4f
        }

    }

    private fun softStartAudio() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        // Ramp up the audio linearly
        // 3    /-------------------
        // 2   /
        // 1  /
        // 0 /

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val timePeriod = Integer.valueOf(defaultSharedPreferences.getString(Constants.USER_SETTINGS_ALARM_VOLUME_INCREMENT_DURATION, "5")).toFloat()
        val tempTimeInterval = timePeriod / currentVolume.toFloat() * 1000
        val timeInterval = if (tempTimeInterval > 100) tempTimeInterval else 100f

        val scheduler = Executors.newSingleThreadScheduledExecutor()

        scheduler.scheduleAtFixedRate(object : Runnable {
            internal var volume = 1
            override fun run() {
                if (volume > currentVolume) {
                    volume = currentVolume
                    scheduler.shutdown()
                }
                audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
                volume++
            }
        }, 0, timeInterval.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun stopAlarmAudio() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        stopForeground(true)
        // If default tone or media playing then stop

        // Stop default audio fail-safe
        stopFailsafe()

        try {
            if (mediaPlayerDefault.isPlaying) {
                mediaPlayerDefault.stop()
            }
        } catch (e: IllegalStateException) {
            logError(e)
        }

        try {
            if (mediaPlayerRooster.isPlaying) {
                mediaPlayerRooster.stop()
                currentPositionRooster = 0
            }
        } catch (e: IllegalStateException) {
            logError(e)
        }

        try {
            if (streamMediaPlayer.isPlaying) {
                streamMediaPlayer.stop()
            }
        } catch (e: IllegalStateException) {
            logError(e)
        }

    }

    private fun releaseMediaPlayers() {
        val method = Thread.currentThread().stackTrace[2].methodName
        if (StrUtils.notNullOrEmpty(method)) Crashlytics.log(method)

        mediaPlayerDefault.release()
        mediaPlayerRooster.release()
        streamMediaPlayer.release()
    }

    private fun foregroundNotification(state: String) {
        // Notification used to attempt to stop Android OS from killing service, and for user feedback

        val launchIntent = Intent(this, MyAlarmsFragmentActivity::class.java)
        launchIntent.putExtra(Extra.ALARM_SET_ID.name, alarmUid)
        launchIntent.action = Action.CANCEL_SNOOZE.name

        val pendingIntent = PendingIntent.getActivity(this, 0,
                launchIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val broadcastIntent = Intent(Action.END_AUDIO_SERVICE.name)
        val broadcastPendingIntent = PendingIntent.getBroadcast(this, 0, broadcastIntent, 0)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // https://stackoverflow.com/questions/45395669/notifications-fail-to-display-in-android-oreo-api-26
        @TargetApi(26)
        if(RoosterUtils.hasO()) {
            val channelA = notificationManager.getNotificationChannel(NotificationChannelID.AUDIO_SERVICE.name)

            if(channelA == null) {
                val channelB = NotificationChannel(NotificationChannelID.AUDIO_SERVICE.name,
                        "AudioService",
                        NotificationManager.IMPORTANCE_DEFAULT)
                channelB.setSound(null, null)

                notificationManager.createNotificationChannel(channelB)
            }
        }

        val notification = if(RoosterUtils.hasO()) {
            NotificationCompat.Builder(this, NotificationChannelID.AUDIO_SERVICE.name)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(state)
                    .setContentIntent(pendingIntent)
                    .setDeleteIntent(broadcastPendingIntent)
                    .build()
        } else {
            NotificationCompat.Builder(this)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(state)
                    .setContentIntent(pendingIntent)
                    .setDeleteIntent(broadcastPendingIntent)
                    .build()
        }

        startForeground(NotificationID.AUDIO_SERVICE.ordinal, notification)
    }

    private fun snoozeNotification(state: String) {
        // Notification used to display snooze state and clear snooze state

        val launchIntent = Intent(this, MyAlarmsFragmentActivity::class.java)
        launchIntent.putExtra(Extra.ALARM_SET_ID.name, alarmUid)
        launchIntent.action = Action.CANCEL_SNOOZE.name

        val pendingIntent = PendingIntent.getActivity(this, 0,
                launchIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        @TargetApi(26)
        if(RoosterUtils.hasO()) {
            val channelA = notificationManager.getNotificationChannel(NotificationChannelID.AUDIO_SERVICE.name)

            if(channelA == null) {
                val channelB = NotificationChannel(NotificationChannelID.AUDIO_SERVICE.name,
                        "AudioService",
                        NotificationManager.IMPORTANCE_DEFAULT)
                channelB.setSound(null, null)

                notificationManager.createNotificationChannel(channelB)
            }
        }

        val notification = if(RoosterUtils.hasLollipop()) {
            NotificationCompat.Builder(this, NotificationChannelID.AUDIO_SERVICE.name)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(state)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setDeleteIntent(pendingIntent)
                    .build()
        } else {
            NotificationCompat.Builder(this)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(state)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setDeleteIntent(pendingIntent)
                    .build()
        }

        // Above not working
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL

        startForeground(NotificationID.AUDIO_SERVICE.ordinal, notification)
    }
}
