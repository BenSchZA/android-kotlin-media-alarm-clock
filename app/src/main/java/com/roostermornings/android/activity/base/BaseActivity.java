/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity.base;

import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.TextInputEditText;
import android.support.design.widget.TextInputLayout;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.Theme;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.mobsandgeeks.saripaar.ValidationError;
import com.mobsandgeeks.saripaar.Validator;
import com.roostermornings.android.BaseApplication;
import com.roostermornings.android.R;
import com.roostermornings.android.activity.FriendsFragmentActivity;
import com.roostermornings.android.activity.MessageStatusActivity;
import com.roostermornings.android.activity.MyAlarmsFragmentActivity;
import com.roostermornings.android.activity.NewAudioRecordActivity;
import com.roostermornings.android.activity.SplashActivity;
import com.roostermornings.android.dagger.RoosterApplicationComponent;
import com.roostermornings.android.domain.User;
import com.roostermornings.android.fragment.base.BaseFragment;
import com.roostermornings.android.node_api.IHTTPClient;
import com.roostermornings.android.receiver.BackgroundTaskReceiver;
import com.roostermornings.android.service.UploadService;
import com.roostermornings.android.sqlutil.AudioTableController;
import com.roostermornings.android.sqlutil.AudioTableManager;
import com.roostermornings.android.sqlutil.DeviceAlarmController;
import com.roostermornings.android.sqlutil.DeviceAlarmTableManager;
import com.roostermornings.android.util.Constants;
import com.roostermornings.android.util.InternetHelper;

import java.util.Calendar;
import java.util.List;

import javax.inject.Inject;

import butterknife.ButterKnife;
import dagger.Module;
import dagger.Provides;

import static com.roostermornings.android.util.RoosterUtils.notNull;

public abstract class BaseActivity extends AppCompatActivity implements Validator.ValidationListener, BaseFragment.BaseActivityListener, UploadService.BaseActivityListener {

    private Dialog progressDialog;
    public FirebaseAuth mAuth;
    private FirebaseAuth.AuthStateListener mAuthListener;
    private static final String TAG = BaseActivity.class.getSimpleName();

    private static Calendar calendar = Calendar.getInstance();

    public DatabaseReference mDatabase;

    public static User mCurrentUser;

    @Inject Context AppContext;
    @Inject SharedPreferences sharedPreferences;
    @Inject DeviceAlarmController deviceAlarmController;
    @Inject AudioTableManager audioTableManager;
    @Inject BackgroundTaskReceiver backgroundTaskReceiver;

    protected abstract void inject(RoosterApplicationComponent component);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BaseApplication baseApplication = (BaseApplication) getApplication();

        //Inject Dagger dependencies
        baseApplication.getRoosterApplicationComponent().inject(this);

        //Set default application settings preferences - don't overwrite existing if false
        setPreferenceManagerDefaultSettings(false);

        mCurrentUser = baseApplication.mCurrentUser;

        //get reference to Firebase database
        mDatabase = baseApplication.mDatabase;

        // [START auth_state_listener]
        mAuthListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    // User is signed in
                    Log.d(TAG, "onAuthStateChanged:signed_in:" + user.getUid());
                } else {
                    // User is signed out
                    Log.d(TAG, "onAuthStateChanged:signed_out");
                }
            }
        };
    }

    public BaseActivity(){}

    @Override
    public boolean checkInternetConnection() {
        if(!checkMobileDataConnection()) {
            Toast.makeText(getApplicationContext(), "'Download/upload on mobile data' is disabled in Settings, " +
                    "please enable this and try again.", Toast.LENGTH_LONG).show();
        }

        if (this.noInternetConnection()) {
            Toast.makeText(getApplicationContext(), "No internet connection was found, please " +
                    "connect and try again.", Toast.LENGTH_LONG).show();
            return false;
        }

        //checkFirebaseConnection(); #TODO - check why this is randomly failing
        return true;
    }

    private boolean checkMobileDataConnection() {
        return !(mobileDataConnection() && !sharedPreferences.getBoolean(Constants.USER_SETTINGS_DOWNLOAD_ON_DATA, true));
    }

    protected void checkFirebaseConnection() {

        FirebaseDatabase.getInstance().getReference(".info/connected")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        if (snapshot.getValue(Boolean.class)) {
                            Log.i(TAG, "Firebase CONNECTED");
                        } else {
                            Log.i(TAG, "Firebase NOT CONNECTED");
                            Toast.makeText(AppContext, "The application could not connect to the " +
                                    "Rooster backend, please check your internet connection and try again.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "onCancelled: ", error.toException());
                    }
                });


    }

    private boolean noInternetConnection() {
        return InternetHelper.noInternetConnection(this);
    }

    private boolean mobileDataConnection() {
        return  InternetHelper.mobileDataConnection(this);
    }

    protected void startHomeActivity() {
        Intent homeIntent = new Intent(this, MyAlarmsFragmentActivity.class);
        startActivity(homeIntent);
    }

    protected void initialize(int layoutId) {

        setContentView(layoutId);

        //Bind to butterknife delegate
        //Calls to ButterKnife.bind can be made anywhere you would otherwise put findViewById calls.
        ButterKnife.bind(this);

    }

    @Override
    public IHTTPClient apiService() {

        BaseApplication baseApplication = (BaseApplication) getApplication();

        return baseApplication.getAPIService();
    }

    @Override
    public FirebaseUser getFirebaseUser() {
        if (mAuth == null) mAuth = FirebaseAuth.getInstance();
        return mAuth.getCurrentUser();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mAuth == null) mAuth = FirebaseAuth.getInstance();
        mAuth.addAuthStateListener(mAuthListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mAuthListener != null) {
            mAuth.removeAuthStateListener(mAuthListener);
        }
    }

    //TODO
//    @Override
//    public void onResume() {
//        super.onResume();
//        if(getFirebaseUser() != null) startServices(true);
//    }
//
//    @Override
//    public void onPause() {
//        super.onPause();
//        startServices(false);
//    }

    public void showProgressDialog() {
        if (progressDialog == null) {
            progressDialog = showIndeterminateProgress();
        } else {
            progressDialog.show();
        }

    }

    public void hideProgressDialog() {
        if (progressDialog != null) progressDialog.hide();
    }

    private Dialog showIndeterminateProgress() {
        final Dialog dialog = new Dialog(this,
                android.R.style.Theme_Translucent);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // here we set layout of progress dialog
        //dialog.setContentView(R.layout.dialog_custom_progress);
        dialog.setCancelable(false);
        dialog.show();
        return dialog;
    }

    @Override
    public void onValidationSucceeded() {
    }

    @Override
    public void onValidationFailed(List<ValidationError> errors) {
        for (ValidationError error : errors) {
            View view = error.getView();
            String message = error.getCollatedErrorMessage(this);

            if (view instanceof TextInputEditText) {

                TextInputLayout parent = (TextInputLayout) view.getParent();
                parent.setError(message);

            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        }
    }

    public void hideSoftKeyboard() {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }

    public void signOut() {
        try {
            //Ensure no audio remaining from old user
            audioTableManager.removeAllSocialAudioItems();
            audioTableManager.removeAllChannelAudioFiles();
            //Ensure no alarms left from old user
            deviceAlarmController.deleteAllLocalAlarms();
            //Cancel background task intents
            backgroundTaskReceiver.scheduleBackgroundCacheFirebaseData(this, false);
            backgroundTaskReceiver.scheduleBackgroundDailyTask(this, false);
            backgroundTaskReceiver.scheduleBackgroundUpdateNotificationsTask(this, false);
            //Set default application settings preferences - don't overwrite existing if false
            setPreferenceManagerDefaultSettings(true);
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
        //End user session - auth state listener in BaseApplication will be triggered
        // and necessary signout procedure performed
        mAuth.signOut();

        //Go to splash activity and onboarding
        Intent intent = new Intent(BaseActivity.this, SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void setPreferenceManagerDefaultSettings(Boolean overwrite) {
        // ensure user settings are set to default once when new user
        // As long as you set the third argument to false, you can safely call this method
        // every time your activity starts without overriding the user's saved preferences
        // by resetting them to the defaults. However, if you set it to true, you will
        // override any previous values with the defaults.

        if(overwrite) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.remove("pref_key_user_settings").apply();
        }
        PreferenceManager.setDefaultValues(this, R.xml.application_user_settings, overwrite);
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static void setBadge(Context context, int count) {
        String launcherClassName = getLauncherClassName(context);
        if (launcherClassName == null) {
            return;
        }
        Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
        intent.putExtra("badge_count", count);
        intent.putExtra("badge_count_package_name", context.getPackageName());
        intent.putExtra("badge_count_class_name", launcherClassName);
        context.sendBroadcast(intent);
    }

    private static String getLauncherClassName(Context context) {

        PackageManager pm = context.getPackageManager();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo resolveInfo : resolveInfos) {
            String pkgName = resolveInfo.activityInfo.applicationInfo.packageName;
            if (pkgName.equalsIgnoreCase(context.getPackageName())) {
                return resolveInfo.activityInfo.name;
            }
        }
        return null;
    }

    public void requestPermissionReadContacts() {
        // Here, thisActivity is the current activity
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    android.Manifest.permission.READ_CONTACTS)) {

                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.

            } else {

                // No explanation needed, we can request the permission.

                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_CONTACTS},
                        Constants.MY_PERMISSIONS_REQUEST_READ_CONTACTS);

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        }
    }

    public void requestPermissionIgnoreBatteryOptimization(Context context) {
        //Google wasn't happy with us programatically requesting this permission, so a dialog will have to do
//        String packageName = getPackageName();
//        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
//        RoosterUtils.hasM() && pm.isIgnoringBatteryOptimizations(packageName) &&

        if(!sharedPreferences.getBoolean(Constants.PERMISSIONS_DIALOG_OPTIMIZATION, false)) {

            new MaterialDialog.Builder(context)
                    .theme(Theme.LIGHT)
                    .content(R.string.dialog_permissions_explainer)
                    .neutralText(R.string.neutral)
                    .negativeColorRes(R.color.grey)
                    .onNeutral(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean(Constants.PERMISSIONS_DIALOG_OPTIMIZATION, true);
                            editor.apply();
                            startActivity(getIntent());
                        }
                    })
                    .show();
        }
    }

    public boolean setupToolbar(TextView toolbarTitle, String title) {
        try {
            Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null)
                getSupportActionBar().setDisplayShowTitleEnabled(false);
            if (toolbarTitle != null && title != null) toolbarTitle.setText(title);

            return true;
        } catch(NullPointerException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean setButtonBarSelection() {
        try {
            if (this instanceof MyAlarmsFragmentActivity)
                findViewById(R.id.home_my_alarms).setSelected(true);
            else if (this instanceof MessageStatusActivity)
                findViewById(R.id.home_my_uploads).setSelected(true);
            else if (this instanceof NewAudioRecordActivity)
                findViewById(R.id.home_record_audio).setSelected(true);
            else if (this instanceof FriendsFragmentActivity)
                findViewById(R.id.home_friends).setSelected(true);
            else
                return false;
            return true;
        } catch(NullPointerException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean setDayNightTheme() {
        try {
            String[] dayNightThemeArrayEntries = getResources().getStringArray(R.array.user_settings_day_night_theme_entry_values);
            SharedPreferences defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(AppContext);

            if (dayNightThemeArrayEntries[0].equals(defaultSharedPreferences.getString(Constants.USER_SETTINGS_DAY_NIGHT_THEME, ""))) {
                if (calendar.get(Calendar.HOUR_OF_DAY) >= 17) {
                    return setThemeNight();
                } else if (calendar.get(Calendar.HOUR_OF_DAY) > 7) {
                    return setThemeDay();
                }
            } else if (dayNightThemeArrayEntries[1].equals(defaultSharedPreferences.getString(Constants.USER_SETTINGS_DAY_NIGHT_THEME, ""))) {
                return setThemeDay();
            } else if (dayNightThemeArrayEntries[2].equals(defaultSharedPreferences.getString(Constants.USER_SETTINGS_DAY_NIGHT_THEME, ""))) {
                return setThemeNight();
            } else {
                if (calendar.get(Calendar.HOUR_OF_DAY) >= 17) {
                    return setThemeNight();
                } else if (calendar.get(Calendar.HOUR_OF_DAY) > 7) {
                    return setThemeDay();
                }
            }

            return true;
        } catch (NullPointerException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean setThemeDay(){
        try {
            if(findViewById(R.id.main_content) != null) findViewById(R.id.main_content).setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.main_background_layer_list_day, null));
            if(findViewById(R.id.toolbar) != null) findViewById(R.id.toolbar).setBackgroundColor(ResourcesCompat.getColor(getResources(), R.color.rooster_blue, null));
            if(findViewById(R.id.tabs) != null) findViewById(R.id.tabs).setBackgroundColor(ResourcesCompat.getColor(getResources(), R.color.rooster_blue, null));

            return true;
        } catch (NullPointerException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean setThemeNight(){
        try {
            if(findViewById(R.id.main_content) != null) findViewById(R.id.main_content).setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.main_background_layer_list_night, null));
            if(findViewById(R.id.toolbar) != null) findViewById(R.id.toolbar).setBackgroundColor(ResourcesCompat.getColor(getResources(), R.color.rooster_dark_blue, null));
            if(findViewById(R.id.tabs) != null) findViewById(R.id.tabs).setBackgroundColor(ResourcesCompat.getColor(getResources(), R.color.rooster_dark_blue, null));

            return true;
        } catch (NullPointerException e) {
            e.printStackTrace();
            return false;
        }
    }
}
