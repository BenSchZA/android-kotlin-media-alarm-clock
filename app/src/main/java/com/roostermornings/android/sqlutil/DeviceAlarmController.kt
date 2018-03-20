/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.sqlutil

import android.accounts.Account
import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.activity.DeviceAlarmFullScreenActivity
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.domain.database.Alarm
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Extra
import com.roostermornings.android.keys.NotificationID
import com.roostermornings.android.realm.AlarmFailureLog
import com.roostermornings.android.realm.RealmAlarmFailureLog
import com.roostermornings.android.realm.RealmScheduledSnackbar
import com.roostermornings.android.receiver.DeviceAlarmReceiver
import com.roostermornings.android.service.AudioService
import com.roostermornings.android.snackbar.SnackbarManager
import com.roostermornings.android.sync.DownloadSyncAdapter
import com.roostermornings.android.util.Constants
import com.roostermornings.android.util.Constants.AUTHORITY
import com.roostermornings.android.util.RoosterUtils.hasKitKat
import com.roostermornings.android.util.RoosterUtils.hasLollipop
import java.util.*
import javax.inject.Inject

/**
 * Created by bscholtz on 2/16/17.
 */

// Synchronises alarm manager's pending intents and SQL database
class DeviceAlarmController(private val context: Context) {

    // The app's AlarmManager, which provides access to the system alarm services.
    private val alarmMgr: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private val deviceAlarmTableManager: DeviceAlarmTableManager

    @Inject
    lateinit var mAccount: Account
    @Inject
    lateinit var realmManagerAlarmFailureLog: RealmAlarmFailureLog
    @Inject
    lateinit var realmManagerScheduledSnackbar: RealmScheduledSnackbar

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
        deviceAlarmTableManager = DeviceAlarmTableManager(context)
    }

    // Take a set of alarms and recreate intents, overwriting if already existing, and clearing changed flag if set
    fun refreshAlarms(deviceAlarmList: List<DeviceAlarm>) {
        deviceAlarmList.forEach {
            setAlarm(it, false)
        }

        // Clear all changed flags
        deviceAlarmTableManager.clearChanged()
    }

    // Create pending intent for individual alarm (done once for each alarm in set)
    private fun setAlarm(deviceAlarm: DeviceAlarm, cancel: Boolean) {
        if (deviceAlarm.enabled) {
            Log.i(this.javaClass.simpleName, "Set alarm started")

            val alarmCalendar = Calendar.getInstance()
            val systemCalendar = Calendar.getInstance()

            alarmCalendar.set(alarmCalendar.get(Calendar.YEAR), alarmCalendar.get(Calendar.MONTH), alarmCalendar.get(Calendar.DATE), deviceAlarm.hour, deviceAlarm.minute)
            alarmCalendar.set(Calendar.WEEK_OF_MONTH, systemCalendar.get(Calendar.WEEK_OF_MONTH))
            alarmCalendar.set(Calendar.DAY_OF_WEEK, deviceAlarm.day)
            alarmCalendar.set(Calendar.SECOND, 0)
            alarmCalendar.set(Calendar.MILLISECOND, 0)

            var alarmTime = alarmCalendar.timeInMillis
            val systemTime = systemCalendar.timeInMillis

            // Set alarm time to one week in future If specified day and time of alarm is already in the past
            var WEEK_OF_MONTH = alarmCalendar.get(Calendar.WEEK_OF_MONTH)
            while (alarmTime < systemTime) {
                WEEK_OF_MONTH += 1
                alarmCalendar.set(Calendar.WEEK_OF_MONTH, WEEK_OF_MONTH)
                alarmTime = alarmCalendar.timeInMillis
            }
            // Store the alarm table with the alarm's new date and time
            deviceAlarmTableManager.updateAlarmMillis(deviceAlarm.piId, alarmTime)
            deviceAlarm.millis = alarmTime

            val alarmIntent = Intent(context, DeviceAlarmReceiver::class.java)
            alarmIntent.action = Action.ALARM_RECEIVER.name
            alarmIntent.putExtra(Extra.REQUEST_CODE.name, deviceAlarm.piId)
            alarmIntent.putExtra(Extra.ALARM_SET_ID.name, deviceAlarm.setId)
            alarmIntent.putExtra(Extra.RECURRING.name, deviceAlarm.recurring)
            alarmIntent.putExtra(Extra.MILLIS_SLOT.name, deviceAlarm.millis)

            Log.i(this.javaClass.simpleName,
                    "Intent extra ${Extra.REQUEST_CODE.name}: ${deviceAlarm.piId}")
            Log.i(this.javaClass.simpleName,
                    "Intent extra ${Extra.ALARM_SET_ID.name}: ${deviceAlarm.setId}")

            // Check if pending intent exists
            val pendingIntentExists = doesPendingIntentExist(deviceAlarm.piId, alarmIntent)

            if (pendingIntentExists)
                Log.i(this.javaClass.simpleName, "Pending intent exists")
            else
            // Clear any pending intents with legacy intent action string
                clearLegacyPendingIntent(deviceAlarm)

            // Initialize pending intent to create alarm
            val alarmPendingIntent = PendingIntent.getBroadcast(context,
                    deviceAlarm.piId, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT)

            // Only clear log if pending intent exists, which indicates intent has not fired
            if (cancel && pendingIntentExists) {
                // Clear AlarmFailureLog ActiveAndroid db entry
                realmManagerAlarmFailureLog.tryDeleteAlarmFailureLogByUIDAndPIID(deviceAlarm.setId, deviceAlarm.piId)
                Log.i(this.javaClass.simpleName, "Realm entry deleted")
            }

            // Use setAlarm with cancel Boolean set to true - this recreates intent in order to clear it
            if (cancel) {
                // Cancel alarm intent
                alarmMgr.cancel(alarmPendingIntent)
                alarmPendingIntent.cancel()

                Log.i(this.javaClass.simpleName, "Pending intent cancelled")
                // The below method is reeeaaallly delayed...?
                //alarmPendingIntent.cancel();
                return
            }

            generateAlarmFailureLogEntry(deviceAlarm)

            when {
                hasLollipop() -> createAlarmLollipop(alarmTime, alarmPendingIntent)
                hasKitKat() -> createAlarmKitKat(alarmTime, alarmPendingIntent)
                else -> createAlarm(alarmTime, alarmPendingIntent)
            }
        }
    }

    private fun doesPendingIntentExist(piID: Int, intent: Intent): Boolean {
        val tempPendingIntent = PendingIntent.getBroadcast(context,
                piID, intent,
                PendingIntent.FLAG_NO_CREATE)
        tempPendingIntent?.cancel()
        return tempPendingIntent != null
    }

    private fun generateAlarmFailureLogEntry(deviceAlarm: DeviceAlarm) {
        // Update or create AlarmFailureLog Realm db entry
        val alarmFailureLog = AlarmFailureLog()
        alarmFailureLog.pendingIntentID = deviceAlarm.piId
        alarmFailureLog.alarmUid = deviceAlarm.setId
        alarmFailureLog.scheduledTime = deviceAlarm.millis
        realmManagerAlarmFailureLog.updateOrCreateAlarmFailureLogEntry(alarmFailureLog)
    }

    @TargetApi(21)
    private fun createAlarmLollipop(alarmTime: Long, pendingIntent: PendingIntent) {
        // If newer version of Android, create info pending intent
        val alarmInfoIntent = Intent(context, DeviceAlarmFullScreenActivity::class.java)
        val alarmInfoPendingIntent = PendingIntent.getActivity(context, 0, alarmInfoIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        // Required for setting alarm clock
        val alarmInfo = AlarmManager.AlarmClockInfo(alarmTime, alarmInfoPendingIntent)
        alarmMgr.setAlarmClock(alarmInfo, pendingIntent)
    }

    @TargetApi(19)
    private fun createAlarmKitKat(alarmTime: Long, pendingIntent: PendingIntent) {
        // If older version of android, don't require info pending intent
        alarmMgr.setExact(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
        // Show alarm in the status bar
        val alarmChanged = Intent(Action.ALARM_CHANGED.name)
        alarmChanged.putExtra(Extra.ALARM_SET.name, true)
        context.sendBroadcast(alarmChanged)
    }

    private fun createAlarm(alarmTime: Long, pendingIntent: PendingIntent) {
        alarmMgr.set(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
        // Show alarm in the status bar
        val alarmChanged = Intent(Action.ALARM_CHANGED.name)
        alarmChanged.putExtra(Extra.ALARM_SET.name, true)
        context.sendBroadcast(alarmChanged)
    }

    private fun clearLegacyPendingIntent(deviceAlarm: DeviceAlarm) {
        // Clear any pending intents with legacy intent action string

        Log.i(this.javaClass.simpleName, "Attempting to clear legacy pending intent")
        val ACTION_ALARMRECEIVER_LEGACY = "receiver.ALARM_RECEIVER"

        val alarmIntent = Intent(context, DeviceAlarmReceiver::class.java)
        alarmIntent.action = ACTION_ALARMRECEIVER_LEGACY

        // Check if pending intent exists
        val pendingIntentExists = doesPendingIntentExist(deviceAlarm.piId, alarmIntent)

        if (pendingIntentExists) {
            Log.i(this.javaClass.simpleName, "Pending intent exists")

            val alarmPendingIntent = PendingIntent.getBroadcast(context,
                    deviceAlarm.piId, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT)

            // Cancel alarm intent
            alarmMgr.cancel(alarmPendingIntent)
            alarmPendingIntent.cancel()

            Log.i(this.javaClass.simpleName, "Pending intent cancelled")
        } else
            Log.i(this.javaClass.simpleName, "Pending intent doesn't exist")
    }

    fun snoozeAlarm(setId: String, cancel: Boolean) {
        // Add new pending intent for 10 minutes time
        val alarmCalendar = Calendar.getInstance()

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        alarmCalendar.timeInMillis = alarmCalendar.timeInMillis + sharedPreferences.getString(Constants.USER_SETTINGS_SNOOZE_TIME, "10").toLong() * Constants.TIME_MILLIS_1_MINUTE

        val alarmIntent = Intent(context, DeviceAlarmReceiver::class.java)
        alarmIntent.action = Action.ALARM_RECEIVER.name
        alarmIntent.putExtra(Extra.REQUEST_CODE.name, 0)
        alarmIntent.putExtra(Extra.ALARM_SET_ID.name, setId)
        alarmIntent.putExtra(Extra.SNOOZE_ACTIVATION.name, true)

        val alarmPendingIntent = PendingIntent.getBroadcast(context,
                0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        if (cancel) {
            // Cancel alarm intent
            alarmPendingIntent.cancel()
            // Clear foreground notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NotificationID.AUDIO_SERVICE.ordinal)
            // Stop audio service
            context.stopService(Intent(context, AudioService::class.java))
            return
        }

        val alarmTime = alarmCalendar.timeInMillis

        when {
            hasLollipop() -> createAlarmLollipop(alarmTime, alarmPendingIntent)
            hasKitKat() -> createAlarmKitKat(alarmTime, alarmPendingIntent)
            else -> createAlarm(alarmTime, alarmPendingIntent)
        }
    }

    fun registerAlarmSet(enabled: Boolean, setId: String, alarmHour: Int, alarmMinute: Int, alarmDays: List<Int>, repeatWeekly: Boolean, channel: String, social: Boolean): Boolean {
        val deviceAlarmList: List<DeviceAlarm>
        val deviceAlarmSet = DeviceAlarm()
                .initAlarmSet(enabled, alarmHour, alarmMinute, alarmDays, repeatWeekly, channel, social)
        deviceAlarmList = deviceAlarmSet.alarmList

        deviceAlarmList?.forEach {
            if (!deviceAlarmTableManager.insertAlarm(it, setId)) return false
        }

        // Update alarm millis and setAlarm
        refreshAlarms(deviceAlarmTableManager.selectChanged())
        // Notify user of time until next alarm, once alarm millis has been updated in db
        notifyUserAlarmTime(deviceAlarmTableManager.getAlarmSet(setId))

        return true
    }

    private fun notifyUserAlarmTime(deviceAlarmList: List<DeviceAlarm>) {
        val alarmCalendar = Calendar.getInstance()
        val systemCalendar = Calendar.getInstance()

        var nextAlarmMillis: Long = java.lang.Long.MAX_VALUE
        val timeUntilNextAlarm: Long?
        val nextAlarmTimeString: String

        // Iterate over all alarms in SQL db for specific set, find next alarm time
        deviceAlarmList.forEach {
            if (it.millis < nextAlarmMillis) nextAlarmMillis = it.millis
        }

        // Time until next alarm is the time of alarm, minus the current time in millis - alarm always set for future date
        alarmCalendar.timeInMillis = nextAlarmMillis
        timeUntilNextAlarm = alarmCalendar.timeInMillis - systemCalendar.timeInMillis

        // Convert millis value to days,hours,mins until next alarm
        val minutes = (timeUntilNextAlarm / (1000 * 60) % 60).toInt()
        val hours = (timeUntilNextAlarm / (1000 * 60 * 60) % 24).toInt()
        val days = (timeUntilNextAlarm / (1000 * 60 * 60 * 24)).toInt()

        // Notify user of time until next alarm
        nextAlarmTimeString = if (days == 0 && hours != 0)
            "$hours hours, and $minutes minutes"
        else if (days == 0 && hours == 0)
            "$minutes minutes"
        else
            "$days days, $hours hours, and $minutes minutes"

        // Ensure alarm time accurate before notice
        if (minutes >= 0 && hours >= 0 && days >= 0) {
            //            Toaster.makeToast(context, "Alarm set for " + nextAlarmTimeString + " from now.", Toast.LENGTH_LONG).checkTastyToast();

            val snackbarQueueElement = SnackbarManager.Companion.SnackbarQueueElement()
            snackbarQueueElement.text = "Alarm set for $nextAlarmTimeString from now."
            snackbarQueueElement.action = View.OnClickListener { }

            val activityName = MyAlarmsFragmentActivity::class.java.name

            realmManagerScheduledSnackbar.updateOrCreateScheduledSnackbarEntry(snackbarQueueElement, activityName, -1L)
        }
    }

    fun deleteAllLocalAlarms() {
        deviceAlarmTableManager.alarmSets?.forEach {
            deleteAlarmSetLocal(it.setId)
        }
    }

    private fun deleteAlarmSetLocal(setId: String) {
        val deviceAlarmList = deviceAlarmTableManager.getAlarmSet(setId)
        deviceAlarmTableManager.deleteAlarmSet(setId)
        deleteAlarmSetIntents(deviceAlarmList)
    }

    // Remove entire set of alarms, first recreate intent EXACTLY as before, then call alarmMgr.cancel(intent)
    fun deleteAlarmSetGlobal(setId: String) {
        deleteAlarmSetLocal(setId)
        FirebaseNetwork.removeFirebaseAlarm(setId)
    }

    private fun deleteAlarmSetIntents(deviceAlarmList: List<DeviceAlarm>) {
        deviceAlarmList.forEach { cancelAlarm(it) }
    }

    private fun cancelAlarm(deviceAlarm: DeviceAlarm) {
        // Use setAlarm with cancel Boolean set to true - this recreates intent in order to clear it
        setAlarm(deviceAlarm, true)
    }

    // Case: local has an alarm that firebase doesn't Result: delete local alarm
    fun syncAlarmSetGlobal(firebaseAlarmSets: ArrayList<Alarm>) {
        // Create array of Firebase alarm IDs to compare against local alarms
        val firebaseAlarmSetIDs = firebaseAlarmSets.map { it.getUid() }
        // If no local alarms, no problem, nothing to do
        // Check if every local alarm exists in Firebase, else delete global
        deviceAlarmTableManager.alarmSets
                .filterNot { firebaseAlarmSetIDs.contains(it.setId) }
                .forEach { deleteAlarmSetGlobal(it.setId) }
    }

    fun setSetEnabled(setId: String, enabled: Boolean, notifyUser: Boolean) {
        if (enabled) {
            // Set all intents for enabled alarms
            deviceAlarmTableManager.setSetEnabled(setId, enabled)
            deviceAlarmTableManager.setSetChanged(setId, true)
            refreshAlarms(deviceAlarmTableManager.selectChanged())

            FirebaseNetwork.updateFirebaseAlarmEnabled(setId, enabled)
            // Trigger audio download
            // Download any social or channel audio files
            ContentResolver.requestSync(mAccount, AUTHORITY, DownloadSyncAdapter.forceBundle)
            if (notifyUser) {
                // Notify user of time until next alarm, once alarm millis has been updated in db
                notifyUserAlarmTime(deviceAlarmTableManager.getAlarmSet(setId))
            }
        } else {
            val deviceAlarmList = deviceAlarmTableManager.getAlarmSet(setId)
            for (deviceAlarm in deviceAlarmList) {
                cancelAlarm(deviceAlarm)
            }
            deviceAlarmTableManager.setSetEnabled(setId, enabled)
            // removeSetChannelAudio(deviceAlarmList);
            FirebaseNetwork.updateFirebaseAlarmEnabled(setId, enabled)
            // Trigger audio download
            // Download any social or channel audio files
            ContentResolver.requestSync(mAccount, AUTHORITY, DownloadSyncAdapter.forceBundle)
        }
    }

    // Used to recreate alarm intents after reboot. All ENABLED alarms recreated.
    fun rebootAlarms() {
        Log.i(this.javaClass.simpleName, "Reboot alarms started")
        val enabledAlarms = deviceAlarmTableManager.selectEnabled()
        Log.i(this.javaClass.simpleName, "Delete alarm intents started")
        deleteAlarmSetIntents(enabledAlarms)
        Log.i(this.javaClass.simpleName, "Refresh alarms started")
        refreshAlarms(enabledAlarms)
    }
}
