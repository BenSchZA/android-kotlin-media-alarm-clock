/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity.base

import android.accounts.Account
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.design.widget.TextInputEditText
import android.support.design.widget.TextInputLayout
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import butterknife.ButterKnife
import butterknife.OnClick
import butterknife.Optional
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.Theme
import com.crashlytics.android.Crashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.mobsandgeeks.saripaar.ValidationError
import com.mobsandgeeks.saripaar.Validator
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.*
import com.roostermornings.android.apis.GoogleIHTTPClient
import com.roostermornings.android.apis.NodeIHTTPClient
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.Alarm
import com.roostermornings.android.domain.database.User
import com.roostermornings.android.firebase.AuthManager
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.geolocation.GeoHashUtils
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Flag
import com.roostermornings.android.keys.PrefsKey
import com.roostermornings.android.realm.RealmAlarmFailureLog
import com.roostermornings.android.receiver.BackgroundTaskReceiver
import com.roostermornings.android.service.FirebaseListenerService
import com.roostermornings.android.service.ForegroundService
import com.roostermornings.android.sqlutil.AudioTableManager
import com.roostermornings.android.sqlutil.DeviceAlarmController
import com.roostermornings.android.sqlutil.DeviceAlarmTableManager
import com.roostermornings.android.util.*
import com.roostermornings.android.util.Constants.AUTHORITY
import javax.inject.Inject
import javax.inject.Named

abstract class BaseActivity : AppCompatActivity(), Validator.ValidationListener, BaseFragment.BaseActivityListener {

    private var mAuth: FirebaseAuth? = null
    private var mAuthListener: FirebaseAuth.AuthStateListener? = null

    private var roosterNotificationReceiver: BroadcastReceiver? = null
    private var requestNotificationReceiver: BroadcastReceiver? = null
    private var foregroundReceiver: BroadcastReceiver? = null

    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject @field:Named("default") lateinit var defaultSharedPreferences: SharedPreferences
    @Inject lateinit var deviceAlarmController: DeviceAlarmController
    @Inject lateinit var audioTableManager: AudioTableManager
    @Inject lateinit var deviceAlarmTableManager: DeviceAlarmTableManager
    @Inject lateinit var backgroundTaskReceiver: BackgroundTaskReceiver
    @Inject lateinit var mDatabase: DatabaseReference
    @Inject lateinit var mAccount: Account
    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var lifeCycle: LifeCycle
    @Inject lateinit var realmAlarmFailureLog: RealmAlarmFailureLog
    @Inject lateinit var geoHashUtils: GeoHashUtils

    override val googleApiService: GoogleIHTTPClient
        get() = (application as BaseApplication).mGoogleAPIService

    override val nodeApiService: NodeIHTTPClient
        get() = (application as BaseApplication).mNodeAPIService

    var firebaseUser: FirebaseUser? = null
    @Inject
    fun BaseActivity(firebaseUser: FirebaseUser?) {
        this.firebaseUser = firebaseUser
    }

    init {
        if (mAuth == null) mAuth = FirebaseAuth.getInstance()
        mCurrentUser = BaseApplication.mCurrentUser
    }

    abstract fun inject(component: RoosterApplicationComponent)

    public override fun onStart() {
        super.onStart()
        mAuthListener?.let { mAuth?.addAuthStateListener(it) }
    }

    public override fun onStop() {
        super.onStop()
        mAuthListener?.let { mAuth?.removeAuthStateListener(it) }
        foregroundReceiver?.let {
            unregisterReceiver(foregroundReceiver)
            foregroundReceiver = null
        }
    }

    public override fun onResume() {
        super.onResume()

        // Setup day/night theme selection (based on settings, and time)
        val themeManager = ThemeManager(this)
        themeManager.setDayNightTheme(defaultSharedPreferences)

        sharedPreferences.edit()
                .putBoolean(PrefsKey.IS_ACTIVITY_FOREGROUND.name, true)
                .apply()

        /*The foreground receiver is intended to run methods once on app launch,
        the ForegroundService sends a broadcast when this occurs. */
        foregroundReceiver = object: BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                performOnceOnForeground()
            }
        }
        val foregroundIntentFilter = IntentFilter()
        foregroundIntentFilter.addAction(PrefsKey.APP_BROUGHT_FOREGROUND.name)

        registerReceiver(foregroundReceiver, foregroundIntentFilter)

        startService(Intent(this, ForegroundService::class.java))

        if(this is MyAlarmsFragmentActivity)
            // Check if first entry
            lifeCycle.performInception()
    }

    public override fun onPause() {
        super.onPause()
        sharedPreferences.edit()
                .putBoolean(PrefsKey.IS_ACTIVITY_FOREGROUND.name, false)
                .apply()
    }

    private fun performOnceOnForeground() {
        firebaseUser?.let {
            FirebaseAnalytics.getInstance(this).setUserId(it.uid)
            // Log new Crashlytics user
            // You can call any combination of these three methods
            Crashlytics.setUserIdentifier(firebaseUser?.uid)
            Crashlytics.setUserEmail(firebaseUser?.email)
            Crashlytics.setUserName(firebaseUser?.displayName)
        }

        // Log last seen in user metrics, to enable clearing stagnant data
        UserMetrics.updateLastSeen()

        // Update any corrupt user names in user_metrics, from user profile
        checkAndUpdateCorruptUserName()

        LifeCycle.performMethodOnceInDay {
            // Log active day
            UserMetrics.logActiveDays()
        }

        // Check if the user's geohash location entry is still valid
        geoHashUtils.checkUserGeoHash()

        // Log current app version
        // Handled by FCF as version name
        //UserMetrics.updateVersionCode()

        // Process any alarm failures
        realmAlarmFailureLog.processAlarmFailures(true)

        // Set shared pref to indicate whether mobile number is valid
        FirebaseNetwork.flagValidMobileNumber(this, false)
        // Set user properties like sign-in method etc.
        FirebaseNetwork.setUserProps()
    }

    private fun checkAndUpdateCorruptUserName() {
        val METHOD_RUN_CHECK = "CORRUPT_USERNAME_UPDATE_RUN"

        if(sharedPreferences.getBoolean(METHOD_RUN_CHECK, false)) return

        // Check for corrupt username entry in user_metrics and update if necessary
        FirebaseNetwork.getRoosterUser(firebaseUser?.uid) { roosterUser ->
            val fDB = FirebaseDatabase.getInstance().reference
            val fUser = FirebaseAuth.getInstance().currentUser

            val userListener = object : ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    if(dataSnapshot.hasChild("user_name")) {
                        val metricsUserName = dataSnapshot.child("user_name").value
                        if(roosterUser?.user_name?.isNotBlank() == true && roosterUser.user_name != metricsUserName) {
                            dataSnapshot.child("user_name").ref.setValue(roosterUser.user_name)
                        }
                    } else if(dataSnapshot.exists() && roosterUser?.user_name?.isNotBlank() == true) {
                        dataSnapshot.child("user_name").ref.setValue(roosterUser.user_name)
                    }

                    sharedPreferences.edit().putBoolean(METHOD_RUN_CHECK, true).apply()
                }

                override fun onCancelled(databaseError: DatabaseError) {}
            }

            if(!fUser?.uid.isNullOrBlank()) {
                val ref = fDB.child("user_metrics/${fUser?.uid}")
                ref.keepSynced(true)
                ref.addListenerForSingleValueEvent(userListener)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inject Dagger dependencies
        BaseApplication.roosterApplicationComponent.inject(this)

        // Set default application settings preferences - don't overwrite existing if false
        setPreferenceManagerDefaultSettings(false)

        startFirebaseListenerService()
    }

    private fun startFirebaseListenerService() {
        // Start Firebase listeners applicable to all activities - primarily to update notifications
        if (!isServiceRunning(FirebaseListenerService::class.java))
            startService(Intent(applicationContext, FirebaseListenerService::class.java))
    }

    override fun checkInternetConnection(): Boolean {
        if (!checkMobileDataConnection()) {
            Toaster.makeToast(applicationContext, "'Download/upload on mobile data' is disabled in Settings, " + "please enable this and try again.", Toast.LENGTH_LONG)
        }
        if (this.noInternetConnection()) {
            Toaster.makeToast(applicationContext, "No internet connection was found, please " + "connect and try again.", Toast.LENGTH_LONG)
            return false
        }
        return true
    }

    private fun checkMobileDataConnection(): Boolean {
        return !(mobileDataConnection() && !defaultSharedPreferences.getBoolean(Constants.USER_SETTINGS_DOWNLOAD_ON_DATA, true))
    }

    private fun noInternetConnection(): Boolean {
        return InternetHelper.noInternetConnection(this)
    }

    private fun mobileDataConnection(): Boolean {
        return InternetHelper.mobileDataConnection(this)
    }

    protected fun startHomeActivity() {
        val homeIntent = Intent(this, MyAlarmsFragmentActivity::class.java)
        startActivity(homeIntent)
    }

    protected fun initialize(layoutId: Int) {
        setContentView(layoutId)

        // Bind to butterknife delegate
        // Calls to ButterKnife.bind can be made anywhere you would otherwise put findViewById calls.
        ButterKnife.bind(this)
    }

    override fun onValidationSucceeded() {}

    override fun onValidationFailed(errors: List<ValidationError>) {
        errors.forEach {
            val view = it.view
            val message = it.getCollatedErrorMessage(this)

            if (view is TextInputEditText) {
                val parent = view.getParent() as TextInputLayout
                parent.error = message
            } else {
                Toaster.makeToast(this, message, Toast.LENGTH_LONG)
            }
        }
    }

    fun signOut() {
        // Ensure no audio remaining from old user
        audioTableManager.removeAllSocialAudioItems()
        audioTableManager.removeAllChannelAudioFiles()
        // Ensure no alarms left from old user
        deviceAlarmController.deleteAllLocalAlarms()
        // Cancel background task intents
        backgroundTaskReceiver.scheduleBackgroundDailyTask(this, false)
        ContentResolver.cancelSync(mAccount, AUTHORITY)
        // Set default application settings preferences - don't overwrite existing if false
        setPreferenceManagerDefaultSettings(true)
        sharedPreferences.edit().clear().apply()
        // Clear specific persistent collections from shared prefs
        clearJSONPersistence()

        // End user session - auth state listener in BaseApplication will be triggered
        // and necessary signout procedure performed
        authManager.signOut()

        // Go to splash activity and onboarding
        val intent = Intent(this@BaseActivity, SplashActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }

    private fun setPreferenceManagerDefaultSettings(overwrite: Boolean) {
        // ensure user settings are set to default once when new user
        // As long as you set the third argument to false, you can safely call this method
        // every time your activity starts without overriding the user's saved preferences
        // by resetting them to the defaults. However, if you set it to true, you will
        // override any previous values with the defaults.

        if (overwrite) {
            val editor = defaultSharedPreferences.edit()
            editor.remove("pref_key_user_settings").apply()
        }
        PreferenceManager.setDefaultValues(this, R.xml.application_user_settings, overwrite)
    }

    private fun clearJSONPersistence() {
        // Clear specific persistent collections from shared prefs
        val editor = defaultSharedPreferences.edit()

        editor
                .remove(JSONPersistence.SharedPrefsKeys.KEY_ALARMS_ARRAY)
                .remove(JSONPersistence.SharedPrefsKeys.KEY_MEDIA_ITEMS_ARRAY)
                .remove(JSONPersistence.SharedPrefsKeys.KEY_USER_CONTACTS_NUMBER_NAME_PAIRS_MAP)
                .remove(JSONPersistence.SharedPrefsKeys.KEY_USER_FRIENDS_ARRAY)
                .remove(JSONPersistence.SharedPrefsKeys.KEY_USER_INVITABLE_CONTACTS_ARRAY)
                .apply()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE).any {
            serviceClass.name == it.service.className
        }
    }

    fun requestPermissionIgnoreBatteryOptimization(context: Context): Boolean {
        if (!sharedPreferences.getBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, false)
                && (Build.BRAND.toLowerCase().contains("huawei")
                || Build.BRAND.toLowerCase().contains("sony")
                || Build.BRAND.toLowerCase().contains("xiaomi")
                || Build.BRAND.toLowerCase().contains("samsung")
                || Build.BRAND.toLowerCase().contains("zte"))) {
            Log.d("Brand: ", Build.BRAND)

            // Set instructions and settings intent
            var settingsNavigationString: String
            val intent = Intent()
            when {
                Build.BRAND.toLowerCase().contains("huawei") -> {
                    settingsNavigationString = ""
                    intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                    if (!isIntentCallable(intent)) {
                        settingsNavigationString = "Try: Go to 'Battery manager'>'Protected apps'"
                        intent.component = null
                        intent.action = android.provider.Settings.ACTION_SETTINGS
                    }

                    //Build content string
                    val dialogContent = (resources.getString(R.string.dialog_background_settings_1)
                            + Build.BRAND + resources.getString(R.string.dialog_background_settings_2)
                            + settingsNavigationString)

                    MaterialDialog.Builder(context)
                            .theme(Theme.LIGHT)
                            .content(dialogContent)
                            .positiveText(R.string.take_to_settings)
                            .negativeText(R.string.later)
                            .onPositive { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, true)
                                editor.apply()
                                startActivityForResult(intent, 0)
                            }
                            .onNegative { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, false)
                                editor.apply()
                                startHomeActivity()
                            }
                            .canceledOnTouchOutside(false)
                            .show()

                    return true
                }
                Build.BRAND.toLowerCase().contains("sony") -> {
                    settingsNavigationString = "Try: Go to 'Battery'>Settings menu>'Battery optimization'>Select Rooster app checkbox"

                    //Build content string
                    val dialogContent = (resources.getString(R.string.dialog_background_settings_sony_1)
                            + Build.BRAND + resources.getString(R.string.dialog_background_settings_sony_2))

                    MaterialDialog.Builder(context)
                            .theme(Theme.LIGHT)
                            .content(dialogContent)
                            .positiveText("Got it")
                            .onPositive { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, true)
                                editor.apply()
                            }
                            .canceledOnTouchOutside(false)
                            .show()

                    return true
                }
                Build.BRAND.toLowerCase().contains("xiaomi") -> {
                    intent.action = android.provider.Settings.ACTION_SETTINGS

                    //Build content string
                    val dialogContent = (resources.getString(R.string.dialog_background_settings_xiaomi_1)
                            + Build.BRAND + resources.getString(R.string.dialog_background_settings_xiaomi_2))

                    MaterialDialog.Builder(context)
                            .theme(Theme.LIGHT)
                            .content(dialogContent)
                            .positiveText(R.string.take_to_settings)
                            .negativeText(R.string.later)
                            .onPositive { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, true)
                                editor.apply()
                                startActivityForResult(intent, 0)
                            }
                            .onNegative { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, false)
                                editor.apply()
                                startHomeActivity()
                            }
                            .canceledOnTouchOutside(false)
                            .show()

                    return true
                }
                Build.BRAND.toLowerCase().contains("zte") -> {
                    intent.action = android.provider.Settings.ACTION_SETTINGS

                    //Build content string
                    val dialogContent = (resources.getString(R.string.dialog_background_settings_zte_1)
                            + Build.BRAND + resources.getString(R.string.dialog_background_settings_zte_2))

                    MaterialDialog.Builder(context)
                            .theme(Theme.LIGHT)
                            .content(dialogContent)
                            .positiveText(R.string.take_to_settings)
                            .negativeText(R.string.later)
                            .onPositive { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, true)
                                editor.apply()
                                startActivityForResult(intent, 0)
                            }
                            .onNegative { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, false)
                                editor.apply()
                                startHomeActivity()
                            }
                            .canceledOnTouchOutside(false)
                            .show()

                    return true
                }
                Build.BRAND.toLowerCase().contains("samsung") && RoosterUtils.hasNougat() -> {
                    settingsNavigationString = ""
                    intent.component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")

                    if (!isIntentCallable(intent)) {
                        intent.action = Intent.ACTION_MAIN
                        intent.setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                        intent.addCategory(Intent.CATEGORY_LAUNCHER)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if(!isIntentCallable(intent)) {
                        settingsNavigationString = "Try: Go to 'Device maintenance'>'Battery'>'Unmonitored apps'"
                        intent.component = null
                        intent.action = android.provider.Settings.ACTION_SETTINGS
                    }

                    //Build content string
                    val dialogContent = (resources.getString(R.string.dialog_background_settings_1)
                            + Build.BRAND + resources.getString(R.string.dialog_background_settings_2)
                            + settingsNavigationString)

                    MaterialDialog.Builder(context)
                            .theme(Theme.LIGHT)
                            .content(dialogContent)
                            .positiveText(R.string.take_to_settings)
                            .negativeText(R.string.later)
                            .onPositive { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, true)
                                editor.apply()
                                startActivityForResult(intent, 0)
                            }
                            .onNegative { _, _ ->
                                val editor = sharedPreferences.edit()
                                editor.putBoolean(PrefsKey.PERMISSIONS_DIALOG_OPTIMIZATION.name, false)
                                editor.apply()
                                startHomeActivity()
                            }
                            .canceledOnTouchOutside(false)
                            .show()

                    return true
                }
            }
        }

        return false
    }

    private fun isIntentCallable(intent: Intent): Boolean {
        val list = packageManager.queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY)
        return list.size > 0
    }

    fun setupToolbar(toolbarTitle: TextView?, title: String?): Toolbar? {
        return try {
            val toolbar = findViewById<Toolbar>(R.id.toolbar)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            if (title != null) toolbarTitle?.text = title
            toolbar
        } catch (e: NullPointerException) {
            e.printStackTrace()
            null
        }
    }

    @Optional
    @OnClick(R.id.home_my_alarms)
    fun manageMyAlarm() {
        val roosterCount = audioTableManager.countUnheardSocialAudioFiles()
        if (this is MyAlarmsFragmentActivity && roosterCount > 0 && deviceAlarmTableManager.nextPendingSocialAlarm == null) {
            showAlarmSocialRoostersExplainer(this, null, roosterCount)
        } else startHomeActivity()
    }

    @Optional
    @OnClick(R.id.home_record_audio)
    open fun recordNewAudio() {
        if (!checkInternetConnection()) return
        startActivity(Intent(this, NewAudioRecordActivity::class.java))
    }

    @Optional
    @OnClick(R.id.home_discover)
    fun onClickDiscover() {
        startActivity(Intent(this, DiscoverFragmentActivity::class.java))
    }

    @Optional
    @OnClick(R.id.home_friends)
    open fun manageFriends() {
        startActivity(Intent(this, FriendsFragmentActivity::class.java))
    }

    @Optional
    @OnClick(R.id.home_my_uploads)
    open fun manageUploads() {
        startActivity(Intent(this, MessageStatusFragmentActivity::class.java))
    }

    fun updateRoosterNotification() {
        setButtonBarNotification(R.id.notification_roosters, false)
        val roosterCount = audioTableManager.countUnheardSocialAudioFiles()

        if (roosterCount > 0) {
            val deviceAlarm = deviceAlarmTableManager.nextPendingSocialAlarm

            if (deviceAlarm == null) {
                (this as? MyAlarmsFragmentActivity)?.clearRoosterNotificationFlags()
                setButtonBarNotification(R.id.notification_roosters, true)
            } else {
                (this as? MyAlarmsFragmentActivity)?.allocateRoosterNotificationFlags(deviceAlarm.setId, roosterCount)
            }
        }

        // Broadcast receiver to receive UI updates
        roosterNotificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                setButtonBarNotification(R.id.notification_roosters, false)

                if (audioTableManager.countUnheardSocialAudioFiles() > 0) {
                    deviceAlarmTableManager.nextPendingSocialAlarm ?:
                            setButtonBarNotification(R.id.notification_roosters, true)
                }
            }
        }
        registerReceiver(roosterNotificationReceiver,
                IntentFilter(Action.ROOSTER_NOTIFICATION.name))
    }

    fun updateRequestNotification() {
        // Flag check for UI changes on load, broadcastreceiver for changes while activity running
        // If notifications waiting, display new friend request notification
        if (BaseApplication.getNotificationFlag(Flag.FRIEND_REQUESTS.name) > 0)
            setButtonBarNotification(R.id.notification_friends, true)

        // Broadcast receiver to receive UI updates
        requestNotificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    when (intent.action) {
                        Action.REQUEST_NOTIFICATION.name -> setButtonBarNotification(R.id.notification_friends, true)
                        else -> {}
                    }
                } catch (e: RuntimeException) {
                    e.printStackTrace()
                }
            }
        }
        registerReceiver(requestNotificationReceiver, IntentFilter(Action.REQUEST_NOTIFICATION.name))
    }

    override fun onDestroy() {
        roosterNotificationReceiver?.let {
            unregisterReceiver(it)
            roosterNotificationReceiver = null
        }
        requestNotificationReceiver?.let {
            unregisterReceiver(it)
            requestNotificationReceiver = null
        }
        foregroundReceiver?.let {
            unregisterReceiver(it)
            foregroundReceiver = null
        }

        super.onDestroy()
    }

    fun setButtonBarNotification(notificationId: Int, visible: Boolean) {
        val buttonBarLayout = findViewById<LinearLayout>(R.id.button_bar)
        val buttonBarNotification = buttonBarLayout?.findViewById<ImageView>(notificationId)

        if (visible) buttonBarNotification?.visibility = View.VISIBLE
        else buttonBarNotification?.visibility = View.GONE
    }

    fun setButtonBarSelection(): Boolean {
        when {
            this is MyAlarmsFragmentActivity -> findViewById<View>(R.id.home_my_alarms)?.isSelected = true
            this is MessageStatusFragmentActivity -> findViewById<View>(R.id.home_my_uploads)?.isSelected = true
            this is NewAudioRecordActivity -> findViewById<View>(R.id.home_record_audio)?.isSelected = true
            this is DiscoverFragmentActivity -> findViewById<View>(R.id.home_discover)?.isSelected = true
            this is FriendsFragmentActivity -> findViewById<View>(R.id.home_friends)?.isSelected = true
            else -> return false
        }
        return true
    }

    companion object {
        var mCurrentUser: User? = null

        fun setBadge(context: Context, count: Int) {
            val launcherClassName = getLauncherClassName(context) ?: return
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
            intent.putExtra("badge_count", count)
            intent.putExtra("badge_count_package_name", context.packageName)
            intent.putExtra("badge_count_class_name", launcherClassName)
            context.sendBroadcast(intent)
        }

        private fun getLauncherClassName(context: Context): String? {
            val pm = context.packageManager

            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)

            pm.queryIntentActivities(intent, 0).forEach {
                val pkgName = it.activityInfo.applicationInfo.packageName
                if (pkgName.equals(context.packageName, ignoreCase = true)) {
                    return it.activityInfo.name
                }
            }
            return null
        }

        fun showAlarmSocialRoostersExplainer(context: Context, alarm: Alarm?, count: Int?) {
            if (context is MyAlarmsFragmentActivity) {
                // If accessed from button bar
                val unseenRoosters = alarm?.unseen_roosters ?: count
                val dialogText = when (unseenRoosters) {
                    0 -> "Social roosters are voice notes from your friends that wake you up."
                    1 -> "You have received " + unseenRoosters.toString() + " rooster from a friend to wake you up."
                    else -> "You have received " + unseenRoosters.toString() + " roosters from friends to wake you up."
                }

                MaterialDialog.Builder(context)
                        .theme(Theme.LIGHT)
                        .content(dialogText)
                        .show()
            }
        }
    }
}
