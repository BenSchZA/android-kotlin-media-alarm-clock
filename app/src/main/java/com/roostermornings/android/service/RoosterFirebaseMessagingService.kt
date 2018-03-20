/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.service

import android.accounts.Account
import android.annotation.TargetApi
import android.app.*
import android.content.ContentResolver
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioAttributes.USAGE_NOTIFICATION
import android.media.AudioManager
import android.media.RingtoneManager
import android.support.v4.app.NotificationCompat
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.keys.Flag
import com.roostermornings.android.keys.NotificationChannelID
import com.roostermornings.android.keys.NotificationID
import com.roostermornings.android.sync.DownloadSyncAdapter
import com.roostermornings.android.util.Constants.AUTHORITY
import com.roostermornings.android.util.RoosterUtils
import org.json.JSONObject
import javax.inject.Inject

class RoosterFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var mAccount: Account

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    // [START receive_message]
    override fun onMessageReceived(remoteMessage: RemoteMessage?) {
        BaseApplication.roosterApplicationComponent.inject(this)

        // [START_EXCLUDE]
        // There are two types of messages data messages and notification messages. Data messages are handled
        // here in onMessageReceived whether the app is in the foreground or background. Data messages are the type
        // traditionally used with GCM. Notification messages are only received here in onMessageReceived when the app
        // is in the foreground. When the app is in the background an automatically generated notification is displayed.
        // When the user taps on the notification they are returned to the app. Messages containing both notification
        // and data payloads are treated as notification messages. The Firebase console always sends notification
        // messages. For more see: https://firebase.google.com/docs/cloud-messaging/concept-options
        // [END_EXCLUDE]

        //Retrieve new social rooster data
        /*
         * Ask the framework to run your sync adapter.
         * To maintain backward compatibility, assume that
         * changeUri is null.
         */
        ContentResolver.requestSync(mAccount, AUTHORITY, DownloadSyncAdapter.forceBundle)

        // TODO(developer): Handle FCM messages here.
        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        Log.d(TAG, "From: " + remoteMessage?.from)

        // Check if message contains a data payload.
        if (remoteMessage?.data?.isNotEmpty() == true) {
            Log.d(TAG, "Message data payload: " + remoteMessage.data)
            val jsonObject = JSONObject(remoteMessage.data)
            val gson = Gson()
            //FCMRoosterNotification notification = gson.fromJson(jsonObject.toString(), FCMRoosterNotification.class);
            //sendNotification(notification.getMessage());
        }

        // Check if message contains a notification payload.
        remoteMessage?.notification?.let {
            Log.d(TAG, "Message Notification Body: " + it.body)
            sendNotification(it.body)
        }
    }
    // [END receive_message]

    /**
     * Create and show a simple notification containing the received FCM message.
     *
     * @param messageBody FCM message body received.
     */
    private fun sendNotification(messageBody: String?) {
        val intent = Intent(this, MyAlarmsFragmentActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("fcm_message", messageBody)

        val pendingIntent = PendingIntent.getActivity(this, 0 /* Request code */, intent,
                PendingIntent.FLAG_ONE_SHOT)

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationManager = getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager

        // https://stackoverflow.com/questions/45395669/notifications-fail-to-display-in-android-oreo-api-26
        @TargetApi(26)
        if(RoosterUtils.hasO()) {
            val channelA = notificationManager.getNotificationChannel(NotificationChannelID.FIREBASE_MESSAGING.name)
            channelA.setShowBadge(true)

            if(channelA == null) {

                val audioAttributes = AudioAttributes.Builder()
                            .setUsage(USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                            .build()

                val channelB = NotificationChannel(NotificationChannelID.FIREBASE_MESSAGING.name,
                        "FirebaseMessagingService",
                        NotificationManager.IMPORTANCE_HIGH)
                channelB.setSound(defaultSoundUri, audioAttributes)
                channelB.setShowBadge(true)

                notificationManager.createNotificationChannel(channelB)
            }
        }

        val notification = if(RoosterUtils.hasO()) {
            NotificationCompat.Builder(this, NotificationChannelID.FIREBASE_MESSAGING.name)
                    .setNumber(BaseApplication.getNotificationFlag(Flag.ROOSTER_COUNT.name))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentTitle("Rooster")
                    .setSmallIcon(R.drawable.logo)
                    .setContentText(messageBody)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
        } else {
            NotificationCompat.Builder(this)
                    .setNumber(BaseApplication.getNotificationFlag(Flag.ROOSTER_COUNT.name))
                    .setContentTitle("Rooster")
                    .setSmallIcon(R.drawable.logo)
                    .setContentText(messageBody)
                    .setAutoCancel(true)
                    .setSound(defaultSoundUri)
                    .setContentIntent(pendingIntent)
                    .build()
        }

        notificationManager.notify(NotificationID.FIREBASE_MESSAGING.ordinal, notification)
    }

    companion object {
        private val TAG = "MyFirebaseMsgService"
    }
}
