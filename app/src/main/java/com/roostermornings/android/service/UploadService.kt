/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.service

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.*
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.widget.Toast
import com.google.firebase.storage.FirebaseStorage
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.apis.NodeIHTTPClient
import com.roostermornings.android.domain.database.User
import com.roostermornings.android.domain.local.FCMPayloadSocialRooster
import com.roostermornings.android.domain.node.NodeAPIResult
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.keys.Extra
import com.roostermornings.android.keys.NotificationChannelID
import com.roostermornings.android.keys.NotificationID
import com.roostermornings.android.util.Constants
import com.roostermornings.android.util.RoosterUtils
import com.roostermornings.android.util.Toaster
import retrofit.Callback
import retrofit.Response
import retrofit.Retrofit
import java.io.File
import java.util.*
import javax.inject.Inject

//Service to offload the task of uploading a new Social Rooster to Firebase cloud storage, receive a link -> Firebase entries & Node post
class UploadService : Service() {

    // Binder given to clients
    private val mBinder = LocalBinder()

    // Create a new background thread for processing messages or runnables sequentially
    private val mHandlerThread = HandlerThread("AudioUploadHandler")
    private val mThis = this
    private var mHandler: Handler? = null

    internal var mAudioSavePathInDevice: String? = ""
    private var mFirebaseIdToken = ""

    @Inject lateinit var sharedPreferences: SharedPreferences

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        // Return this instance of AudioService so clients can call public methods
        val service: UploadService
            get() = this@UploadService
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BaseApplication.roosterApplicationComponent.inject(this)

        foregroundNotification("Audio upload in progress")

        // Starts the background thread
        mHandlerThread.start()

        // Create a handler attached to the HandlerThread's Looper
        mHandler = object : Handler(mHandlerThread.looper) {
            override fun handleMessage(msg: Message) {
                // Process received messages here!
                val uploadData = msg.data
                mAudioSavePathInDevice = uploadData.getString(Extra.LOCAL_FILE_STRING.name)

                try {
                    val friendsList = uploadData.getSerializable(Extra.FRIENDS_LIST.name) as ArrayList<User>?

                    if (friendsList?.isNotEmpty() == true) {
                        uploadAudioFile(mAudioSavePathInDevice, friendsList)
                    }
                } catch (e: ClassCastException) {
                    e.printStackTrace()
                    uploadFailed()
                    endService()
                }
            }
        }

        return Service.START_STICKY
    }

    private fun uploadFailed() {
        Toaster.makeToast(baseContext, "Rooster upload failed.", Toast.LENGTH_SHORT).checkTastyToast()
    }

    /** methods for clients  */

    private fun uploadAudioFile(mAudioSavePathInDevice: String?, friendsList: ArrayList<User>) {

        val mStorageRef = FirebaseStorage.getInstance().reference

        val localFile = File(mAudioSavePathInDevice)
        val file = Uri.fromFile(localFile)
        val audioFileRef = mStorageRef.child("social_rooster_uploads/" + file.lastPathSegment)

        if (friendsList.isNotEmpty()) {
            val sentRoosters = if (sharedPreferences.contains(FA.UserProp.social_rooster_sender.shared_pref_sent_roosters)) {
                friendsList.size + sharedPreferences.getInt(FA.UserProp.social_rooster_sender.shared_pref_sent_roosters, 0)
            } else friendsList.size

            sharedPreferences.edit()
                    .putInt(FA.UserProp.social_rooster_sender.shared_pref_sent_roosters, sentRoosters)
                    .apply()

            FA.SetUserProp(FA.UserProp.social_rooster_sender::class.java, sentRoosters.toString())
        }

        audioFileRef.putFile(file)
                .addOnSuccessListener {
                    // Get a URL to the uploaded content
                    val firebaseStorageURL = file.lastPathSegment

                    friendsList
                            .filter { it.selected }
                            .forEach { sendRoosterToUser(it, firebaseStorageURL) }

                    FA.Log(FA.Event.social_rooster_sent::class.java,
                            FA.Event.social_rooster_sent.Param.social_rooster_receivers,
                            friendsList.size)

                    endService()
                }
                .addOnFailureListener {
                    // Handle unsuccessful upload
                    uploadFailed()
                    endService()
                }
    }

    private fun endService() {
        //Delete all temporary recording files
        val files = filesDir.listFiles { _, name -> name.contains(Constants.FILENAME_PREFIX_ROOSTER_TEMP_RECORDING) }

        files?.forEach {
            it.delete()
        }

        stopForeground(true)

        if (RoosterUtils.hasJellyBeanMR2())
            mHandlerThread.quitSafely()
        else
            mHandlerThread.quit()

        this.stopSelf()
    }

    private fun sendRoosterToUser(user: User, firebaseStorageURL: String) {
        FirebaseNetwork.sendRoosterToUser(user, firebaseStorageURL)
        socialRoosterNotifyUserFCMMessage(user.uid)

        Toaster.makeToast(applicationContext, "Social rooster sent to " + user.user_name + "!", Toast.LENGTH_LONG).checkTastyToast()
    }

    private fun socialRoosterNotifyUserFCMMessage(recipientUserId: String) {
        val call = apiService().notifySocialUploadRecipient(
                FCMPayloadSocialRooster(mThis.mFirebaseIdToken, recipientUserId))

        call.enqueue(object : Callback<NodeAPIResult> {
            override fun onResponse(response: Response<NodeAPIResult>,
                                    retrofit: Retrofit) {

                val statusCode = response.code()
                val apiResponse = response.body()

                if (statusCode == 200) {
                    Log.d("apiResponse", apiResponse.toString())
                }
            }

            override fun onFailure(t: Throwable) {}
        })
    }

    private fun apiService(): NodeIHTTPClient {
        val baseApplication = application as BaseApplication
        return baseApplication.mNodeAPIService
    }

    fun processAudioFile(firebaseIdToken: String?, localFileString: String?, friendsList: ArrayList<User>) {
        firebaseIdToken?.let {
            mThis.mFirebaseIdToken = it

            // Secure a new message to send
            val message = mHandler?.obtainMessage()

            // Create a bundle
            val uploadData = Bundle()
            uploadData.putString(Extra.LOCAL_FILE_STRING.name, localFileString)
            uploadData.putSerializable(Extra.FRIENDS_LIST.name, friendsList)

            // Attach bundle to the message
            message?.data = uploadData

            // Send message through the handler
            mHandler?.sendMessage(message)

        } ?: uploadFailed()
    }

    private fun foregroundNotification(state: String) {
        val notificationIntent = Intent(this, AudioService::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // https://stackoverflow.com/questions/45395669/notifications-fail-to-display-in-android-oreo-api-26
        @TargetApi(26)
        if(RoosterUtils.hasO()) {
            var channel = notificationManager.getNotificationChannel(NotificationChannelID.UPLOAD_SERVICE.name)

            if(channel == null) {
                channel = NotificationChannel(NotificationChannelID.UPLOAD_SERVICE.name,
                        "UploadService",
                        NotificationManager.IMPORTANCE_HIGH)
                notificationManager.createNotificationChannel(channel)
            }
        }

        val notification = if(RoosterUtils.hasLollipop()) {
            NotificationCompat.Builder(this, NotificationChannelID.UPLOAD_SERVICE.name)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Rooster: " + state)
                    .setContentIntent(pendingIntent)
                    .build()
        } else {
            NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(state)
                    .setContentIntent(pendingIntent)
                    .build()
        }

        startForeground(NotificationID.UPLOAD_SERVICE.ordinal, notification)
    }

    companion object {
        private val TAG = UploadService::class.java.simpleName
    }
}
