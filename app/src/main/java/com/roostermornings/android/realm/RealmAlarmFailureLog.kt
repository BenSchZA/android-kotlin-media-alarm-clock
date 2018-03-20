package com.roostermornings.android.realm

import android.content.Context
import android.content.SharedPreferences
import android.support.design.widget.Snackbar
import com.crashlytics.android.Crashlytics
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.GsonBuilder
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.domain.local.MetricsErrorEvent
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.keys.PrefsKey
import com.roostermornings.android.snackbar.SnackbarManager
import com.roostermornings.android.util.Constants
import io.realm.Realm
import io.realm.RealmObject
import java.lang.reflect.Modifier
import java.util.*
import javax.inject.Inject


/**
 * Created by bscholtz on 2017/11/04.
 */

class RealmAlarmFailureLog(val context: Context) {

    @Inject lateinit var realm: Realm
    @Inject lateinit var realmScheduledSnackbar: RealmScheduledSnackbar
    @Inject lateinit var sharedPreferences: SharedPreferences

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    fun closeRealm() {
        realm.close()
    }

    fun getAllAlarmFailureLogs(): List<AlarmFailureLog> {
        return realm.where(AlarmFailureLog::class.java)
                .findAll().toList()
    }

    fun processAlarmFailures(clear: Boolean) {
        val currentTime = Calendar.getInstance().timeInMillis

        realm.executeTransaction {
            // Query Realm, looking for alarm failures, as defined in AlarmFailureLog comments
            var tempResults = realm
                    .where(AlarmFailureLog::class.java)
                    .lessThan("scheduledTime", currentTime)
                    .not()
                    .beginGroup()
                        .equalTo("fired", true)
                        .equalTo("activated", true)
                        .equalTo("running", false)
                    .endGroup().findAll()

            // Find all results where alarm delayed by more than 5 minutes
            val delayedAlarms = tempResults.filter {
                val unmanagedRealmObject = realm.copyFromRealm(it)
                Math.abs(unmanagedRealmObject.scheduledTime - unmanagedRealmObject.firedTime) >= Constants.TIME_MILLIS_1_MINUTE*5
            }

            tempResults = tempResults.where()
                    .beginGroup()
                        .equalTo("fired", false)
                        .or()
                        .equalTo("activated", false)
                        .or()
                        .equalTo("running", false)
                        .or()
                        .equalTo("seen", false)
                        .or()
                        .equalTo("heard", false)
                        .or()
                        .equalTo("failsafe", true)
                        .or()
                        .equalTo("stream", true)
                        .or()
                        .equalTo("def", true)
                            .beginGroup()
                                .equalTo("content", true)
                                .or()
                                .equalTo("channel", true)
                            .endGroup()
                        .or()
                        .equalTo("channel", true)
                            .beginGroup()
                                .equalTo("content", false)
                            .endGroup()
                    .endGroup().findAll()

            // Find all objects matching query
            val failures: List<AlarmFailureLog> = tempResults + delayedAlarms.filterNot { tempResults.contains(it) }

            // For each alarm failure, mark it as such for easy fetching later
            failures.forEach { alarmFailure ->
                alarmFailure.failure = true
            }
        }
        // Send relevant logs to Crashlytics
        sendAlarmFailureLogs(clear)
        // Clear all logs older than current time in millis
        if(clear) clearOldAlarmFailureLogs()
    }

    private fun sendAlarmFailureLogs(clear: Boolean): List<AlarmFailureLog> {
        // Ignore serialization of RealmObject subclass to avoid StackOverFlow error
        val gson = GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .excludeFieldsWithModifiers(Modifier.ABSTRACT)
                .addSerializationExclusionStrategy(object : ExclusionStrategy {
            override fun shouldSkipField(f: FieldAttributes): Boolean {
                return f.declaringClass == RealmObject::class.java
            }

            override fun shouldSkipClass(clazz: Class<*>): Boolean {
                return false
            }
        }).create()

        // If alarm failures exist, direct user to FAQs on alarm creation
        if(getAlarmFailures().isNotEmpty())
            sharedPreferences.edit()
                    .putBoolean(PrefsKey.USER_VIEWED_FAQS.name, false)
                    .apply()

        // Log each alarm failure to Crashlytics before deleting from Realm
        getAlarmFailures().onEach { alarmFailure ->
            val unmanagedAlarmFailure = realm.copyFromRealm(alarmFailure)
            Crashlytics.log("Alarm Failure: \n" + gson.toJson(unmanagedAlarmFailure))

            generateScheduledSnackbarForAlarmFailure(unmanagedAlarmFailure)

            realm.executeTransaction {
                if(clear) alarmFailure.deleteFromRealm()
            }

            //Log a firebase analytics event indicating whether an attempt was/should have been made to play audio content
            FA.LogMany(FA.Event.default_alarm_play::class.java,
                    arrayOf(FA.Event.default_alarm_play.Param.attempt_to_play, FA.Event.default_alarm_play.Param.fatal_failure),
                    arrayOf<Any>(unmanagedAlarmFailure.content, (!unmanagedAlarmFailure.seen || !unmanagedAlarmFailure.heard) && !unmanagedAlarmFailure.interaction))

        }.takeIf {
            // If the list is not empty, continue
            it.isNotEmpty()
        }?.also {
            // Send Crashlytics log and return list from method sendAlarmFailureLogs()
            Crashlytics.logException(Throwable("Alarm Failure Report"))
            return@sendAlarmFailureLogs it
        }
        return emptyList()
    }

    private fun generateScheduledSnackbarForAlarmFailure(alarmFailureLog: AlarmFailureLog) {
        val snackbarQueueElement = SnackbarManager.Companion.SnackbarQueueElement()
        snackbarQueueElement.actionText = "Learn more"
        snackbarQueueElement.priority = 10
        snackbarQueueElement.length = Snackbar.LENGTH_INDEFINITE

        val activityName = MyAlarmsFragmentActivity::class.java.name

        alarmFailureLog.takeIf { !it.heard || !it.seen }?.let {
            when {
                !it.heard -> {
                    UserMetrics.logErrorEvent(MetricsErrorEvent(timestamp = it.scheduledTime)
                            .setEventAndType(
                                    MetricsErrorEvent.Companion.Event.ALARM_FAILURE(
                                            MetricsErrorEvent.Companion.Event.ALARM_FAILURE.Type.NOT_HEARD)))
                }
                !it.seen -> {
                    UserMetrics.logErrorEvent(MetricsErrorEvent(timestamp = it.scheduledTime)
                            .setEventAndType(
                                    MetricsErrorEvent.Companion.Event.ALARM_FAILURE(
                                            MetricsErrorEvent.Companion.Event.ALARM_FAILURE.Type.NOT_SEEN)))
                }
            }
        }
        alarmFailureLog.takeIf {
            (!it.fired || !it.activated) && !it.running}?.let {

            //Show dialog explainer again by clearing shared pref
            val editor = sharedPreferences.edit()
            editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, false)
            editor.apply()

            snackbarQueueElement.text = "Your alarm didn't fire. Find out why."
            snackbarQueueElement.dialog = true
            snackbarQueueElement.dialogType = SnackbarManager.DialogType.NOT_FIRED
            snackbarQueueElement.dialogTitle = "What's this missed alarm about?"
            snackbarQueueElement.dialogText = "Some phones (e.g. Huawei and Xiaomi) don't allow other apps to set alarms in order to save power. In most of these cases the phone will have a settings page with a whitelist where you can allow Rooster to set alarms or start from the background.\n\nIf your phone issue is one we are aware of, we'll display an explainer again when you set your next alarm, otherwise please get in touch with us and we'll try help you out."

            realmScheduledSnackbar.updateOrCreateScheduledSnackbarEntry(snackbarQueueElement, activityName, -1L)

            UserMetrics.logErrorEvent(MetricsErrorEvent(timestamp = it.scheduledTime)
                    .setEventAndType(
                            MetricsErrorEvent.Companion.Event.ALARM_FAILURE(
                                    MetricsErrorEvent.Companion.Event.ALARM_FAILURE.Type.NO_FIRE)))

            return@generateScheduledSnackbarForAlarmFailure
        }
        alarmFailureLog.takeIf {
            it.firedTime > -1 &&
            Math.abs(it.scheduledTime - it.firedTime) >= Constants.TIME_MILLIS_1_MINUTE*5}?.let {

            // 88 characters max for text
            snackbarQueueElement.text = "Your alarm was delayed. Find out why."
            snackbarQueueElement.dialog = true
            snackbarQueueElement.dialogType = SnackbarManager.DialogType.DELAYED
            snackbarQueueElement.dialogTitle = "What's this delay about?"
            snackbarQueueElement.dialogText = "We noticed your alarm was delayed by more than 5 minutes. This is most likely because your phone OS is delaying Rooster's alarms to save power. You might be able to add Rooster to a protected list in your phone settings that gives Rooster priority, and solves the delayed alarm issue."

            realmScheduledSnackbar.updateOrCreateScheduledSnackbarEntry(snackbarQueueElement, activityName, -1L)

            UserMetrics.logErrorEvent(MetricsErrorEvent(timestamp = it.scheduledTime)
                    .setEventAndType(
                            MetricsErrorEvent.Companion.Event.ALARM_FAILURE(
                                    MetricsErrorEvent.Companion.Event.ALARM_FAILURE.Type.DELAYED)))

            return@generateScheduledSnackbarForAlarmFailure
        }
        alarmFailureLog.takeIf {
            it.def && it.channel && !it.content}?.let {

            snackbarQueueElement.text = "Received a default alarm tone? Find out why."
            snackbarQueueElement.dialog = true
            snackbarQueueElement.dialogType = SnackbarManager.DialogType.DEFAULT
            snackbarQueueElement.dialogTitle = "What's this default alarm tone about?"
            snackbarQueueElement.dialogText = "We noticed that the alarm audio content was not downloaded before your alarm went off, this can happen if:\n\n" + "1) You didn't have an active internet connection when you created your alarm (note that your alarm will still go off without an internet connection, but you need to make sure the content is downloaded when you set your alarm for the best experience).\n\n" + "2) Your phone is blocking Rooster from downloading content. Some phones have a page within your settings that allows you to add Rooster to a whitelist of allowed apps.\n\n" + "On the home page, the little cloud will indicate the current download state: either downloading, finished downloading, or no active internet connection."

            realmScheduledSnackbar.updateOrCreateScheduledSnackbarEntry(snackbarQueueElement, activityName, -1L)

            UserMetrics.logErrorEvent(MetricsErrorEvent(timestamp = it.scheduledTime)
                    .setEventAndType(
                            MetricsErrorEvent.Companion.Event.ALARM_FAILURE(
                                    MetricsErrorEvent.Companion.Event.ALARM_FAILURE.Type.DEFAULT)))

            return@generateScheduledSnackbarForAlarmFailure
        }
        alarmFailureLog.takeIf {
            it.stream  && it.channel && !it.content}?.let {

            snackbarQueueElement.text = "Your alarm was streamed. Find out why."
            snackbarQueueElement.dialog = true
            snackbarQueueElement.dialogType = SnackbarManager.DialogType.STREAM
            snackbarQueueElement.dialogTitle = "What's this streaming about?"
            snackbarQueueElement.dialogText = "If you did not have an active internet connection when your alarm was set then Rooster can't download your chosen channel content, and Rooster will attempt to stream the audio content when your alarm goes off.\n\nThis could also be as a result of your phone blocking Rooster from downloading audio content in the background, in which case there might be a page within your settings that allows you to add Rooster to a whitelist of allowed apps."

            realmScheduledSnackbar.updateOrCreateScheduledSnackbarEntry(snackbarQueueElement, activityName, -1L)

            UserMetrics.logErrorEvent(MetricsErrorEvent(timestamp = it.scheduledTime)
                    .setEventAndType(
                            MetricsErrorEvent.Companion.Event.ALARM_FAILURE(
                                    MetricsErrorEvent.Companion.Event.ALARM_FAILURE.Type.STREAM)))

            return@generateScheduledSnackbarForAlarmFailure
        }
    }

    fun clearOldAlarmFailureLogs() {
        // Clear all logs older than current time - i.e. pending intent that has fired (hopefully)
        val currentTime = Calendar.getInstance().timeInMillis

        realm.executeTransaction {
            realm.where(AlarmFailureLog::class.java)
                    .lessThan("scheduledTime", currentTime)
                    .findAll().deleteAllFromRealm()
        }
    }

    fun getAlarmFailures(): List<AlarmFailureLog> {
        // Return all logs with failure flag set. As generated in processAlarmFailures.
        return realm.where(AlarmFailureLog::class.java)
                .equalTo("failure", true)
                .findAll().toList()
    }

    private fun getAlarmFailureLogByUIDAndPIID(alarmUid: String, pendingIntentID: Int): AlarmFailureLog? {
        return realm.where(AlarmFailureLog::class.java)
                .equalTo("pendingIntentID", pendingIntentID)
                .equalTo("alarmUid", alarmUid)
                .findFirst()
    }

    fun getAlarmFailureLogMillisSlot(scheduledMillis: Long?, operation: (AlarmFailureLog) -> Unit): AlarmFailureLog? {
        // scheduledTime is a unique key - return the slot where scheduledTime is equal to the
        // millis set

        val alarmFailureLog = realm.where(AlarmFailureLog::class.java)
                .equalTo("scheduledTime", scheduledMillis)
                .findFirst()

        // For a non-null log, perform the operation from the lambda within a Realm transaction
        // e.g. alarmFailureLog.fired = true
        alarmFailureLog?.let { notNullLog ->
            realm.executeTransaction {
                operation(notNullLog)
            }
        }

        return alarmFailureLog
    }

    fun updateOrCreateAlarmFailureLogEntry(alarmFailureLog: AlarmFailureLog) {
        // Insert a new entry, or update if it exists.
        // Working with an unmanaged Realm object, you're only guaranteed of no duplication if
        // primary key present.
        realm.executeTransaction {
            realm.insertOrUpdate(alarmFailureLog)
        }
    }

    fun tryDeleteAlarmFailureLogByUIDAndPIID(alarmUid: String, pendingIntentID: Int) {
        realm.executeTransaction{
            getAlarmFailureLogByUIDAndPIID(alarmUid, pendingIntentID)?.deleteFromRealm()
        }
    }
}