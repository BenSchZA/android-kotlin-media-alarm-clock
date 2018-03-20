/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.media

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.support.v4.app.NotificationCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.keys.NotificationChannelID
import com.roostermornings.android.service.MediaService
import com.roostermornings.android.util.RoosterUtils
import com.squareup.picasso.Picasso


/**
 * Helper class for building Media style Notifications from a
 * [android.support.v4.media.session.MediaSessionCompat].
 */
object MediaNotificationHelper {

    var mNotificationManager: NotificationManager? = null

    val NOTIFICATION_ID = 101

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    fun createNotification(context: Context,
                           mediaSession: MediaSessionCompat) {

        //mNotificationManager = NotificationManagerCompat.from(context)
        mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val controller = mediaSession.controller
        val mMetadata = controller.metadata
        val mPlaybackState = controller.playbackState

        if (mMetadata == null || mPlaybackState == null) {
            return
        }

        val isPlaying = mPlaybackState.state == PlaybackStateCompat.STATE_PLAYING
        val action = if (isPlaying)
            NotificationCompat.Action(R.drawable.ic_pause_white_24dp,
                    "Pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                            PlaybackStateCompat.ACTION_PAUSE))
        else
            NotificationCompat.Action(R.drawable.ic_play_arrow_white_24dp,
                    "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                            PlaybackStateCompat.ACTION_PLAY))


        val description = mMetadata.description

        val target = object : com.squareup.picasso.Target {
            override fun onPrepareLoad(placeHolderDrawable: Drawable?) {

            }

            override fun onBitmapFailed(errorDrawable: Drawable?) {
                compileNotification(context, action, mediaSession, controller, mMetadata, null, mPlaybackState)
            }

            override fun onBitmapLoaded(bitmap: Bitmap?, from: Picasso.LoadedFrom?) {
                compileNotification(context, action, mediaSession, controller, mMetadata, bitmap, mPlaybackState)
            }
        }

        val imageUri = description.iconUri

        if (imageUri == null) {
            compileNotification(context, action, mediaSession, controller, mMetadata, null, mPlaybackState)
        } else {
            Picasso.Listener { _, _, _ ->
                compileNotification(context, action, mediaSession, controller, mMetadata, null, mPlaybackState)
            }

            Picasso.with(context).load(imageUri)
                    .into(target)
        }
    }

    private fun compileNotification(context: Context, action: NotificationCompat.Action, mediaSession: MediaSessionCompat, controller: MediaControllerCompat, mMetadata: MediaMetadataCompat, art: Bitmap?, mPlaybackState: PlaybackStateCompat) {

        val description = mMetadata.description

        // https://stackoverflow.com/questions/45395669/notifications-fail-to-display-in-android-oreo-api-26
        @TargetApi(26)
        if(RoosterUtils.hasO()) {
            val channelA = mNotificationManager?.getNotificationChannel(NotificationChannelID.MEDIA_SERVICE.name)

            if(channelA == null) {
                val channelB = NotificationChannel(NotificationChannelID.MEDIA_SERVICE.name,
                        "MediaService",
                        NotificationManager.IMPORTANCE_DEFAULT)
                channelB.setSound(null, null)

                mNotificationManager?.createNotificationChannel(channelB)
            }
        }

        val notificationBuilder = if(RoosterUtils.hasLollipop()) {
            NotificationCompat.Builder(context, NotificationChannelID.MEDIA_SERVICE.name)
        } else {
            NotificationCompat.Builder(context)
        }

        notificationBuilder
                .setStyle(android.support.v4.media.app.NotificationCompat.MediaStyle()
                        // Show actions 0,2,4 in compact view
                        .setShowActionsInCompactView(0,2,4)
                        .setMediaSession(mediaSession.sessionToken))
                .setSmallIcon(R.drawable.logo_icon)
                .setShowWhen(false)
                .setContentIntent(controller.sessionActivity)
                .setContentTitle(description.title)
                .setContentText(description.description)
                .setLargeIcon(art)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(mPlaybackState.state == PlaybackStateCompat.STATE_PLAYING)
                .setOnlyAlertOnce(true)

                if(!RoosterUtils.hasLollipop()) {
                    notificationBuilder
                            .setStyle(android.support.v4.media.app.NotificationCompat.MediaStyle()
                                    // Show actions 0,2,4 in compact view
                                    .setShowActionsInCompactView(0,2,4)
                                    .setMediaSession(mediaSession.sessionToken)
                                    .setShowCancelButton(true)
                                    .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                                            PlaybackStateCompat.ACTION_STOP)))
                            // Stop the service when the notification is swiped away
                            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                                    PlaybackStateCompat.ACTION_STOP))
                }

        notificationBuilder.addAction(NotificationCompat.Action(
                R.drawable.exo_controls_previous,
                "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)))
        notificationBuilder.addAction(NotificationCompat.Action(
                R.drawable.ic_replay_10_white_24dp,
                "Rewind",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                        PlaybackStateCompat.ACTION_REWIND)))

        notificationBuilder.addAction(action)

        notificationBuilder.addAction(NotificationCompat.Action(
                R.drawable.ic_forward_10_white_24dp,
                "Fast Foward",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                        PlaybackStateCompat.ACTION_FAST_FORWARD)))
        notificationBuilder.addAction(NotificationCompat.Action(
                R.drawable.exo_controls_next,
                "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT)))

        (context as MediaService).startForeground(NOTIFICATION_ID, notificationBuilder.build())
        //mNotificationManager?.notify(NOTIFICATION_ID, notificationBuilder.build())
    }
}
