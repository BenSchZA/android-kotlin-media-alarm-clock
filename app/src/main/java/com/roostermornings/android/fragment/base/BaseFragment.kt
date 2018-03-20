/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment.base

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import butterknife.ButterKnife
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.mobsandgeeks.saripaar.ValidationError
import com.mobsandgeeks.saripaar.Validator
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.apis.GoogleIHTTPClient
import com.roostermornings.android.apis.NodeIHTTPClient
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.SocialRooster
import com.roostermornings.android.domain.database.User
import com.roostermornings.android.domain.local.Contact
import com.roostermornings.android.domain.local.Friend
import com.roostermornings.android.sqlutil.DeviceAudioQueueItem
import java.util.*
import javax.inject.Inject

abstract class BaseFragment : Fragment(), Validator.ValidationListener {

    @Inject lateinit var AppContext: Context
    @Inject lateinit var baseApplication: BaseApplication

    private val baseActivityListener: BaseActivityListener by lazy { activity as BaseActivityListener }

    var firebaseUser: FirebaseUser? = null
    @Inject
    fun BaseActivity(firebaseUser: FirebaseUser?) {
        this.firebaseUser = firebaseUser
    }

    protected val databaseReference: DatabaseReference
        get() = FirebaseDatabase.getInstance().reference

    protected abstract fun inject(component: RoosterApplicationComponent)

    interface BaseActivityListener {
        val nodeApiService: NodeIHTTPClient
        val googleApiService: GoogleIHTTPClient
        fun onValidationSucceeded()
        fun onValidationFailed(errors: List<ValidationError>)
        fun checkInternetConnection(): Boolean
    }

    fun checkInternetConnection(): Boolean {
        return baseActivityListener.checkInternetConnection()
    }

    fun nodeApiService(): NodeIHTTPClient {
        return baseActivityListener.nodeApiService
    }

    fun googleApiService(): GoogleIHTTPClient {
        return baseActivityListener.googleApiService
    }

    override fun onValidationSucceeded() {
        baseActivityListener.onValidationSucceeded()
    }

    override fun onValidationFailed(errors: List<ValidationError>) {
        baseActivityListener.onValidationFailed(errors)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BaseApplication.roosterApplicationComponent.inject(this)

        if(activity !is BaseActivityListener) {
            throw RuntimeException(context.toString() + " must implement BaseActivityListener")
        }
    }

    protected fun initiate(inflater: LayoutInflater, resource: Int, root: ViewGroup?, attachToRoot: Boolean): View {
        val view = inflater.inflate(resource, root, attachToRoot)

        ButterKnife.bind(this, view)
        return view
    }

    fun sortNamesFriends(mUsers: ArrayList<Friend>) {
        //Take arraylist and sort alphabetically
        Collections.sort(mUsers) { lhs, rhs ->
            //If null, pretend equal
            if (lhs == null || rhs == null || lhs.user_name == null || rhs.user_name == null) 0
            else lhs.user_name.compareTo(rhs.user_name)
        }
    }

    fun sortNamesUsers(mUsers: ArrayList<User>) {
        //Take arraylist and sort alphabetically
        Collections.sort(mUsers) { lhs, rhs ->
            //If null, pretend equal
            if (lhs == null || rhs == null || lhs.user_name == null || rhs.user_name == null) 0
            else lhs.user_name.compareTo(rhs.user_name)
        }
    }

    fun sortNamesContacts(contacts: ArrayList<Contact>) {
        //Take arraylist and sort alphabetically
        Collections.sort(contacts) { lhs, rhs ->
            //If null, pretend equal
            if (lhs == null || rhs == null || lhs.name == null || rhs.name == null) 0
            else lhs.name.compareTo(rhs.name)
        }
    }

    fun sortSocialRoosters(socialRoosters: ArrayList<SocialRooster>) {
        //Take arraylist and sort by date
        Collections.sort(socialRoosters) { lhs, rhs ->
            //If null, pretend equal
            if (lhs == null || rhs == null || lhs.date_uploaded == null || rhs.date_uploaded == null) 0
            else rhs.date_uploaded.compareTo(lhs.date_uploaded)
        }
    }

    fun sortDeviceAudioQueueItems(socialRoosters: ArrayList<DeviceAudioQueueItem>) {
        //Take arraylist and sort by date
        Collections.sort(socialRoosters) { lhs, rhs ->
            //If null, pretend equal
            if (lhs == null || rhs == null || lhs.date_uploaded == null || rhs.date_uploaded == null) 0
            else rhs.date_uploaded.compareTo(lhs.date_uploaded)
        }
    }

    protected fun startHomeActivity() {
        val homeIntent = Intent(AppContext, MyAlarmsFragmentActivity::class.java)
        startActivity(homeIntent)
    }
}
