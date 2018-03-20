/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.FloatingActionButton
import android.support.design.widget.NavigationView
import android.support.graphics.drawable.VectorDrawableCompat
import android.support.v4.content.ContextCompat
import android.support.v4.content.res.ResourcesCompat
import android.support.v4.view.GravityCompat
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import butterknife.BindView
import butterknife.OnClick
import com.afollestad.materialdialogs.MaterialDialog
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.adapter.MyAlarmsListAdapter
import com.roostermornings.android.adapter_data.RoosterAlarmManager
import com.roostermornings.android.custom_ui.SquareFrameLayout
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.Alarm
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Extra
import com.roostermornings.android.onboarding.CustomCommandInterface
import com.roostermornings.android.onboarding.InterfaceCommands
import com.roostermornings.android.onboarding.ProfileCreationFragment
import com.roostermornings.android.snackbar.SnackbarManager
import com.roostermornings.android.sync.DownloadSyncAdapter
import com.roostermornings.android.util.ConnectivityUtils
import com.roostermornings.android.util.Constants.AUTHORITY
import com.roostermornings.android.util.DetailsUtils
import com.roostermornings.android.util.JSONPersistence
import com.roostermornings.android.widgets.AlarmToggleWidget
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import kotlinx.android.synthetic.main.activity_navigation_drawer.*
import kotlinx.android.synthetic.main.custom_toolbar.*
import kotlinx.android.synthetic.main.nav_header_navigation_drawer.view.*
import me.grantland.widget.AutofitTextView
import java.util.*
import javax.inject.Inject

class MyAlarmsFragmentActivity : BaseActivity(), CustomCommandInterface, NavigationView.OnNavigationItemSelectedListener {

    @BindView(R.id.my_alarms_coordinator_layout)
    lateinit var myAlarmsCoordinatorLayout: CoordinatorLayout
    @BindView(R.id.home_alarmsListView)
    lateinit var mRecyclerView: RecyclerView
    @BindView(R.id.toolbar_title)
    lateinit var toolbarTitle: TextView
    @BindView(R.id.add_alarm)
    lateinit var buttonAddAlarm: FloatingActionButton

    @BindView(R.id.add_alarm_filler)
    lateinit var addAlarmFiller: SquareFrameLayout
    @BindView(R.id.add_alarm_filler_text)
    lateinit var addAlarmFillerText: AutofitTextView

    @BindView(R.id.swiperefresh)
    lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private val mAlarms = ArrayList<Alarm>()
    private var mAdapter: RecyclerView.Adapter<*>? = null

    private var receiver: BroadcastReceiver? = null

    private var toolbar: Toolbar? = null

    private var snackbarManager: SnackbarManager? = null

    private var profileCreationFragment: ProfileCreationFragment? = null

    @Inject
    lateinit var roosterAlarmManager: RoosterAlarmManager
    @Inject
    lateinit var jsonPersistence: JSONPersistence
    @Inject
    lateinit var connectivityUtils: ConnectivityUtils

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onStart() {
        super.onStart()
        //Display notifications
        updateRoosterNotification()
        updateRequestNotification()
    }

    override fun onPause() {
        super.onPause()
        // Persist alarms for seamless loading
        jsonPersistence.alarms = mAlarms
        // Remove Realm listeners, and close Realm
        snackbarManager?.destroy()
        // Update app widget
        AlarmToggleWidget.sendUpdateBroadcast(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(R.layout.activity_my_alarms)
        BaseApplication.roosterApplicationComponent.inject(this)

        /** To be run only if debuggable, for safe testing */
        if (DetailsUtils.isDebuggable(this)) {
            //FirstMileManager firstMileManager = new FirstMileManager();
            //firstMileManager.createShowcase(this, new ViewTarget(buttonAddAlarm.getId(), this), 1);

//            @TargetApi(23)
//            if(RoosterUtils.hasM()) {
//                // https://plus.google.com/+H%C3%A9ctorJ%C3%BAdez/posts/asy8WoN485U
//                val batteryOptimizationIntent = Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
//                startActivityForResult(batteryOptimizationIntent, 0)
//            }
        }

        // Final context to be used in threads
        val context = this

        snackbarManager = SnackbarManager(this, myAlarmsCoordinatorLayout)

        // Download any social or channel audio files
        ContentResolver.requestSync(mAccount, AUTHORITY, DownloadSyncAdapter.forceBundle)

        /*
        * Sets up a SwipeRefreshLayout.OnRefreshListener that is invoked when the user
        * performs a swipe-to-refresh gesture.
        */
        swipeRefreshLayout.setOnRefreshListener {
            // This method performs the actual data-refresh operation.
            // The method calls setRefreshing(false) when it's finished.
            roosterAlarmManager.fetchAlarms(mAlarms)
            refreshDownloadIndicator()
        }

        // UI setup thread
        object : Thread() {
            override fun run() {
                // Set highlighting of button bar
                setButtonBarSelection()
                // Animate FAB with pulse
                buttonAddAlarm.animation = AnimationUtils.loadAnimation(context, R.anim.pulse)
                // Set toolbar title
                toolbar = setupToolbar(toolbarTitle, getString(R.string.my_alarms))
                // Set download indicator
                refreshDownloadIndicator()

                // Set up navigation drawer
                val toggle = ActionBarDrawerToggle(
                        context, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
                drawer_layout.addDrawerListener(toggle)
                toggle.syncState()
                nav_view.setNavigationItemSelectedListener(context)
                refreshDrawer()

                // Load navigation drawer header image
                mCurrentUser?.profile_pic?.takeIf { it.isNotBlank() }?.let {
                    Picasso.with(context).load(it)
                            .resize(400, 400)
                            .centerCrop()
                            .into(object : Target {
                                override fun onPrepareLoad(placeHolderDrawable: Drawable?) {}

                                override fun onBitmapFailed(errorDrawable: Drawable?) {}

                                override fun onBitmapLoaded(bitmap: Bitmap?, from: Picasso.LoadedFrom?) {
                                    nav_view.getHeaderView(0)?.background = BitmapDrawable(resources, bitmap)
                                }
                            })
                }

                // Set up adapter for monitoring alarm objects
                mAdapter = MyAlarmsListAdapter(mAlarms, this@MyAlarmsFragmentActivity)
                // Use a linear layout manager
                val mLayoutManager = LinearLayoutManager(context)
                mRecyclerView.layoutManager = mLayoutManager
                mRecyclerView.adapter = mAdapter

                // Check for, and load, persisted data
                if (!jsonPersistence.alarms.isEmpty()) {
                    mAlarms.addAll(jsonPersistence.alarms)
                    mAdapter?.notifyDataSetChanged()
                } else if (checkInternetConnection()) {
                    if (!swipeRefreshLayout.isRefreshing) swipeRefreshLayout.isRefreshing = true
                }
            }
        }.run()

        // Process intent bundle thread
        object : Thread() {
            override fun run() {
                val extras = intent.extras
                if (extras != null && extras.containsKey("message")) {
                    val fcmMessage = extras.getString("message", "")
                    if (fcmMessage.isNotEmpty()) {

                        MaterialDialog.Builder(applicationContext)
                                .title("Hey, we thought you should know!")
                                .content(fcmMessage)
                                .positiveText(R.string.ok)
                                .negativeText("")
                                .show()
                    }
                }

                // Clear snooze if action
                if (Action.CANCEL_SNOOZE.name == intent.action) {
                    if (extras?.getString(Extra.ALARM_SET_ID.name)?.isNotBlank() == true) {
                        deviceAlarmController.snoozeAlarm(extras.getString(Extra.ALARM_SET_ID.name), true)
                    }
                }
            }
        }.run()

        RoosterAlarmManager.onFlagAlarmManagerDataListener = object : RoosterAlarmManager.Companion.OnFlagAlarmManagerDataListener {
            override fun onAlarmDataChanged(freshAlarms: ArrayList<Alarm>) {
                // Check if persisted data is fresh
                if (mAlarms !== freshAlarms) {
                    mAlarms.clear()
                    mAlarms.addAll(freshAlarms)
                }
            }

            override fun onSyncFinished() {
                // Sort alarms according to time
                sortAlarms(mAlarms)
                toggleAlarmFiller()

                // Recreate all enabled alarms as failsafe
                deviceAlarmController.rebootAlarms()
                // Case: local has an alarm that Firebase doesn't Result: delete local alarm
                deviceAlarmController.syncAlarmSetGlobal(mAlarms)

                // Load content and stop refresh indicator
                mAdapter?.notifyDataSetChanged()
                swipeRefreshLayout.isRefreshing = false
                // Configure rooster notification indicator
                updateRoosterNotification()
            }
        }
        //Refresh alarms list from background thread
        roosterAlarmManager.fetchAlarms(mAlarms)
    }

    private fun refreshDownloadIndicator() {
        if (!deviceAlarmTableManager.isAlarmTableEmpty) {
            mMenu?.getItem(0)?.isVisible = true

            /* If there is no pending alarm, or pending alarm is synced,
            indicate with icon and clear no-internet snackbar*/
            if (deviceAlarmTableManager.nextPendingAlarm == null || deviceAlarmTableManager.isNextPendingAlarmSynced) {
                mMenu?.getItem(0)?.icon =
                        ContextCompat.getDrawable(this, R.drawable.ic_cloud_done_white_24dp)

                // If "no internet" high priority snackbar is showing, dismiss it, we're synced
                if (snackbarManager?.previousState === SnackbarManager.State.NO_INTERNET) {
                    snackbarManager?.dismissSnackbar()
                }
            } else {
                // Check the current connectivity status, display appropriate icon and snackbar
                connectivityUtils.isActive { active ->
                    if (active) {
                        animateRefreshDownloadIndicator()
                    } else {
                        mMenu?.getItem(0)?.icon =
                                ContextCompat.getDrawable(this, R.drawable.ic_cloud_off_white_24dp)
                        snackbarManager?.generateNoInternetConnection()
                    }
                }
            }

            // Listen for channel download complete notices from sync adapter
            DownloadSyncAdapter.onChannelDownloadListener = object : DownloadSyncAdapter.OnChannelDownloadListener {
                override fun onChannelDownloadStarted(channelId: String) {
                    // When download starts, indicate this to user
                    if (!deviceAlarmTableManager.isNextPendingAlarmSynced) {
                        animateRefreshDownloadIndicator()
                    }
                }

                override fun onChannelDownloadComplete(valid: Boolean, channelId: String) {
                    // When download completes, indicate this to user
                    if (deviceAlarmTableManager.isNextPendingAlarmSynced) {
                        mMenu?.getItem(0)?.icon =
                                ContextCompat.getDrawable(this@MyAlarmsFragmentActivity, R.drawable.ic_cloud_done_white_24dp)
                        if (snackbarManager?.previousState === SnackbarManager.State.SYNCING)
                            snackbarManager?.generateFinished()
                    }
                }
            }
        } else {
            // If there are no alarms, clear navigation icon
            mMenu?.getItem(0)?.isVisible = false
        }

        //Download any social or channel audio files
        ContentResolver.requestSync(mAccount, AUTHORITY, DownloadSyncAdapter.forceBundle)
    }

    private fun animateRefreshDownloadIndicator() {
        // Get vector drawable, return if null
        val drawableDownloadIndicator = ResourcesCompat.getDrawable(
                resources,
                R.drawable.ic_cloud_download_white_24dp,
                null) ?: return

        // Get menu item icon
        mMenu?.getItem(0)?.icon = drawableDownloadIndicator

        // Animate alpha
        val animator = ObjectAnimator.ofPropertyValuesHolder(
                drawableDownloadIndicator,
                PropertyValuesHolder.ofInt("alpha", 255, 120))

        animator.duration = 1000
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.start()
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else moveTaskToBack(true)
    }

    private fun sortAlarms(alarms: ArrayList<Alarm>) {
        // Take arraylist and sort by date
        Collections.sort(alarms) { lhs, rhs -> lhs.millis.compareTo(rhs.millis) }
        mAdapter?.notifyDataSetChanged()
    }

    fun clearRoosterNotificationFlags() {
        for (alarm in mAlarms) alarm.unseen_roosters = 0
        mAdapter?.notifyDataSetChanged()
    }

    fun allocateRoosterNotificationFlags(alarmId: String, roosterCount: Int) {
        mAlarms.forEach { alarm ->
            alarm.unseen_roosters = 0
            if (alarm.uid == alarmId) {
                alarm.unseen_roosters = roosterCount
            }
        }
        mAdapter?.notifyDataSetChanged()
    }

    private fun refreshDrawer() {
        if (authManager.isUserSignedIn()) {
            // Change menu entry to "Sign out"
            nav_view.menu?.findItem(R.id.nav_signout)?.setTitle(R.string.action_signout)
        } else {
            // Change menu entry to "Sign in"
            nav_view.menu?.findItem(R.id.nav_signout)?.setTitle(R.string.action_signin)
        }

        nav_view.getHeaderView(0)?.user_name?.text =
                if (firebaseUser?.displayName?.isBlank() == true)
                    "Anonymous"
                else firebaseUser?.displayName
        nav_view.getHeaderView(0)?.user_email?.text = firebaseUser?.email
    }

    override fun onDestroy() {
        if (receiver != null) {
            unregisterReceiver(receiver)
            receiver = null
        }
        // Close Realm object
        realmAlarmFailureLog.closeRealm()
        super.onDestroy()
    }

    private var mMenu: Menu? = null
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        mMenu = menu
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
        //R.id.action_settings -> return true
            R.id.action_sync -> {
                // When sync icon clicked, try refresh content
                refreshDownloadIndicator()
                //Download any social or channel audio files
                ContentResolver.requestSync(
                        mAccount,
                        AUTHORITY,
                        DownloadSyncAdapter.forceBundle)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_profile -> {
                startActivity(Intent(this, ProfileActivity::class.java))
            }
            R.id.nav_faqs -> {
                if (checkInternetConnection())
                    startActivity(Intent(this, FAQActivity::class.java))
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.nav_signout -> {
                if (authManager.isUserSignedIn()) signOut()
                else {
                    appbar.visibility = View.GONE

                    profileCreationFragment = ProfileCreationFragment.newInstance(
                            ProfileCreationFragment.Companion.Source.HOME_PAGE)
                    supportFragmentManager.beginTransaction()
                            .replace(R.id.fillerContainer, profileCreationFragment)
                            .commit()
                }
            }
            R.id.nav_share -> {
                lifeCycle.shareApp()
            }
            R.id.nav_send -> {
                lifeCycle.sendFeedback(mCurrentUser?.user_name ?: "Anonymous")
            }
            R.id.nav_rate -> {
                lifeCycle.requestAppRating()
            }
        }

        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onCustomCommand(command: InterfaceCommands.Companion.Command) {
        when (command) {
        // When sign-in complete, remove fragment and refresh nav drawer
            InterfaceCommands.Companion.Command.PROCEED -> {
                appbar.visibility = View.VISIBLE

                profileCreationFragment?.let {
                    supportFragmentManager
                            .beginTransaction()
                            .remove(it)
                            .commit()
                }

                refreshDrawer()
            }
            else -> {
            }
        }
    }

    fun toggleAlarmSetEnable(alarm: Alarm, enabled: Boolean) {
        //Toggle alarm set enabled
        deviceAlarmController.setSetEnabled(alarm.getUid(), enabled, true)
        //Set adapter arraylist item to enabled
        val alarmIndex = mAlarms.indexOf(alarm)
        if (alarmIndex > -1) mAlarms[alarmIndex].isEnabled = enabled
        //Update notification of pending social roosters
        updateRoosterNotification()
        toggleAlarmFiller()
        refreshDownloadIndicator()
    }

    fun deleteAlarm(alarmId: String) {
        try {
            //Remove alarm *set* from local SQL database using retrieved Uid from firebase && Remove alarm from firebase
            deviceAlarmController.deleteAlarmSetGlobal(alarmId)
            //Update notification of pending social roosters
            updateRoosterNotification()
            toggleAlarmFiller()
            refreshDownloadIndicator()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }

    }

    private fun toggleAlarmFiller() {
        if (mAlarms.isEmpty()) {
            // For pre-Lollipop devices use VectorDrawableCompat to get your vector from resources
            val vectorDrawable = VectorDrawableCompat.create(this.resources, R.drawable.ic_alarm_add_white_24px, null)
            addAlarmFiller.background = vectorDrawable
            addAlarmFiller.visibility = View.VISIBLE
            addAlarmFillerText.visibility = View.VISIBLE
        } else {
            addAlarmFiller.visibility = View.GONE
            addAlarmFillerText.visibility = View.GONE
        }
    }

    @OnClick(R.id.add_alarm,
            R.id.add_alarm_filler,
            R.id.add_alarm_filler_text)
    fun onClickAddAlarm() {
        startActivity(Intent(this, NewAlarmFragmentActivity::class.java))
    }

    fun editAlarm(alarmId: String) {
        val intent = Intent(this, NewAlarmFragmentActivity::class.java)
        intent.putExtra(Extra.ALARM_SET_ID.name, alarmId)
        startActivity(intent)
    }
}
