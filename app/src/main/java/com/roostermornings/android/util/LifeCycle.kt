/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.SharedPreferences
import android.net.Uri
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.Snackbar
import android.view.View
import android.widget.Toast
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.FAQActivity
import com.roostermornings.android.activity.NewAlarmFragmentActivity
import com.roostermornings.android.domain.database.ChannelRooster
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.keys.PrefsKey
import com.roostermornings.android.snackbar.SnackbarManager
import com.roostermornings.android.sqlutil.AudioTableManager
import com.roostermornings.android.sync.DownloadSyncAdapter
import java.util.*
import javax.inject.Inject


/**
 * <h1>LifeCycle Class</h1>
 *
 * Handles the user lifecycle, i.e.
 * - onboarding
 * - first use guides
 * - first social and channel roosters, etc.
 *
 * It does this by keeping a record of what the user has seen using sharedPrefs, etc.
 *
 * @author bscholtz
 * @version 1
 * @since 15/08/17
 */

class LifeCycle
/**
 * Instantiate class and perform Dagger 2 dependency injection
 * @param context
 */
(private val context: Context) {

    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var audioTableManager: AudioTableManager
    @Inject lateinit var connectivityUtils: ConnectivityUtils

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    /**
     * Performs all tasks for the user's first entry, or inception into Rooster...
     * (sounds a bit like a cult...)
     * @return boolean isFirstEntry?
     */

    fun performInception(): Boolean {
        return if (sharedPreferences.getBoolean(firstEntry, true)) {
            createFillerChannel()
            sharedPreferences.edit()
                    .putBoolean(PrefsKey.USER_FINISHED_ONBOARDING.name, true)
                    .apply()

            setFirstEntry()
            true
        } else {
            //If no methods performed
            false
        }
    }

    fun directUserToFAQs(overrideAlreadyViewed: Boolean, activity: Activity, coordinatorLayout: CoordinatorLayout) {
        if(!overrideAlreadyViewed && sharedPreferences.getBoolean(PrefsKey.USER_VIEWED_FAQS.name, false)) return

        val snackbarManager = SnackbarManager(activity, coordinatorLayout)

        val snackbarQueueElement = SnackbarManager.Companion.SnackbarQueueElement()
        snackbarQueueElement.actionText = "Learn more"
        snackbarQueueElement.priority = 10
        snackbarQueueElement.length = Snackbar.LENGTH_INDEFINITE

        val activityName = NewAlarmFragmentActivity::class.java.name

        snackbarQueueElement.text = "Some phones have issues with 3rd party alarms."
        snackbarQueueElement.action = View.OnClickListener {
            if(connectivityUtils.isConnected()) {
                val intent = Intent(context, FAQActivity::class.java)
                intent.flags = FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                sharedPreferences.edit()
                        .putBoolean(PrefsKey.USER_VIEWED_FAQS.name, true)
                        .apply()
            }
            else
                Toaster.makeToast(context, "No internet connection.", Toast.LENGTH_LONG)
        }

        snackbarManager.generateSnackbar(snackbarQueueElement)
    }


    /**
     * This method is run on the [n]th new app launch, to request the user to rate the app.
     * https://stackoverflow.com/questions/10816757/rate-this-app-link-in-google-play-store-app-on-the-phone
     */

    fun requestAppRating() {
        val uri = Uri.parse("market://details?id=${context.packageName}")
        val goToMarket = Intent(Intent.ACTION_VIEW, uri)

        // To count with Play market backstack, After pressing back button,
        // to taken back to our application, we need to add following flags to intent.
        if (RoosterUtils.hasLollipop()) {
            goToMarket.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            goToMarket.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(goToMarket)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://play.google.com/store/apps/details?id=${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun shareApp() {
        var sendIntent = Intent()
        sendIntent.action = Intent.ACTION_SEND
        sendIntent.putExtra(Intent.EXTRA_TEXT, context.resources.getString(R.string.share_rooster_message))
        sendIntent.type = "text/plain"
        sendIntent = Intent.createChooser(sendIntent, "Share Rooster").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(sendIntent)
        FA.Log(FA.Event.invitation_to_join_rooster_sent::class.java, null, null)
    }

    fun sendFeedback(name: String) {
        val recipientEmail = "ben.scholtz@roostermornings.com"
        val emailIntent = Intent(Intent.ACTION_SENDTO)
        emailIntent.data = Uri.parse("mailto:$recipientEmail?subject=$name:%20Hello%20there" +
                "&body=Add%20message%20here")

        try {
            context.startActivity(Intent.createChooser(emailIntent,
                    "Send feedback").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: android.content.ActivityNotFoundException) {
            Toaster.makeToast(context, "No email clients installed.", Toast.LENGTH_LONG)
        }
    }

    /**
     * This method creates the first channel the user sees, and inserts it in
     * today's roosters, as well as sets it as a favourite.
     */

    private fun createFillerChannel() {
        val channelRooster = ChannelRooster(
                "Bird Calls",
                true,
                "",
                "https://firebasestorage.googleapis.com/v0/b/roosta-media.appspot.com/o/channel_roosters%2FBRD-002%20emerald_wooddove.mp3?alt=media&token=407fc34b-bb89-41d2-b307-31c1a17edf07",
                "-KkGc979MLXKA5VBfFtY",
                "Emerald Spotted Wood Dove",
                "https://firebasestorage.googleapis.com/v0/b/roosta-media.appspot.com/o/channel_roosters_photos%2Femerald%20spotted%20wood%20dove.jpg?alt=media&token=72479ea3-2094-44d7-b619-a5aa88463fd2",
                0,
                0,
                "",
                "")

        val downloadSyncAdapter = DownloadSyncAdapter(context, true)

        DownloadSyncAdapter.onChannelDownloadListener = object : DownloadSyncAdapter.OnChannelDownloadListener {
            override fun onChannelDownloadStarted(channelId: String) {}

            override fun onChannelDownloadComplete(valid: Boolean, channelId: String) {
                val audioItem = audioTableManager.extractChannelAudioItem(channelRooster.getChannel_uid())
                if (audioItem != null) {
                    audioTableManager.setListened(audioItem)
                    audioTableManager.setFavourite(audioItem.id, true)
                }
            }
        }

        downloadSyncAdapter.retrieveChannelContentAudio(channelRooster, context)
    }

    /**
     * Set the shared pref to indicate whether this is a user's first entry, this is persisted.
     */

    private fun setFirstEntry() {
        sharedPreferences.edit()
                .putBoolean(firstEntry, false)
                .apply()
    }

    companion object {
        private val firstEntry = "shared_pref_lifecycle_first_entry"

        fun performMethodOnceInDay(operation: () -> Unit) {
            val className = operation.javaClass.enclosingClass.name
            val methodName = operation.javaClass.enclosingMethod.name

            val uid = "$className:$methodName"

            val jsonPersistence = JSONPersistence()

            // Fetch date-lock date if exists for method
            val dateLockTimeInMillis = jsonPersistence.getDateLock(uid)
            val dateLockTime = Calendar.getInstance()

            // Get current date
            dateLockTime.timeInMillis = dateLockTimeInMillis
            val currentTime = Calendar.getInstance()

            // Determine if method already run today
            val alreadyPerformedToday = dateLockTime.get(Calendar.DATE) == currentTime.get(Calendar.DATE)

            // If method hasn't run today, run it and set date-lock
            if(!alreadyPerformedToday) {
                operation()
                jsonPersistence.setDateLock(uid, currentTime.timeInMillis)
            }
        }
    }
}
