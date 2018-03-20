/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast

import com.roostermornings.android.BaseApplication
import com.roostermornings.android.BuildConfig
import com.roostermornings.android.R
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.adapter.NewAudioFriendsListAdapter
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.service.UploadService
import com.roostermornings.android.domain.database.User
import com.roostermornings.android.domain.database.Users

import java.util.ArrayList
import java.util.Collections

import javax.inject.Inject

import butterknife.BindView
import butterknife.OnClick
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Extra
import com.roostermornings.android.util.*
import retrofit.Callback
import retrofit.Response
import retrofit.Retrofit

class NewAudioFriendsActivity : BaseActivity() {
    private var mFriends = ArrayList<User>()
    private var mFriendsSelected = ArrayList<User>()
    private var mAdapter: RecyclerView.Adapter<*>? = null
    private var localFileString: String? = ""
    private var firebaseIdToken: String? = ""

    private var extras: Bundle? = null

    private var mUploadService: UploadService? = null
    private var mBound: Boolean = false

    @BindView(R.id.progressBar)
    lateinit var progressBar: ProgressBar

    @BindView(R.id.new_audio_friendsListView)
    lateinit var mRecyclerView: RecyclerView

    @BindView(R.id.select_all_button)
    lateinit var selectAllButton: Button

    @Inject lateinit var myContactsController: MyContactsController
    @Inject lateinit var connectivity: ConnectivityUtils

    private val mUploadServiceConnection = object : ServiceConnection {
        // Called when the connection with the service is established
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // Because we have bound to an explicit
            // service that is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            val binder = service as UploadService.LocalBinder
            mUploadService = binder.service
            mBound = true

            // Start upload service thread task
            mUploadService?.processAudioFile(firebaseIdToken, localFileString, mFriendsSelected)
        }

        // Called when the connection with the service disconnects unexpectedly
        override fun onServiceDisconnected(className: ComponentName) {
            Log.e(TAG, "onServiceDisconnected")
            mBound = false
        }
    }

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(R.layout.activity_new_audio_friends)
        BaseApplication.roosterApplicationComponent.inject(this)

        setupToolbar(null, null)

        selectAllButton.isSelected = false

        firebaseUser?.let {
            firebaseUser?.getIdToken(true)
                    ?.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            firebaseIdToken = task.result.token
                            retrieveMyFriends()
                        } else {
                            Toaster.makeToast(this, "Please check internet connection and try again.", Toast.LENGTH_SHORT)
                        }
                    }
        } ?:Toaster.makeToast(this,
                "Please check internet connection and try again.",
                Toast.LENGTH_SHORT)

        //Load intent extras
        extras = intent.extras
        localFileString = extras?.getString(Extra.LOCAL_FILE_STRING.name)

        connectivity.isActive(makeToast = true) { active ->
            if(active) {
                mAdapter = NewAudioFriendsListAdapter(mFriends)
                mRecyclerView.layoutManager = LinearLayoutManager(this@NewAudioFriendsActivity)
                mRecyclerView.adapter = mAdapter

                if (extras?.containsKey(Extra.FRIENDS_LIST.name) == true) {

                    @Suppress("UNCHECKED_CAST")
                    val tempUsers = extras?.getSerializable(Extra.FRIENDS_LIST.name) as ArrayList<User>
                    tempUsers.forEach { it.selected = true }

                    mFriends.clear()
                    mFriends.addAll(tempUsers)
                    mFriends.firstOrNull { it.selected }?.let {
                        onSendMenuItemClick()
                    }
                }
            } else {
                startHomeActivity()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        //Display notifications
        updateRoosterNotification()
        updateRequestNotification()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_new_audio, menu)
        if (statusCode == 200) {
            menu.findItem(R.id.action_send).isVisible = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when(item.itemId) {
            R.id.action_send -> {
                onSendMenuItemClick()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (mBound) unbindService(mUploadServiceConnection)
    }

    @OnClick(R.id.select_all_button)
    fun onClickSelectAll() {
        selectAllButton.isSelected = !selectAllButton.isSelected
        mFriends.forEach { it.selected = selectAllButton.isSelected }
        mAdapter?.notifyDataSetChanged()
    }

    private fun onSendMenuItemClick() {
        if (!checkInternetConnection()) return
        mFriends.filterTo(mFriendsSelected, {it.selected})

        if (mFriendsSelected.isEmpty()) {
            Toast.makeText(this@NewAudioFriendsActivity, R.string.new_audio_at_least_one_friend, Toast.LENGTH_LONG).show()
            return
        }

        //Bind to upload service to allow asynchronous management of Rooster upload
        val intent = Intent(this, UploadService::class.java)
        startService(intent)
        //0 indicates that service should not be restarted
        bindService(intent, mUploadServiceConnection, 0)

        //Switch to message status activity, set action to change to relevant tab
        val roostersSentIntent = Intent(this, MessageStatusFragmentActivity::class.java)
        roostersSentIntent.action = Action.FROM_ROOSTER_SEND.name
        startActivity(roostersSentIntent)

        sendBroadcast(Intent(Action.FINISH_AUDIO_RECORD_ACTIVITY.name))
    }

    private fun retrieveMyFriends() {
        if (!checkInternetConnection()) return

        if (firebaseUser == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "User not authenticated on FB!")
            return
        }

        if (firebaseIdToken.isNullOrBlank()) {
            Toaster.makeToast(applicationContext, "Loading friends failed, please try again.", Toast.LENGTH_LONG).checkTastyToast()
            return
        }

        val call = nodeApiService.retrieveUserFriends(firebaseIdToken!!)
        call.enqueue(object : Callback<Users> {
            override fun onResponse(response: Response<Users>,
                                    retrofit: Retrofit) {

                statusCode = response.code()
                val apiResponse = response.body()

                if (statusCode == 200) {

                    progressBar.visibility = View.GONE
                    mRecyclerView.visibility = View.VISIBLE

                    mFriends.clear()
                    apiResponse.users.filterNotNullTo(mFriends)

                    //For each user, check if name appears in contacts, and allocate name
                    if (ContextCompat.checkSelfPermission(applicationContext,
                            android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                        //Get a map of contact numbers to names
                        val numberNamePairs = myContactsController.numberNamePairs

                        mFriends.forEach {
                            if (numberNamePairs.containsKey(it.cell_number)) {
                                it.user_name = numberNamePairs[it.cell_number]?:it.user_name
                            }
                        }
                    }

                    sortNames(mFriends)
                    moveSelfToStart()

                    mAdapter?.notifyDataSetChanged()

                    //Recreate menu to show send item
                    invalidateOptionsMenu()
                }
            }

            override fun onFailure(t: Throwable) {
                Log.i(TAG, if (StrUtils.notNullOrEmpty(t.localizedMessage)) t.localizedMessage else " ")
                Toaster.makeToast(applicationContext, "Loading friends failed, please try again.", Toast.LENGTH_LONG).checkTastyToast()
                startHomeActivity()
            }
        })
    }

    private fun moveSelfToStart() {
        // Move self to beginning of list
        mFriends.firstOrNull { it.uid == firebaseUser?.uid }?.let { self ->
            self.user_name = "Me"
            mFriends.remove(self)
            mFriends.add(0, self)
        }
    }

    fun sortNames(mUsers: ArrayList<User>) {
        //Take arraylist and sort alphabetically
        Collections.sort(mUsers) { lhs, rhs -> lhs.user_name.compareTo(rhs.user_name) }
    }

    companion object {
        private val TAG: String = NewAudioFriendsActivity::class.java.simpleName
        private var statusCode = 0
    }
}
