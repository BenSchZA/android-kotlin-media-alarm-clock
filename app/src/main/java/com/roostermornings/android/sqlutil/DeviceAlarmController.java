/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.sqlutil;

import android.accounts.Account;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.roostermornings.android.BaseApplication;
import com.roostermornings.android.activity.DeviceAlarmFullScreenActivity;
import com.roostermornings.android.activity.MyAlarmsFragmentActivity;
import com.roostermornings.android.domain.database.Alarm;
import com.roostermornings.android.firebase.FirebaseNetwork;
import com.roostermornings.android.keys.Action;
import com.roostermornings.android.keys.Extra;
import com.roostermornings.android.keys.NotificationID;
import com.roostermornings.android.realm.AlarmFailureLog;
import com.roostermornings.android.realm.RealmAlarmFailureLog;
import com.roostermornings.android.realm.RealmScheduledSnackbar;
import com.roostermornings.android.receiver.DeviceAlarmReceiver;
import com.roostermornings.android.service.AudioService;
import com.roostermornings.android.sync.DownloadSyncAdapter;
import com.roostermornings.android.util.Constants;
import com.roostermornings.android.snackbar.SnackbarManager;
import com.roostermornings.android.util.Toaster;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.inject.Inject;

import static com.roostermornings.android.util.Constants.AUTHORITY;
import static com.roostermornings.android.util.RoosterUtils.hasKitKat;
import static com.roostermornings.android.util.RoosterUtils.hasLollipop;

/**
 * Created by bscholtz on 2/16/17.
 */


// Synchronises alarm manager's pending intents and SQL database
public final class DeviceAlarmController {

    // The app's AlarmManager, which provides access to the system alarm services.
    private AlarmManager alarmMgr;

    private DeviceAlarmTableManager deviceAlarmTableManager;
    private Context context;

    @Inject
    Account mAccount;
    @Inject
    RealmAlarmFailureLog realmManagerAlarmFailureLog;
    @Inject
    RealmScheduledSnackbar realmManagerScheduledSnackbar;

    public DeviceAlarmController(Context context) {
        BaseApplication.Companion.getRoosterApplicationComponent().inject(this);
        this.context = context;
        deviceAlarmTableManager = new DeviceAlarmTableManager(context);
    }

    // Take a set of alarms and recreate intents, overwriting if already existing, and clearing changed flag if set
    public void refreshAlarms(List<DeviceAlarm> deviceAlarmList) {
        for (DeviceAlarm deviceAlarm : deviceAlarmList) {
            setAlarm(deviceAlarm, false);
        }

        // Clear all changed flags
        deviceAlarmTableManager.clearChanged();
    }

    // Create pending intent for individual alarm (done once for each alarm in set)
    private void setAlarm(DeviceAlarm deviceAlarm, Boolean cancel) {
        if (deviceAlarm.getEnabled()) {
            Log.i(this.getClass().getSimpleName(),"Set alarm started");

            alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Calendar alarmCalendar = Calendar.getInstance();
            Calendar systemCalendar = Calendar.getInstance();

            alarmCalendar.set(alarmCalendar.get(Calendar.YEAR), alarmCalendar.get(Calendar.MONTH), alarmCalendar.get(Calendar.DATE), deviceAlarm.getHour(), deviceAlarm.getMinute());
            alarmCalendar.set(Calendar.WEEK_OF_MONTH, systemCalendar.get(Calendar.WEEK_OF_MONTH));
            alarmCalendar.set(Calendar.DAY_OF_WEEK, deviceAlarm.getDay());
            alarmCalendar.set(Calendar.SECOND, 0);
            alarmCalendar.set(Calendar.MILLISECOND, 0);

            long alarmTime = alarmCalendar.getTimeInMillis();
            long systemTime = systemCalendar.getTimeInMillis();

            // Set alarm time to one week in future If specified day and time of alarm is already in the past
            int WEEK_OF_MONTH = alarmCalendar.get(Calendar.WEEK_OF_MONTH);
            while (alarmTime < systemTime) {
                WEEK_OF_MONTH += 1;
                alarmCalendar.set(Calendar.WEEK_OF_MONTH, WEEK_OF_MONTH);
                alarmTime = alarmCalendar.getTimeInMillis();
            }
            // Store the alarm table with the alarm's new date and time
            deviceAlarmTableManager.updateAlarmMillis(deviceAlarm.getPiId(), alarmTime);
            deviceAlarm.setMillis(alarmTime);

            Intent alarmIntent = new Intent(context, DeviceAlarmReceiver.class);
            alarmIntent.setAction(Action.ALARM_RECEIVER.name());
            alarmIntent.putExtra(Extra.REQUEST_CODE.name(), deviceAlarm.getPiId());
            alarmIntent.putExtra(Extra.UID.name(), deviceAlarm.getSetId());
            alarmIntent.putExtra(Extra.RECURRING.name(), deviceAlarm.getRecurring());
            alarmIntent.putExtra(Extra.MILLIS_SLOT.name(), deviceAlarm.getMillis());

            Log.i(this.getClass().getSimpleName(),
                    "Intent extra " + Extra.REQUEST_CODE.name() + ": " + deviceAlarm.getPiId());
            Log.i(this.getClass().getSimpleName(),
                    "Intent extra " + Extra.UID.name() + ": " + deviceAlarm.getSetId());

            PendingIntent temp = PendingIntent.getBroadcast(context,
                    deviceAlarm.getPiId(), alarmIntent,
                    PendingIntent.FLAG_NO_CREATE);
            boolean pendingIntentExists = temp != null;
            if(temp != null) temp.cancel();

            if(pendingIntentExists)
                Log.i(this.getClass().getSimpleName(), "Pending intent exists");
            else
                // Clear any pending intents with legacy intent action string
                clearLegacyPendingIntent(deviceAlarm);

            PendingIntent alarmPendingIntent = PendingIntent.getBroadcast(context,
                    deviceAlarm.getPiId(), alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            // Only clear log if pending intent exists, which indicates intent has not fired
            if(cancel && pendingIntentExists) {
                // Clear AlarmFailureLog ActiveAndroid db entry
                realmManagerAlarmFailureLog.tryDeleteAlarmFailureLogByUIDAndPIID(deviceAlarm.getSetId(), deviceAlarm.getPiId());
                Log.i(this.getClass().getSimpleName(), "Realm entry deleted");
            }

            // Use setAlarm with cancel Boolean set to true - this recreates intent in order to clear it
            if(cancel) {
                // Cancel alarm intent
                alarmMgr.cancel(alarmPendingIntent);
                alarmPendingIntent.cancel();

                Log.i(this.getClass().getSimpleName(), "Pending intent cancelled");
                // The below method is reeeaaallly delayed...?
                //alarmPendingIntent.cancel();
                return;
            }

            if(pendingIntentExists) {
                // Update AlarmFailureLog ActiveAndroid db entry
                AlarmFailureLog alarmFailureLog = new AlarmFailureLog();
                alarmFailureLog.setPendingIntentID(deviceAlarm.getPiId());
                alarmFailureLog.setAlarmUid(deviceAlarm.getSetId());
                alarmFailureLog.setScheduledTime(deviceAlarm.getMillis());
                realmManagerAlarmFailureLog.updateOrCreateAlarmFailureLogEntry(alarmFailureLog);
            } else {
                // Create new AlarmFailureLog ActiveAndroid db entry
                AlarmFailureLog alarmFailureLog = new AlarmFailureLog();
                alarmFailureLog.setPendingIntentID(deviceAlarm.getPiId());
                alarmFailureLog.setAlarmUid(deviceAlarm.getSetId());
                alarmFailureLog.setScheduledTime(deviceAlarm.getMillis());
                realmManagerAlarmFailureLog.updateOrCreateAlarmFailureLogEntry(alarmFailureLog);
            }

            // If newer version of Android, create info pending intent
            if (hasLollipop()) {
                Intent alarmInfoIntent = new Intent(context, DeviceAlarmFullScreenActivity.class);
                PendingIntent alarmInfoPendingIntent = PendingIntent.getActivity(context, 0, alarmInfoIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                // Required for setting alarm clock
                AlarmManager.AlarmClockInfo alarmInfo = new AlarmManager.AlarmClockInfo(alarmTime, alarmInfoPendingIntent);
                alarmMgr.setAlarmClock(alarmInfo, alarmPendingIntent);

            } else if(hasKitKat()) {
                // If older version of android, don't require info pending intent
                alarmMgr.setExact(AlarmManager.RTC_WAKEUP, alarmTime, alarmPendingIntent);
                // Show alarm in the status bar
                Intent alarmChanged = new Intent(Action.ALARM_CHANGED.name());
                alarmChanged.putExtra(Extra.ALARM_SET.name(), true);
                context.sendBroadcast(alarmChanged);
            } else {
                alarmMgr.set(AlarmManager.RTC_WAKEUP, alarmTime, alarmPendingIntent);
                // Show alarm in the status bar
                Intent alarmChanged = new Intent(Action.ALARM_CHANGED.name());
                alarmChanged.putExtra(Extra.ALARM_SET.name(), true);
                context.sendBroadcast(alarmChanged);
            }
        }
    }

    private void clearLegacyPendingIntent(DeviceAlarm deviceAlarm) {
        // Clear any pending intents with legacy intent action string

        Log.i(this.getClass().getSimpleName(), "Attempting to clear legacy pending intent");
        String ACTION_ALARMRECEIVER_LEGACY = "receiver.ALARM_RECEIVER";

        Intent alarmIntent = new Intent(context, DeviceAlarmReceiver.class);
        alarmIntent.setAction(ACTION_ALARMRECEIVER_LEGACY);

        PendingIntent temp = PendingIntent.getBroadcast(context,
                deviceAlarm.getPiId(), alarmIntent,
                PendingIntent.FLAG_NO_CREATE);
        boolean pendingIntentExists = temp != null;
        if(temp != null) temp.cancel();

        if(pendingIntentExists) {
            Log.i(this.getClass().getSimpleName(), "Pending intent exists");

            PendingIntent alarmPendingIntent = PendingIntent.getBroadcast(context,
                    deviceAlarm.getPiId(), alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            // Cancel alarm intent
            alarmMgr.cancel(alarmPendingIntent);
            alarmPendingIntent.cancel();

            Log.i(this.getClass().getSimpleName(), "Pending intent cancelled");
        } else
            Log.i(this.getClass().getSimpleName(), "Pending intent doesn't exist");
    }

    public void snoozeAlarm(String setId, Boolean cancel){
        // Add new pending intent for 10 minutes time
        alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Calendar alarmCalendar = Calendar.getInstance();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        alarmCalendar.setTimeInMillis(alarmCalendar.getTimeInMillis() + Long.valueOf(sharedPreferences.getString(Constants.USER_SETTINGS_SNOOZE_TIME, "10"))*Constants.TIME_MILLIS_1_MINUTE);

        Intent alarmIntent = new Intent(context, DeviceAlarmReceiver.class);
        alarmIntent.setAction(Action.ALARM_RECEIVER.name());
        alarmIntent.putExtra(Extra.REQUEST_CODE.name(), 0);
        alarmIntent.putExtra(Extra.UID.name(), setId);
        alarmIntent.putExtra(Extra.SNOOZE_ACTIVATION.name(), true);

        PendingIntent alarmPendingIntent = PendingIntent.getBroadcast(context,
                0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        if(cancel) {
            // Cancel alarm intent
            alarmPendingIntent.cancel();
            // Clear foreground notification
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(NotificationID.AUDIO_SERVICE.ordinal());
            // Stop audio service
            context.stopService(new Intent(context, AudioService.class));
            return;
        }

        long alarmTime = alarmCalendar.getTimeInMillis();

        // if newer version of Android, create info pending intent
        if (hasLollipop()) {
            Intent alarmInfoIntent = new Intent(context, DeviceAlarmFullScreenActivity.class);
            PendingIntent alarmInfoPendingIntent = PendingIntent.getActivity(context, 0, alarmInfoIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            // required for setting alarm clock
            AlarmManager.AlarmClockInfo alarmInfo = new AlarmManager.AlarmClockInfo(alarmTime, alarmInfoPendingIntent);
            alarmMgr.setAlarmClock(alarmInfo, alarmPendingIntent);

        } else if(hasKitKat()) {
            // if older version of android, don't require info pending intent
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, alarmTime, alarmPendingIntent);
            // Show alarm in the status bar
            Intent alarmChanged = new Intent(Action.ALARM_CHANGED.name());
            alarmChanged.putExtra(Extra.ALARM_SET.name(), true);
            context.sendBroadcast(alarmChanged);
        } else {
            alarmMgr.set(AlarmManager.RTC_WAKEUP, alarmTime, alarmPendingIntent);
            // Show alarm in the status bar
            Intent alarmChanged = new Intent(Action.ALARM_CHANGED.name());
            alarmChanged.putExtra(Extra.ALARM_SET.name(), true);
            context.sendBroadcast(alarmChanged);
        }
    }

    public Boolean registerAlarmSet(Boolean enabled, String setId, int alarmHour, int alarmMinute, List<Integer> alarmDays, boolean repeatWeekly, String channel, boolean social) {
        List<DeviceAlarm> deviceAlarmList;
        DeviceAlarm deviceAlarmSet = new DeviceAlarm()
                .initAlarmSet(enabled, alarmHour, alarmMinute, alarmDays, repeatWeekly, channel, social);
        deviceAlarmList = deviceAlarmSet.getAlarmList();

        for (DeviceAlarm deviceAlarm :
                deviceAlarmList) {
            if(!deviceAlarmTableManager.insertAlarm(deviceAlarm, setId)) return false;
        }

        // Update alarm millis and setAlarm
        refreshAlarms(deviceAlarmTableManager.selectChanged());
        // Notify user of time until next alarm, once alarm millis has been updated in db
        notifyUserAlarmTime(deviceAlarmTableManager.getAlarmSet(setId));

        return true;
    }

    private void notifyUserAlarmTime(List<DeviceAlarm> deviceAlarmList) {
        Calendar alarmCalendar = Calendar.getInstance();
        Calendar systemCalendar = Calendar.getInstance();

        Long nextAlarmMillis;
        Long timeUntilNextAlarm;
        nextAlarmMillis = Long.MAX_VALUE;
        String nextAlarmTimeString;

        // Iterate over all alarms in SQL db for specific set, find next alarm time
        for (DeviceAlarm deviceAlarm :
                deviceAlarmList) {
            if(deviceAlarm.getMillis() < nextAlarmMillis) nextAlarmMillis = deviceAlarm.getMillis();
        }

        // Time until next alarm is the time of alarm, minus the current time in millis - alarm always set for future date
        alarmCalendar.setTimeInMillis(nextAlarmMillis);
        timeUntilNextAlarm = alarmCalendar.getTimeInMillis() - systemCalendar.getTimeInMillis();

        // Convert millis value to days,hours,mins until next alarm
        int minutes = (int) ((timeUntilNextAlarm / (1000*60)) % 60);
        int hours   = (int) ((timeUntilNextAlarm / (1000*60*60)) % 24);
        int days = (int) (timeUntilNextAlarm / (1000*60*60*24));

        // Notify user of time until next alarm
        if(days == 0 && hours != 0) nextAlarmTimeString = String.format("%s hours, and %s minutes", hours, minutes);
        else if(days == 0 && hours == 0) nextAlarmTimeString = String.format("%s minutes", minutes);
        else nextAlarmTimeString = String.format("%s days, %s hours, and %s minutes", days, hours, minutes);

        // Ensure alarm time accurate before notice
        if(minutes >= 0 && hours >= 0 && days >= 0) {
//            Toaster.makeToast(context, "Alarm set for " + nextAlarmTimeString + " from now.", Toast.LENGTH_LONG).checkTastyToast();

            SnackbarManager.Companion.SnackbarQueueElement snackbarQueueElement = new SnackbarManager.Companion.SnackbarQueueElement();
            snackbarQueueElement.setText("Alarm set for " + nextAlarmTimeString + " from now.");
            snackbarQueueElement.setAction(new View.OnClickListener() {
                @Override
                public void onClick(View view) {}});

            String activityName = MyAlarmsFragmentActivity.class.getName();

            realmManagerScheduledSnackbar.updateOrCreateScheduledSnackbarEntry(snackbarQueueElement, activityName, -1L);
        }
    }

    public void deleteAllLocalAlarms() {
        List<DeviceAlarm> deviceAlarmList = deviceAlarmTableManager.getAlarmSets();
        if(deviceAlarmList == null) return;
        for (DeviceAlarm deviceAlarm:
                deviceAlarmList) {
            deleteAlarmSetLocal(deviceAlarm.getSetId());
        }
    }

    private void deleteAlarmSetLocal(String setId) {
        List<DeviceAlarm> deviceAlarmList = deviceAlarmTableManager.getAlarmSet(setId);
        deviceAlarmTableManager.deleteAlarmSet(setId);
        deleteAlarmSetIntents(deviceAlarmList);
    }

    // Remove entire set of alarms, first recreate intent EXACTLY as before, then call alarmMgr.cancel(intent)
    public void deleteAlarmSetGlobal(String setId) {
        deleteAlarmSetLocal(setId);
        FirebaseNetwork.INSTANCE.removeFirebaseAlarm(setId);
    }

    private void deleteAlarmSetIntents(List<DeviceAlarm> deviceAlarmList) {
        for (DeviceAlarm deviceAlarm :
                deviceAlarmList) {
            cancelAlarm(deviceAlarm);
        }
    }

    private void cancelAlarm(DeviceAlarm deviceAlarm) {
        // Use setAlarm with cancel Boolean set to true - this recreates intent in order to clear it
        setAlarm(deviceAlarm, true);
    }

    // Case: local has an alarm that firebase doesn't Result: delete local alarm
    public void syncAlarmSetGlobal(ArrayList<Alarm> firebaseAlarmSets) {
        ArrayList<String> firebaseAlarmSetIDs = new ArrayList<>();
        // Create array of Firebase alarm IDs to compare against local alarms
        for (Alarm alarmSet:
                firebaseAlarmSets) {
            firebaseAlarmSetIDs.add(alarmSet.getUid());
        }
        // If no local alarms, no problem, nothing to do
        if(deviceAlarmTableManager.getAlarmSets() == null) return;
        // Check if every local alarm exists in Firebase, else delete global
        for (DeviceAlarm deviceAlarmSet:
        deviceAlarmTableManager.getAlarmSets()) {
            if(!firebaseAlarmSetIDs.contains(deviceAlarmSet.getSetId())) deleteAlarmSetGlobal(deviceAlarmSet.getSetId());
        }
    }

    public void setSetEnabled(String setId, boolean enabled, boolean notifyUser) {
        if(enabled) {
            // Set all intents for enabled alarms
            deviceAlarmTableManager.setSetEnabled(setId, enabled);
            deviceAlarmTableManager.setSetChanged(setId, true);
            refreshAlarms(deviceAlarmTableManager.selectChanged());

            FirebaseNetwork.INSTANCE.updateFirebaseAlarmEnabled(setId, enabled);
            // Trigger audio download
            // Download any social or channel audio files
            ContentResolver.requestSync(mAccount, AUTHORITY, DownloadSyncAdapter.getForceBundle());
            if(notifyUser) {
                // Notify user of time until next alarm, once alarm millis has been updated in db
                notifyUserAlarmTime(deviceAlarmTableManager.getAlarmSet(setId));
            }
        } else{
            List<DeviceAlarm> deviceAlarmList = deviceAlarmTableManager.getAlarmSet(setId);
            for (DeviceAlarm deviceAlarm :
                    deviceAlarmList) {
                cancelAlarm(deviceAlarm);
            }
            deviceAlarmTableManager.setSetEnabled(setId, enabled);
            // removeSetChannelAudio(deviceAlarmList);
            FirebaseNetwork.INSTANCE.updateFirebaseAlarmEnabled(setId, enabled);
            // Trigger audio download
            // Download any social or channel audio files
            ContentResolver.requestSync(mAccount, AUTHORITY, DownloadSyncAdapter.getForceBundle());
        }
    }

    // Used to recreate alarm intents after reboot. All ENABLED alarms recreated.
    public void rebootAlarms() {
        Log.i(this.getClass().getSimpleName(), "Reboot alarms started");
        List<DeviceAlarm> enabledAlarms = deviceAlarmTableManager.selectEnabled();
        Log.i(this.getClass().getSimpleName(), "Delete alarm intents started");
        deleteAlarmSetIntents(enabledAlarms);
        Log.i(this.getClass().getSimpleName(), "Refresh alarms started");
        refreshAlarms(enabledAlarms);
    }
}
