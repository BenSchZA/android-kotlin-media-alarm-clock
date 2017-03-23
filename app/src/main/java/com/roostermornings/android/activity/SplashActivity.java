/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.service.notification.NotificationListenerService;

import com.google.firebase.auth.FirebaseUser;
import com.roostermornings.android.BuildConfig;
import com.roostermornings.android.R;
import com.roostermornings.android.activity.base.BaseActivity;
import com.roostermornings.android.receiver.BackgroundTaskReceiver;
import com.roostermornings.android.sqldata.AudioTableHelper;
import com.roostermornings.android.sqldata.DeviceAlarmTableHelper;

public class SplashActivity extends BaseActivity {

    FirebaseUser mFBUser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initialize(R.layout.activity_splash);
        mFBUser = getFirebaseUser();

        CountDownTimer countDownTimer = new CountDownTimer(2000, 2000) {
            @Override
            public void onTick(long millisUntilFinished) {

            }

            @Override
            public void onFinish() {

                //All users go through intro activity upon sign out -
                // this ensures cell number is entered and if old user they are on-boarded, no harm done
                if (mFBUser == null || mFBUser.getUid() == null) {
                    navigateToActivity(IntroFragmentActivity.class);
                } else {
                    //TODO: go to alarm creation for new user?
                    navigateToActivity(MyAlarmsFragmentActivity.class);
                }

            }
        };
        countDownTimer.start();
    }

    private void navigateToActivity(Class<? extends Activity> activityClass) {
        Intent i = new Intent(SplashActivity.this, activityClass);
        finish();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);startActivity(i);

    }

}
