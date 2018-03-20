/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity

import android.content.*
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.support.v4.graphics.drawable.RoundedBitmapDrawableFactory
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import butterknife.BindView
import butterknife.OnClick
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Extra
import com.roostermornings.android.service.AudioService
import com.roostermornings.android.sqlutil.DeviceAudioQueueItem
import com.roostermornings.android.util.StrUtils
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.activity_device_alarm_full_screen.*

class DeviceAlarmFullScreenActivity : BaseActivity() {

    internal var mAudioService: AudioService? = null
    private var mBound = false
    private var receiver: BroadcastReceiver? = null
    internal var audioItem: DeviceAudioQueueItem? = DeviceAudioQueueItem()

    private var alarmCount = 1
    private var alarmPosition = 1

    private var actionUrlClicked: Boolean = false

    @BindView(R.id.alarm_sender_pic)
    lateinit var imgSenderPic: ImageView

    @BindView(R.id.alarm_sender_name)
    lateinit var txtSenderName: TextView

    @BindView(R.id.alarm_count)
    lateinit var txtAlarmCount: TextView

    @BindView(R.id.alarm_action_button)
    lateinit var alarmActionButton: Button

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    private val mAudioServiceConnection = object : ServiceConnection {
        // Called when the connection with the service is established
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // Because we have bound to an explicit
            // service that is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            val binder = service as AudioService.LocalBinder
            mAudioService = binder.service
            mBound = true

            //Attach broadcast receiver which updates alarm display UI using serializable extra
            attachAudioServiceBroadCastReceiver()

            //Replace image and name with message if no Roosters etc.
            //setDefaultDisplayProfile(true)
        }

        // Called when the connection with the service disconnects unexpectedly
        override fun onServiceDisconnected(className: ComponentName) {
            Log.e(TAG, "onServiceDisconnected")
            mBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BaseApplication.roosterApplicationComponent.inject(this)

        //Used to ensure alarm shows over lock-screen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON + WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or +WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        //This method hides the status bar, in reality this isn't very practical, mainly visual, so removed
        //        if(RoosterUtils.hasJellyBean()) {
        //            View decorView = getWindow().getDecorView();
        //            // Hide the status bar.
        //            int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
        //            decorView.setSystemUiVisibility(uiOptions);
        //            // Remember that you should never show the action bar if the
        //            // status bar is hidden, so hide that too if necessary.
        //            ActionBar actionBar = getActionBar();
        //            if(actionBar != null) actionBar.hide();
        //        } else {
        //            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        //        }

        initialize(R.layout.activity_device_alarm_full_screen)

        realmAlarmFailureLog.getAlarmFailureLogMillisSlot(intent?.getLongExtra(Extra.MILLIS_SLOT.name, -1L)) {
            it.seen = true
        }

        //Bind to audio service to allow playback and pausing of alarms in background
        val intent = Intent(this, AudioService::class.java)
        //0 indicates that service should not be restarted
        bindService(intent, mAudioServiceConnection, 0)

        //Set volume rocker to alarm stream
        volumeControlStream = AudioManager.STREAM_ALARM
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (mBound) unbindService(mAudioServiceConnection)
        try {
            if (receiver != null)
                unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
        // Close Realm object
        realmAlarmFailureLog.closeRealm()
    }

    override fun onUserLeaveHint() {
        //Detect when user elects to leave activity (home button, etc.)
        super.onUserLeaveHint()
        logAlarmUIInteraction()
        //Check if event as a result of action url click
        if (!actionUrlClicked) {
            mAudioService?.snoozeAudioState()
            if (mBound) unbindService(mAudioServiceConnection)
            mBound = false
            finish()
            startNextActivity()
        }
        //Clear flag
        actionUrlClicked = false
    }

    override fun onBackPressed() {
        mAudioService?.snoozeAudioState()?.let { logAlarmUIInteraction() }
        if (mBound) unbindService(mAudioServiceConnection)
        mBound = false
        finish()
        startNextActivity()
    }

    @OnClick(R.id.alarm_snooze_button)
    fun onAlarmSnoozeButtonClicked() {
        mAudioService?.snoozeAudioState()?.let { logAlarmUIInteraction() }
        if (mBound) unbindService(mAudioServiceConnection)
        mBound = false
        finish()
        startNextActivity()
    }

    @OnClick(R.id.alarm_dismiss)
    fun onAlarmDismissButtonClicked() {
        mAudioService?.dismissAlarm()?.let { logAlarmUIInteraction() }
        if (mBound) unbindService(mAudioServiceConnection)
        mBound = false
        finish()
        startNextActivity()
    }

    private fun startNextActivity() {
        startActivity(Intent(this, MessageStatusFragmentActivity::class.java))
    }

    @OnClick(R.id.alarm_action_button)
    fun onActionButtonClicked() {
        actionUrlClicked = true
        logAlarmUIInteraction()
        if (StrUtils.notNullOrEmpty(audioItem?.action_url?:"")) {
            try {
                val uri = Uri.parse(audioItem?.action_url?:"")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
                FA.LogMany(FA.Event.action_url_click::class.java,
                        arrayOf(FA.Event.action_url_click.Param.channel_title, FA.Event.action_url_click.Param.action_url),
                        arrayOf<Any>(audioItem?.name?:"", audioItem?.action_url?:""))
            } catch (e: Exception) {
                e.printStackTrace()
                alarmActionButton.visibility = View.GONE
                actionUrlClicked = false
            }

        }
    }

    @OnClick(R.id.skip_previous)
    fun onSkipPreviousButtonClicked() {
        mAudioService?.skipPrevious()?.let { logAlarmUIInteraction() }
    }

    @OnClick(R.id.skip_next)
    fun onSkipNextButtonClicked() {
        mAudioService?.skipNext()?.let { logAlarmUIInteraction() }
    }

    private fun logAlarmUIInteraction() {
        realmAlarmFailureLog.getAlarmFailureLogMillisSlot(intent?.getLongExtra(Extra.MILLIS_SLOT.name, -1L)) {
            it.interaction = true
        }
    }

    private fun attachAudioServiceBroadCastReceiver() {
        //Flag check for UI changes on load, broadcast receiver for changes while activity running
        //Broadcast receiver filter to receive UI updates
        val intentFilter = IntentFilter()
        intentFilter.addAction(Action.ALARM_DISPLAY.name)
        intentFilter.addAction(Action.ALARM_TIME_UP.name)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                //do something based on the intent's action
                try {
                    when (intent.action) {
                        Action.ALARM_DISPLAY.name -> {
                            //Update alarm UI based on audio service broadcast
                            audioItem = intent.extras?.getSerializable("audioItem") as DeviceAudioQueueItem?
                            alarmPosition = intent.getIntExtra("alarmPosition", alarmPosition)
                            alarmCount = intent.getIntExtra("alarmCount", alarmCount)
                            //                            if (intent.getBooleanExtra("multipleAudioFiles", false)) {
                            //                                skipNext.setVisibility(View.VISIBLE);
                            //                                skipPrevious.setVisibility(View.VISIBLE);
                            //                            } else {
                            //                                skipNext.setVisibility(View.INVISIBLE);
                            //                                skipPrevious.setVisibility(View.INVISIBLE);
                            //                            }
                            setAlarmUI()
                        }
                        Action.ALARM_TIME_UP.name -> {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON + WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or +WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                            finish()
                        }
                        else -> {
                        }
                    }
                } catch (e: RuntimeException) {
                    e.printStackTrace()
                }

            }
        }
        registerReceiver(receiver, intentFilter)
        //Once receiver is registered, try update UI from service
        mAudioService?.updateAlarmUI(false)
    }

    private fun setAlarmUI() {
        if (StrUtils.notNullOrEmpty(audioItem?.action_title) && StrUtils.notNullOrEmpty(audioItem?.action_url)) {
            alarmActionButton.text = audioItem?.action_title
            alarmActionButton.visibility = View.VISIBLE
        }

        if (StrUtils.notNullOrEmpty(audioItem?.picture) && StrUtils.notNullOrEmpty(audioItem?.name)) {
            //Both image and title are valid
            setProfilePic(audioItem?.picture?:"")
            txtSenderName.text = audioItem?.name
        } else if (StrUtils.notNullOrEmpty(audioItem?.name)) {
            //Set default image, keep title
            setDefaultDisplayProfile(false)
            txtSenderName.text = audioItem?.name
        } else {
            //Set default image and title
            setDefaultDisplayProfile(true)
        }
        txtAlarmCount.text = String.format("%s of %s", alarmPosition, alarmCount)
    }

    private fun setProfilePic(url: String) {
        //Reset scale type
        imgSenderPic.scaleType = ImageView.ScaleType.FIT_CENTER

        Picasso.with(this@DeviceAlarmFullScreenActivity).load(url)
                .resize(600, 600)
                .centerCrop()
                .into(imgSenderPic, object : Callback {
                    override fun onSuccess() {
                        val imageBitmap = (imgSenderPic.drawable as BitmapDrawable).bitmap
                        val imageDrawable = RoundedBitmapDrawableFactory.create(resources, imageBitmap)
                        imageDrawable.isCircular = true
                        imageDrawable.cornerRadius = Math.max(imageBitmap.width, imageBitmap.height) / 2.0f
                        imgSenderPic.setImageDrawable(imageDrawable)
                        alarm_sender_pic_progress.visibility = View.GONE
                    }

                    override fun onError() {
                        setDefaultDisplayProfile(false)
                    }
                })
    }

    private fun setDefaultDisplayProfile(overwriteTitle: Boolean) {
        //Bitmap bm = BitmapFactory.decodeResource(getResources(), R.drawable.logo_icon);
        //Drawable d = new BitmapDrawable(getResources(), bm);
        //Set max width to 600 pixels, as with channel image
        imgSenderPic.maxWidth = 600
        //Set center inside to ensure logo scaled to fit inside 600 px max width
        imgSenderPic.scaleType = ImageView.ScaleType.CENTER_INSIDE
        //Set logo as default image
        imgSenderPic.setImageResource(R.drawable.logo_icon)
        //Clear background (grey circle)
        imgSenderPic.background = null
        //Only overwrite title if it is not available
        if (overwriteTitle) {
            txtSenderName.setText(R.string.alarm_default_name)
        }
        alarm_sender_pic_progress.visibility = View.GONE
    }

    companion object {
        val TAG: String = DeviceAlarmFullScreenActivity::class.java.simpleName
    }
}
