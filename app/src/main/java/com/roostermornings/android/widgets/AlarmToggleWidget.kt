/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.activity.NewAlarmFragmentActivity
import com.roostermornings.android.domain.database.Alarm
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.keys.Extra
import com.roostermornings.android.sqlutil.DeviceAlarmTableManager
import com.roostermornings.android.util.Constants
import com.roostermornings.android.util.RoosterUtils
import java.util.*
import javax.inject.Inject
import javax.inject.Named

/**
 * Implementation of App Widget functionality.
 * App Widget Configuration implemented in [AlarmToggleWidgetConfigureActivity]
 */
class AlarmToggleWidget : AppWidgetProvider() {

    @Inject
    @field:Named("default") lateinit var defaultSharedPreferences: SharedPreferences

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    companion object {
        val TOAST_ACTION = "AlarmToggleWidget.TOAST_ACTION"
        val EXTRA_ITEM = "AlarmToggleWidget.EXTRA_ITEM"
        val EXTRA_ALARM_UID = "AlarmToggleWidget.EXTRA_ALARM_UID"

        fun sendUpdateBroadcast(ctx: Context) {
            //Update app widget
            val updateWidgetIntent = Intent(ctx, AlarmToggleWidget::class.java)
            updateWidgetIntent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val appWidgetManager = AppWidgetManager.getInstance(ctx.applicationContext)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(ctx.applicationContext, AlarmToggleWidget::class.java))
            updateWidgetIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            ctx.sendBroadcast(updateWidgetIntent)
        }
    }

    fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager,
                        appWidgetId: Int) {

        //Used for displaying a list of alarms
//            // Sets up the intent that points to the StackViewService that will
//            // provide the views for this collection.
//            val intent = Intent(context, AlarmToggleWidgetService::class.java)
//            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
//            // When intents are compared, the extras are ignored, so we need to embed the extras
//            // into the data so that the extras will not be ignored.
//            intent.data = Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
//            val rv = RemoteViews(context.packageName, R.layout.widget_alarm_toggle)
//            rv.setRemoteAdapter(R.id.widget_alarmsListView, intent)
//
//            // The empty activityContentView is displayed when the collection has no items. It should be a sibling
//            // of the collection activityContentView.
//            rv.setEmptyView(R.id.widget_alarmsListView, R.id.widget_alarmsListView)
//
//            // This section makes it possible for items to have individualized behavior.
//            // It does this by setting up a pending intent template. Individuals items of a collection
//            // cannot set up their own pending intents. Instead, the collection as a whole sets
//            // up a pending intent template, and the individual items set a fillInIntent
//            // to create unique behavior on an item-by-item basis.
//            val toastIntent = Intent(context, AlarmToggleWidget::class.java)
//            // Set the action for the intent.
//            // When the user touches a particular activityContentView, it will have the effect of
//            // broadcasting TOAST_ACTION.
//            toastIntent.action = TOAST_ACTION
//            toastIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
//            intent.data = Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
//            val toastPendingIntent = PendingIntent.getBroadcast(context, 0, toastIntent,
//                    PendingIntent.FLAG_UPDATE_CURRENT)
//            rv.setPendingIntentTemplate(R.id.widget_alarmsListView, toastPendingIntent)
//
////            /** PendingIntent to launch the MainActivity when the widget was clicked **/
////            val launchMain = Intent(context, MyAlarmsFragmentActivity::class.java)
////            val pendingMainIntent = PendingIntent.getActivity(context, 0, launchMain, 0)
////            views.setOnClickPendingIntent(R.id.widget_alarm_toggle, pendingMainIntent)
//            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_alarmsListView)
//
//            appWidgetManager.updateAppWidget(appWidgetId, rv)

        val deviceAlarmTableManager = DeviceAlarmTableManager(context)

        // Get the layout for the App Widget
        val views = RemoteViews(context.packageName, R.layout.widget_alarm_toggle)

        //Set 24-hour or 12-hour time format
        val timeFormat = defaultSharedPreferences.getBoolean(Constants.USER_SETTINGS_TIME_FORMAT, true)

        val calendar = Calendar.getInstance()
        val currentTimeHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentTimeMinute = calendar.get(Calendar.MINUTE)

        try {
            //If using 12 hour format
            if(!timeFormat) {
                val twelveHourTimeString: String
                if(currentTimeHour >= 12) {
                    twelveHourTimeString = RoosterUtils.setAlarmTimeFromHourAndMinute(currentTimeHour, currentTimeMinute, false) + context.resources.getString(R.string.alarm_12_hour_pm)
                    views.setTextViewText(R.id.widget_current_time_textview, twelveHourTimeString)
                } else {
                    twelveHourTimeString = RoosterUtils.setAlarmTimeFromHourAndMinute(currentTimeHour, currentTimeMinute, false) + context.resources.getString(R.string.alarm_12_hour_am)
                    views.setTextViewText(R.id.widget_current_time_textview, twelveHourTimeString)
                }
            } else {
                views.setTextViewText(R.id.widget_current_time_textview, RoosterUtils.setAlarmTimeFromHourAndMinute(currentTimeHour, currentTimeMinute, true))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Create an Intent to launch Home Activity
        val intentHomeActivity = Intent(context, MyAlarmsFragmentActivity::class.java)
        val pendingIntentHomeActivity = PendingIntent.getActivity(context, 0, intentHomeActivity, PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_current_time_textview, pendingIntentHomeActivity)

        // Create an Intent to launch New Alarm Activity
        val intentNewAlarmActivity = Intent(context, NewAlarmFragmentActivity::class.java)
        val pendingIntentNewAlarmActivity = PendingIntent.getActivity(context, 1, intentNewAlarmActivity, PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_add_alarm, pendingIntentNewAlarmActivity)

        views.setViewVisibility(R.id.widget_toggle_alarm, View.INVISIBLE)
        views.setViewVisibility(R.id.widget_pending_alarm_layout, View.INVISIBLE)

        deviceAlarmTableManager.nextPendingAlarm?.let { pendingAlarm ->
            val mAlarm = Alarm()
            mAlarm.fromDeviceAlarm(pendingAlarm, true)
            mAlarm.setAlarmDaysFromDeviceAlarm(deviceAlarmTableManager.getAlarmClassDays(pendingAlarm.setId))

            val channelNameListener = object: FirebaseNetwork.OnFlagChannelNameReceivedListener {
                override fun onChannelNameReceived(channelName: String?) {
                    if(channelName.isNullOrBlank()) views.setViewVisibility(R.id.widget_alarm_channel_textview, View.GONE)
                    else {
                        views.setViewVisibility(R.id.widget_alarm_channel_textview, View.VISIBLE)
                        views.setTextViewText(R.id.widget_alarm_channel_textview, channelName)
                    }
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }

            FirebaseNetwork.setOnFlagChannelNameReceivedListener(channelNameListener)
            FirebaseNetwork.getChannelNameFromUID(pendingAlarm.channel)

            views.setViewVisibility(R.id.widget_toggle_alarm, View.VISIBLE)
            views.setViewVisibility(R.id.widget_pending_alarm_layout, View.VISIBLE)

            try {
                //If using 12 hour format
                if(!timeFormat) {
                    val twelveHourTimeString: String
                    if(mAlarm.hour >= 12) {
                        twelveHourTimeString = RoosterUtils.setAlarmTimeFromHourAndMinute(mAlarm.hour, mAlarm.minute, false) + context.resources.getString(R.string.alarm_12_hour_pm)
                        views.setTextViewText(R.id.widget_alarm_time_textview, twelveHourTimeString)
                    } else {
                        twelveHourTimeString = RoosterUtils.setAlarmTimeFromHourAndMinute(mAlarm.hour, mAlarm.minute, false) + context.resources.getString(R.string.alarm_12_hour_am)
                        views.setTextViewText(R.id.widget_alarm_time_textview, twelveHourTimeString)
                    }
                } else {
                    views.setTextViewText(R.id.widget_alarm_time_textview, RoosterUtils.setAlarmTimeFromHourAndMinute(mAlarm.hour, mAlarm.minute, true))
                }

                views.setTextViewText(R.id.widget_alarm_days_textview, RoosterUtils.getAlarmDays(mAlarm))
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Create an Intent to launch Edit Alarm Activity
            val intentEditAlarmActivity = Intent(context, NewAlarmFragmentActivity::class.java)
            intentEditAlarmActivity.putExtra(Extra.ALARM_SET_ID.name, pendingAlarm.setId)
            val pendingIntentEditAlarmActivity = PendingIntent.getActivity(context, 2, intentEditAlarmActivity, PendingIntent.FLAG_UPDATE_CURRENT)

            views.setOnClickPendingIntent(R.id.widget_pending_alarm_layout, pendingIntentEditAlarmActivity)
            views.setOnClickPendingIntent(R.id.widget_alarm_time_textview, pendingIntentEditAlarmActivity)
            views.setOnClickPendingIntent(R.id.widget_alarm_channel_textview, pendingIntentEditAlarmActivity)
            views.setOnClickPendingIntent(R.id.widget_alarm_days_textview, pendingIntentEditAlarmActivity)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    // Called when the BroadcastReceiver receives an Intent broadcast.
    // Checks to see whether the intent's action is TOAST_ACTION. If it is, the app widget
    // displays a Toast message for the current item.
    override fun onReceive(context: Context, intent: Intent) {
        val mgr = AppWidgetManager.getInstance(context)
        if (intent.action == TOAST_ACTION) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID)
            val viewIndex = intent.getIntExtra(EXTRA_ITEM, 0)
            Toast.makeText(context, "Touched activityContentView " + viewIndex, Toast.LENGTH_SHORT).show()
        }

        if(!RoosterUtils.hasM()) context.startService(Intent(context, WidgetService::class.java))

        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // When the user deletes the widget, delete the preference associated with it.
        for (appWidgetId in appWidgetIds) {
            AlarmToggleWidgetConfigureActivity.deleteTitlePref(context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }
}

