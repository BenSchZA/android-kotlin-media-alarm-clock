/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserServiceCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import android.util.Pair
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ext.mediasession.DefaultPlaybackController
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.trackselection.TrackSelector
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.activity.DiscoverFragmentActivity
import com.roostermornings.android.adapter_data.ChannelManager
import com.roostermornings.android.domain.database.ChannelRooster
import com.roostermornings.android.media.MediaNotificationHelper
import com.roostermornings.android.util.RoosterUtils
import java.util.*
import java.util.concurrent.TimeUnit


class MediaService : MediaBrowserServiceCompat(),
        AudioManager.OnAudioFocusChangeListener{

    private val mThis: Context by lazy { this }

    private var mFocusRequest : AudioFocusRequest? = null

    private val mSession: MediaSessionCompat by lazy { MediaSessionCompat(this, "MediaService") }
    private val mSessionConnector: MediaSessionConnector by lazy { MediaSessionConnector(mSession, PlaybackController()) }
    private val mTransportControls: MediaControllerCompat.TransportControls by lazy { mSession.controller.transportControls }

    private val mPlaybackState: PlaybackStateCompat
        get() = mSession.controller.playbackState

    private val mCurrentMediaDescription: MediaDescriptionCompat
        get() = mSession.controller.metadata.description

    private var currentMediaItem: CurrentMediaItem = CurrentMediaItem("", -1, false)

    fun CurrentMediaItem.reset(): CurrentMediaItem {
        return CurrentMediaItem("", -1, false)
    }

    class CurrentMediaItem(val ID: String, val position: Long, val isPlaying: Boolean)

    private var mPlayerPreparing = false
    private var mHasPlayed = false
    private var mStopCalled = false

    private val mTrackSelector: TrackSelector = DefaultTrackSelector()
    private val mPlayer by lazy{ ExoPlayerFactory.newSimpleInstance(this, mTrackSelector) }

    private var lastVolume : Float = 0f

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val channelManager: ChannelManager by lazy { ChannelManager(this) }
    private var channelRoosters: ArrayList<ChannelRooster> = ArrayList()

    private var mediaSources: ArrayList<MediaSource> = ArrayList()

    private val mWiFiLock: WifiManager.WifiLock? by lazy { (this.getSystemService(Context.WIFI_SERVICE) as WifiManager?)?.createWifiLock(WifiManager.WIFI_MODE_FULL, "RadiophonyLock")  }

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()

        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                or MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS)

        //Repeat all in queue
        mPlayer.repeatMode = REPEAT_MODE_ALL
        mSession.setRepeatMode(REPEAT_MODE_ALL)
        //Activate session
        mSession.isActive = true

        mSession.controller.registerCallback(mMediaControllerCallback)

        mSessionConnector.setQueueNavigator(ChannelQueueNavigator())
        mSessionConnector.setErrorMessageProvider(MediaErrorMessageProvider())
        mSessionConnector.setPlayer(mPlayer, MediaPlaybackPreparer())

        setAttachedActivity()

        initNoisyReceiver()

        //Lock WiFi during service operation
        mWiFiLock?.acquire()

        super.setSessionToken(mSession.sessionToken)
    }

    override fun onDestroy() {
        super.onDestroy()

        abandonAudioFocus()
        unregisterReceiver(mNoisyReceiver)

        //Deactivate session
        mSession.isActive = false
        mSession.release()

        NotificationManagerCompat.from(this).cancelAll()

        if(mWiFiLock?.isHeld == true) mWiFiLock?.release()

        stopForeground(true)
    }

    private fun setAttachedActivity() {
        val context = applicationContext
        val intent = Intent(context, DiscoverFragmentActivity::class.java)
        val pi = PendingIntent.getActivity(context, 99 /*request code*/,
                intent, PendingIntent.FLAG_UPDATE_CURRENT)
        mSession.setSessionActivity(pi)
    }

    // Attempt to retrieve audio focus from Android OS
    @SuppressLint("NewApi")
    private fun retrieveAudioFocus(): Boolean {
        val result : Int
        if(RoosterUtils.hasO()) {
            val mPlaybackAttributes =  AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

            mFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(mPlaybackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build()

            result = audioManager.requestAudioFocus(mFocusRequest)
        } else {
            result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }

        return result == AudioManager.AUDIOFOCUS_GAIN
    }

    // Abandon audio focus when service destroyed
    private fun abandonAudioFocus() {
        if(RoosterUtils.hasO()) {
            mFocusRequest?.let{ audioManager.abandonAudioFocusRequest(it) }
        }
        else audioManager.abandonAudioFocus(this)
    }

    // Handles headphones coming unplugged - cannot be done through a manifest receiver
    private fun initNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(mNoisyReceiver, filter)
    }

    // When headphones disconnected, pause audio
    private val mNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mPlaybackState.state == PlaybackStateCompat.STATE_PLAYING) mTransportControls.pause()
        }
    }

    // When audio focus is lost, manage audio session properly, e.g. pause when call received
    //https://medium.com/google-developers/how-to-properly-handle-audio-interruptions-3a13540d18fa
    override fun onAudioFocusChange(focusChange: Int) {
        val state = mPlaybackState.state
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (state == PlaybackStateCompat.STATE_PLAYING)
                    mTransportControls.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                mTransportControls.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                lastVolume = mPlayer.volume
                mPlayer.volume = lastVolume*0.3f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (state == PlaybackStateCompat.STATE_STOPPED)
                    mTransportControls.play()
                mPlayer.volume = lastVolume
            }
        }
    }

    // Manage error messages and present to user
    class MediaErrorMessageProvider : MediaSessionConnector.ErrorMessageProvider {
        override fun getErrorMessage(playbackException: ExoPlaybackException?): Pair<Int, String> {
            return Pair(1, playbackException.toString())
        }
    }

    companion object {
        enum class CustomCommand {
            REFRESH
        }
    }

    inner class MediaPlaybackPreparer : MediaSessionConnector.PlaybackPreparer {

        override fun getSupportedPrepareActions(): Long {
            return (ACTION_PLAY_FROM_MEDIA_ID)
        }

        override fun onCommand(command: String?, extras: Bundle?, cb: ResultReceiver?) {
            when(command) {
                // Refresh media browser content and send result to subscribers
                CustomCommand.REFRESH.toString() -> {
                    notifyChildrenChanged(MEDIA_ID_ROOT)
                }
            }
        }

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) { TODO("not implemented") }
        override fun onPrepareFromSearch(query: String?, extras: Bundle?) { TODO("not implemented") }
        override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) { TODO("not implemented")  }
        override fun onPrepare() { TODO("not implemented") }
    }

    inner class ChannelQueueNavigator : TimelineQueueNavigator(mSession) {

        override fun getSupportedQueueNavigatorActions(player: Player?): Long {
            return super.getSupportedQueueNavigatorActions(player) or ACTION_SET_SHUFFLE_MODE_ENABLED
        }

        override fun getMediaDescription(windowIndex: Int): MediaDescriptionCompat {
            return getQueueItemMediaDescription(windowIndex)
        }

        override fun onSkipToPrevious(player: Player?) {
            super.onSkipToPrevious(player)
            mTransportControls.play()
        }

        override fun onSkipToNext(player: Player?) {
            super.onSkipToNext(player)
            mTransportControls.play()
        }

        override fun onSkipToQueueItem(player: Player?, id: Long) {
            // If called on currently playing item, toggle play, else skipToQueueItem
            if(this.getActiveQueueItemId(player) != id) {
                super.onSkipToQueueItem(player, id)
            }
        }

        override fun onSetShuffleModeEnabled(player: Player?, enabled: Boolean) {
            super.onSetShuffleModeEnabled(player, enabled)
//            if(enabled) {
//                mTransportControls.setShuffleMode(SHUFFLE_MODE_ALL)
//            } else {
//                mTransportControls.setShuffleMode(SHUFFLE_MODE_NONE)
//            }
        }
    }

    fun getQueueItemMediaDescription(windowIndex: Int) : MediaDescriptionCompat {
        // Create media description from ChannelRooster, for TimelineQueueNavigator
        val rooster: ChannelRooster = channelRoosters[windowIndex]

        val mMediaDescriptionCompatBuilder: MediaDescriptionCompat.Builder = MediaDescriptionCompat.Builder()
                .setTitle(rooster.name)
                .setSubtitle("subtitle")
                .setDescription(rooster.description)
                .setMediaId(rooster.channel_uid)
                .setMediaUri(Uri.parse(rooster.audio_file_url))
                .setIconUri(Uri.parse(rooster.photo))

        return mMediaDescriptionCompatBuilder.build()
    }

    inner class PlaybackController : DefaultPlaybackController() {
        override fun onRewind(player: Player) {
            // Override rewind method, to jump back by 10 second interval
            //super.onRewind(player)
            mTransportControls.seekTo(player.currentPosition - TimeUnit.SECONDS.toMillis(10))
            mTransportControls.play()
        }

        override fun onSeekTo(player: Player?, position: Long) {
            super.onSeekTo(player, position)
        }

        override fun onFastForward(player: Player) {
            // Override rewind method, to jump forward by 10 second interval
            //super.onFastForward(player)
            mTransportControls.seekTo(player.currentPosition + TimeUnit.SECONDS.toMillis(10))
            mTransportControls.play()
        }

        override fun getSupportedPlaybackActions(player: Player?): Long {
            return super.getSupportedPlaybackActions(player) or ACTION_SET_SHUFFLE_MODE_ENABLED
        }

        override fun onPlay(player: Player) {
            // Ensure audio focus is gained before playing
            if(retrieveAudioFocus()) {
                super.onPlay(player)
                // Ensure service is started to avoid being killed on client subscription disconnect
                if(mStopCalled) {
                    notifyChildrenChanged(MEDIA_ID_ROOT)
                    mStopCalled = false
                }
                startService(Intent(mThis, MediaService::class.java))
            }
            // Register that a media play action has been received
            if(!mHasPlayed) mHasPlayed = true

            // Ensure media session is active, to retrieve media commands
            if (!mSession.isActive) mSession.isActive = true
        }

        override fun onStop(player: Player) {
            super.onStop(player)
            //Stop service when media stopped
            stopSelf()
            mStopCalled = true
        }
    }

    // Update media notification whenever the metadata or playback state changes
    private val mMediaControllerCallback = object : MediaControllerCompat.Callback() {

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            super.onPlaybackStateChanged(state)
            // Only show notification after first play action
            when(state?.state) {
                PlaybackStateCompat.STATE_PLAYING -> {
                    if(mHasPlayed) MediaNotificationHelper.createNotification(mThis, mSession)
                }
                PlaybackStateCompat.STATE_PAUSED -> {
                    if(mHasPlayed) MediaNotificationHelper.createNotification(mThis, mSession)
                    stopForeground(false)
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            super.onMetadataChanged(metadata)
            // Only show notification after first play action
            // if(mHasPlayed) MediaNotificationHelper.createNotification(mThis, mSession)
        }
    }

    private val MEDIA_ID_ROOT = "MEDIA_ID_ROOT"
    private val MEDIA_ID_EMPTY_ROOT = "MEDIA_ID_EMPTY_ROOT"

    var currentParentId = "UNINITIALIZED"
    var currentResult: Result<List<MediaBrowserCompat.MediaItem>>? = null

    override fun onLoadChildren(parentId: String, result: MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>) {
        // Use result.detach to allow calling result.sendResult from another thread
        result.detach()

        currentParentId = parentId
        currentResult = result
        //Load media queue async, prepare player, and send to subscribed client when ready
        ChannelManager.onFlagChannelManagerDataListener = object : ChannelManager.Companion.OnFlagChannelManagerDataListener {
            override fun onChannelRoosterDataChanged(freshChannelRoosters: java.util.ArrayList<ChannelRooster>) {
                channelRoosters.clear()
                channelRoosters.addAll(freshChannelRoosters)

                mediaSources = ArrayList(channelRoosters.size)

                // Create MediaSource list to compile and pass to player via ConcatenatingMediaSource()
                for(item in channelRoosters) {
                    val dataSourceFactory = DefaultHttpDataSourceFactory("MediaService")
                    val extractor = DefaultExtractorsFactory()
                    val handler = Handler()

                    val mediaSource = ExtractorMediaSource(Uri.parse(item.audio_file_url),
                            dataSourceFactory,
                            extractor,
                            handler, null)

                    mediaSources.add(mediaSource)
                }

                // Create a listener to determine when player is prepared
                val playerEventListener = object : Player.EventListener {

                    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                        when(playbackState) {
                            Player.STATE_READY -> {
                                if(mPlayerPreparing) {
                                    // Prepare content to send to subscribed content
                                    loadChildrenImpl(currentParentId, currentResult as MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>)
                                    mPlayerPreparing = false
                                }
                            }
                            Player.STATE_IDLE -> {}
                            Player.STATE_BUFFERING -> {}
                            Player.STATE_ENDED -> {}
                        }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters?) {}
                    override fun onTracksChanged(trackGroups: TrackGroupArray?, trackSelections: TrackSelectionArray?) {}
                    override fun onPlayerError(error: ExoPlaybackException?) {}
                    override fun onLoadingChanged(isLoading: Boolean) {}
                    override fun onPositionDiscontinuity() {}
                    override fun onRepeatModeChanged(repeatMode: Int) {}
                    override fun onTimelineChanged(timeline: Timeline?, manifest: Any?) {}
                }

                // Cache last playing state before refreshing content
                currentMediaItem = if(mPlaybackState.state == STATE_PLAYING) {
                    CurrentMediaItem(mCurrentMediaDescription.mediaId ?: "" , mPlayer.currentPosition, true)
                } else {
                    currentMediaItem.reset()
                }
                // Prepare media content and wait for STATE_READY
                mPlayer.addListener(playerEventListener)
                mPlayerPreparing = true
                // ConcatenatingMediaSource enables us to create a *windowed* {} loop of media content [ ][ ] {[ ][ ]...x10...[ ][ ]} [ ]
                // Each window, managed by TimelineQueueNavigator(), is a maximum of 10 items long and dynamically loaded
                mPlayer.prepare(ConcatenatingMediaSource(*mediaSources.toTypedArray()))
            }

            override fun onSyncFinished() {
                // Prepare content to send to subscribed content
                loadChildrenImpl(currentParentId, currentResult as MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>)
                mPlayerPreparing = false
            }
        }

        channelManager.refreshChannelData(channelRoosters)
    }

    private fun loadChildrenImpl(parentMediaId: String, result: MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>) {
        var mediaItems : List<MediaBrowserCompat.MediaItem> = ArrayList()

        when(parentMediaId) {
            MEDIA_ID_ROOT -> {
                val tempMediaItems : MutableList<MediaBrowserCompat.MediaItem> = ArrayList(channelRoosters.size)

                // Iterate through channel rooster content and create MediaItems to pass to subscribed client
                 channelRoosters.forEachIndexed { index, _ ->
                     val mediaItem : MediaBrowserCompat.MediaItem = MediaBrowserCompat.MediaItem(getQueueItemMediaDescription(index),
                             MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
                     tempMediaItems.add(mediaItem)
                 }

                mediaItems = tempMediaItems.toList()

                // Re-initialize last playing item before refresh action
                if(currentMediaItem.isPlaying) {
                    mediaItems.indexOfFirst { it.mediaId == currentMediaItem.ID }.takeIf { it > -1 }?.let {
                        mTransportControls.skipToQueueItem(it.toLong())
                        if(currentMediaItem.position > -1)
                            mTransportControls.seekTo(currentMediaItem.position)
                        mTransportControls.play()
                    }
                }
            }
            // Since the client provided the empty root we'll just send back an empty list
            MEDIA_ID_EMPTY_ROOT -> {  }
            else -> {  }
        }

        result.sendResult(mediaItems)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserServiceCompat.BrowserRoot? {
        // Verify the client is authorized to browse media and return the root that
        // makes the most sense here. In this example we simply verify the package name
        // is the same as ours, but more complicated checks, and responses, are possible

        return if (clientPackageName != packageName) {
            // Allow the client to connect, but not browse, by returning an empty root
            MediaBrowserServiceCompat.BrowserRoot(MEDIA_ID_EMPTY_ROOT, null)
        } else MediaBrowserServiceCompat.BrowserRoot(MEDIA_ID_ROOT, null)
    }
}