/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.v4.app.NotificationCompat;

import com.roostermornings.android.R;
import com.roostermornings.android.sqlutil.DeviceAudioQueueItem;
import com.roostermornings.android.sqlutil.AudioTableManager;
import com.roostermornings.android.util.Constants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

//Service to manage playing and pausing audio during Rooster alarm
public class AudioService extends Service {

    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();

    private static Timer timer = new Timer();

    private MediaPlayer mediaPlayerDefault;
    private MediaPlayer mediaPlayerRooster;

    private DeviceAudioQueueItem audioItem;

    protected AudioService mThis = this;

    ArrayList<DeviceAudioQueueItem> audioItems = new ArrayList<>();
    AudioTableManager audioTableManager = new AudioTableManager(this);

    private int alarmCycle;
    private String alarmUid;
    private int playDuration;
    private int alarmCount;
    private int alarmPosition;
    private File file;

    private int currentPositionRooster;

    public AudioService() {
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public AudioService getService() {
            // Return this instance of AudioService so clients can call public methods
            return AudioService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /** methods for clients */

    public void updateAlarmUI() {
        //Send broadcast message to notify all receivers of new data, in this case UI data
        Intent intent = new Intent(Constants.ACTION_ALARMDISPLAY);
        Bundle bundle = new Bundle();
        bundle.putSerializable("audioItem", audioItem);
        bundle.putInt("alarmPosition", alarmPosition);
        bundle.putInt("alarmCount", alarmCount);
        intent.putExtras(bundle);
        sendBroadcast(intent);
    }

    public void startAlarmRoosters(String alarmUid) {
        this.alarmUid = alarmUid;

        //Check if Social and Channel alarm content exists, else startDefaultAlarmTone
        ArrayList<DeviceAudioQueueItem> channelAudioItems;
        channelAudioItems = audioTableManager.extractAlarmChannelAudioFiles(mThis.alarmUid);
        ArrayList<DeviceAudioQueueItem> socialAudioItems;
        socialAudioItems = audioTableManager.extractSocialAudioFiles();

        if(!socialAudioItems.isEmpty() || !channelAudioItems.isEmpty()) {
            this.alarmCycle = 0;
            startAlarmSocialRoosters();
        } else {
            startDefaultAlarmTone();
        }
    }

    private void startAlarmSocialRoosters() {

        //Increment alarmCycle, only loop rooster content 5 times
        //after which control handed to activity to endService and turn off screen
        if(alarmCycle == 5) {
            notifyActivityTimesUp();
            return;
        }
        this.alarmCycle++;

        this.audioItems = audioTableManager.extractSocialAudioFiles();
        alarmCount = audioItems.size();

        //Check conditions for playing default tone: people must wake up!
        if (this.audioItems == null || this.audioItems.isEmpty()) {
            mThis.audioItems = audioTableManager.extractAlarmChannelAudioFiles(mThis.alarmUid);
            if(!mThis.audioItems.isEmpty()) playChannelRooster(mThis.audioItems.get(0));
            else startDefaultAlarmTone();
            return;
        }

        if(currentPositionRooster < 1){
            alarmPosition = 0;
            playSocialRooster(audioItems.get(0));
        } else{
            try {
                mediaPlayerRooster.seekTo(currentPositionRooster);
                mediaPlayerRooster.start();
                this.audioItem = audioItems.get(0);
                //Send broadcast to DeviceAlarmFullScreenActivity with UI data
                updateAlarmUI();
            } catch(NullPointerException e){
                e.printStackTrace();
            }
        }
    }

    private void playSocialRooster(final DeviceAudioQueueItem audioItem) {
        foregroundNotification("Social Rooster playing");

        mThis.audioItem = audioItem;

        mediaPlayerRooster = new MediaPlayer();
        //Set media player to alarm volume
        mediaPlayerRooster.setAudioStreamType(AudioManager.STREAM_ALARM);

        file = new File(getFilesDir() + "/" + audioItem.getFilename());

        try {
            mediaPlayerRooster.setDataSource(file.getPath());

            mediaPlayerRooster.prepareAsync();
            mediaPlayerRooster.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    mediaPlayerRooster.start();

                    //Set alarm count display
                    alarmPosition++;
                    //Send broadcast to DeviceAlarmFullScreenActivity with UI data
                    updateAlarmUI();

                    mediaPlayerRooster.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            //TODO: this isn't used anymore, due to looping - maybe check volume?
                            //playDuration used to check that audio has played and for specified period, otherwise set default alarm
                            mThis.playDuration += mediaPlayerRooster.getDuration();
                            //delete record from arraylist
                            mThis.audioItems.remove(mThis.audioItem);
                            //set audio file entry in SQL db as listened; to be removed when AudioService ends
                            audioTableManager.setListened(audioItem.getId());
                            //Play channel rooster if social rooster list is empty, else play next file
                            if (mThis.audioItems.isEmpty()) {
                                mThis.audioItems = audioTableManager.extractAlarmChannelAudioFiles(mThis.alarmUid);
                                if(!mThis.audioItems.isEmpty()) playChannelRooster(mThis.audioItems.get(0));
                                else startAlarmSocialRoosters();
                            } else{
                                playSocialRooster(mThis.audioItems.get(0));
                            }
                        }
                    });
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            //Social rooster will never play... let's not go here
            //delete file
            file.delete();
            //delete record from AudioTable SQL DB
            audioTableManager.removeAudioFile(audioItem.getId());
            //delete record from arraylist
            mThis.audioItems.remove(audioItem);
        }
    }

    private void playChannelRooster(final DeviceAudioQueueItem audioItem) {
        foregroundNotification("Channel Rooster playing");

        mThis.audioItem = audioItem;

        //Reset position counter for single channel
        alarmPosition = 0;
        alarmCount = 0;

        mediaPlayerRooster = new MediaPlayer();
        //Set media player to alarm volume
        mediaPlayerRooster.setAudioStreamType(AudioManager.STREAM_ALARM);

        file = new File(getFilesDir() + "/" + audioItem.getFilename());

        try {
            mediaPlayerRooster.setDataSource(file.getPath());

            mediaPlayerRooster.prepareAsync();
            mediaPlayerRooster.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    mediaPlayerRooster.start();

                    //Set alarm count display
                    alarmPosition++;
                    //Send broadcast to DeviceAlarmFullScreenActivity with UI data
                    updateAlarmUI();

                    mediaPlayerRooster.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            //playDuration used to check that audio has played and for specified period, otherwise set default alarm
                            mThis.playDuration += mediaPlayerRooster.getDuration();
                            //delete record from arraylist
                            mThis.audioItems.remove(mThis.audioItem);
                            //set audio file entry in SQL db as listened; to be removed when AudioService ends
                            audioTableManager.setListened(audioItem.getId());

                            startAlarmSocialRoosters();
                        }
                    });
                }
            });
        } catch (IOException e) {
            e.printStackTrace();

            //delete file
            file.delete();
            //delete record from AudioTable SQL DB
            audioTableManager.removeAudioFile(audioItem.getId());
            //delete record from arraylist
            mThis.audioItems.remove(audioItem);
        }
    }

    public void endService(ServiceConnection conn) {
        //delete record and file of all listened audio files
        for (DeviceAudioQueueItem audioItem :
             audioTableManager.selectListened()) {
            file = new File(getFilesDir() + "/" + audioItem.getFilename());
            if(file.delete()){
                audioTableManager.removeAudioFile(audioItem.getId());
            }
        }
        //delete record from arraylist
        audioItems.clear();
        //clear variables
        playDuration = 0;
        alarmCount = 0;
        alarmPosition = 0;
        currentPositionRooster = 0;
        //ensure no alarms still playing...
        stopVibrate();
        stopAlarmAudio();
        //unbind from and kill service
        stopForeground(true);
        try {
            this.unbindService(conn);
        } catch(IllegalArgumentException e){
            e.printStackTrace();
        }
        this.stopSelf();
    }

    //Set timer to kill alarm after 5 minutes
    private void startTimer() {
        timer.schedule(new timerTask(), Constants.ALARM_DEFAULTTIME);
    }

    private class timerTask extends TimerTask {
        public void run() {
            notifyActivityTimesUp();
        }
    }

    public void notifyActivityTimesUp() {
        //Send broadcast message to notify receiver to stop alarm and release wakelock
        Intent intent = new Intent(Constants.ACTION_ALARMTIMESUP);
        sendBroadcast(intent);
    }

    public void pauseSocialRooster() {
        foregroundNotification("Social Rooster paused");

        mediaPlayerRooster.pause();
        currentPositionRooster = mediaPlayerRooster.getCurrentPosition();
    }

    public void pauseDefaultAlarmTone() {
        foregroundNotification("Alarm tone paused");

        mediaPlayerDefault.stop();
    }

    public void startVibrate() {
        foregroundNotification("Alarm vibrate active");
    }

    public void stopVibrate() {
        stopForeground(true);
        //If vibrating then cancel
        Vibrator vibrator = (Vibrator) getApplicationContext().getSystemService(VIBRATOR_SERVICE);
        if (vibrator.hasVibrator()) {
            vibrator.cancel();
        }
    }

    public void snoozeAudioState(){
        try {
            if (mediaPlayerRooster.isPlaying()) pauseSocialRooster();
            if (mediaPlayerDefault.isPlaying()) pauseDefaultAlarmTone();
        } catch(NullPointerException e){
            e.printStackTrace();
            stopAlarmAudio();
        }
        //If vibrating then cancel
        Vibrator vibrator = (Vibrator) getApplicationContext().getSystemService(VIBRATOR_SERVICE);
        if (vibrator.hasVibrator()) {
            vibrator.cancel();
        }
    }

    public void startDefaultAlarmTone() {
        foregroundNotification("Alarm ringtone playing");

        mediaPlayerDefault = new MediaPlayer();

        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        //In case no alarm tone previously set
        if (notification == null) {
            notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (notification == null) {
                notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
        }
        try {
            mediaPlayerDefault = new MediaPlayer();
            mediaPlayerDefault.setAudioStreamType(AudioManager.STREAM_ALARM);
            mediaPlayerDefault.setDataSource(this, notification);
            mediaPlayerDefault.setLooping(true);
            mediaPlayerDefault.prepareAsync();

            mediaPlayerDefault.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    mediaPlayerDefault.start();
                    startTimer();
                }});

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopAlarmAudio() {
        stopForeground(true);
        //If default tone or media playing then stop
        try {
            if (mediaPlayerDefault != null && mediaPlayerDefault.isPlaying()) {
                mediaPlayerDefault.stop();
                mediaPlayerDefault.release();
            }
        } catch(IllegalStateException e) {
                e.printStackTrace();
        }
        try {
            if (mediaPlayerRooster != null && mediaPlayerRooster.isPlaying()) {
                mediaPlayerRooster.stop();
                mediaPlayerRooster.release();
                currentPositionRooster = 0;
            }
        } catch(IllegalStateException e) {
            e.printStackTrace();
        }
    }

    private void foregroundNotification(String state) {

        Intent notificationIntent = new Intent(this, AudioService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification notification=new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.logo)
                .setContentText("Rooster Mornings: " + state)
                .setContentIntent(pendingIntent).build();

        startForeground(Constants.AUDIOSERVICE_NOTIFICATION_ID, notification);
    }
}
