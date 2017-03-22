/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.util;

public class Constants {

    public final static String APP_FONT = "fonts/Nunito/Nunito-Bold.ttf";

    //DeviceAlarmController intent actions
    public final static String ACTTION_ALARMRECEIVER = "receiver.ALARM_RECEIVER";
    // > display alarm icon
    public final static String ACTION_ALARMCHANGED = "android.intent.action.ALARM_CHANGED";
    //DeviceAlarmController intent extras
    public final static String EXTRA_REQUESTCODE = "requestCode";
    // > alarm set flag to remove alarm icon later
    public final static String EXTRA_ALARMSET = "alarmSet";

    //DeviceAlarmController config constants
    public final static int ALARM_SNOOZETIME = 10 * 60 * 1000;
    public final static int ALARM_DEFAULTTIME = 1 * 60 * 1000;

    //DeviceAlarm extras
    public static final String EXTRA_RECURRING = "extra_recurring";
    public static final String EXTRA_VIBRATE = "extra_vibrate";
    public static final String EXTRA_TONE = "extra_tone";
    public static final String EXTRA_UID = "extra_uid";

    //DeviceAlarmReceiver wakeful intent
    public static final String DEVICE_ALARM_RECEIVER_WAKEFUL_INTENT = "DeviceAlarmReceiver.WAKEFUL_INTENT";

    //Background task service intent actions
    public static final String ACTION_BACKGROUNDDOWNLOAD = "service.action.BACKGROUND_DOWNLOAD";
    public static final String ACTION_DAILYTASK = "service.action.DAILY_TASK";

    //AudioService intent actions
    public final static String ACTION_ALARMDISPLAY = "rooster.update.ALARMDISPLAY";
    public final static String ACTION_ALARMTIMESUP = "rooster.update.ALARMTIMESUP";

    //AudioService notification IDs
    public final static int AUDIOSERVICE_NOTIFICATION_ID = 1000;

    //FirebaseListenerService intent actions
    public final static String ACTION_REQUESTNOTIFICATION = "rooster.update.REQUEST_NOTIFICATION";
    public final static String ACTION_ROOSTERNOTIFICATION = "rooster.update.ROOSTER_NOTIFICATION";

    //BaseApplication flags
    public final static String FLAG_FRIENDREQUESTS = "friendRequests";
    public final static String FLAG_ROOSTERCOUNT = "roosterCount";

    //Intent extras
    public final static String EXTRA_ALARMID = "alarmId";
}
