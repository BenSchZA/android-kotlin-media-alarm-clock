/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment.friends

import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import butterknife.OnClick
import com.facebook.FacebookSdk.getApplicationContext
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.FriendsFragmentActivity
import com.roostermornings.android.adapter.FriendsInviteListAdapter
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.User
import com.roostermornings.android.domain.local.Contact
import com.roostermornings.android.domain.local.Friend
import com.roostermornings.android.domain.local.LocalContacts
import com.roostermornings.android.domain.node.NodeUsers
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.keys.RequestCode
import com.roostermornings.android.util.JSONPersistence
import com.roostermornings.android.util.LifeCycle
import com.roostermornings.android.util.MyContactsController
import com.roostermornings.android.util.Toaster
import kotlinx.android.synthetic.main.fragment_friends_3.*
import retrofit.Callback
import retrofit.Response
import retrofit.Retrofit
import java.util.*
import javax.inject.Inject

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [FriendsInviteFragment3.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [FriendsInviteFragment3.newInstance] factory method to
 * create an instance of this fragment.
 */
class FriendsInviteFragment3 : BaseFragment() {

    private var mRecyclerViewElements = ArrayList<Any>()
    private var mAddableContacts = ArrayList<Friend>()
    private var mInvitableContacts = ArrayList<Contact>()

    private var mAddContactsAdapter: RecyclerView.Adapter<*>? = null

    private var addableHeader = ""
    private var invitableHeader = ""

    private var mListener: OnFragmentInteractionListener? = null

    private var processAddableBackground: ProcessAddableBackground? = ProcessAddableBackground()

    @Inject lateinit var jsonPersistence: JSONPersistence
    @Inject lateinit var myContactsController: MyContactsController
    @Inject lateinit var lifeCycle: LifeCycle

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inject(BaseApplication.roosterApplicationComponent)

        addableHeader = resources.getString(R.string.add_contacts)
        invitableHeader = resources.getString(R.string.invite_contacts)

        myContactsController = MyContactsController(AppContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment

        return initiate(inflater, R.layout.fragment_friends_3, container, false)
    }

    //NB: bind ButterKnife to activityContentView and then initialise UI elements
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mAddContactsAdapter = FriendsInviteListAdapter(mRecyclerViewElements)
        friendsAddListView.layoutManager = LinearLayoutManager(AppContext)
        friendsAddListView.adapter = mAddContactsAdapter

        mAddContactsAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                notifyAdapter()
            }
        })

        swiperefresh.isRefreshing = true
        /*
        * Sets up a SwipeRefreshLayout.OnRefreshListener that is invoked when the user
        * performs a swipe-to-refresh gesture.
        */
        swiperefresh.setOnRefreshListener {
            // This method performs the actual data-refresh operation.
            // The method calls setRefreshing(false) when it's finished.

            //If pull to refresh and permission has been denied, retry, else request permission as per normal
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity!!,
                    android.Manifest.permission.READ_CONTACTS)) {
                retrieveContactsPermissionRetry()
            } else {
                requestPermissionReadContacts()
            }
        }

        requestPermissionReadContacts()
    }

    override fun onDetach() {
        super.onDetach()
        mListener = null
    }

    fun searchRecyclerViewAdapter(query: String) {
        //Filter contacts by CharSequence
        //Get reference to list adapter to access getFilter method
        (mAddContactsAdapter as FriendsInviteListAdapter).refreshAll(mRecyclerViewElements)
        (mAddContactsAdapter as FriendsInviteListAdapter).filter.filter(query)
    }

    fun notifyAdapter() {
        setHeaderVisibility()

        (mAddContactsAdapter as FriendsInviteListAdapter).refreshAll(mRecyclerViewElements)
        mAddContactsAdapter?.notifyDataSetChanged()
    }

    private fun setHeaderVisibility() {
        //If list is empty, don't show header
        try {
            if (mRecyclerViewElements.contains(addableHeader)
                    && mRecyclerViewElements[mRecyclerViewElements.indexOf(addableHeader) + 1] !is Friend) {
                mRecyclerViewElements.remove(addableHeader)
            }
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
        }

        try {
            if (mRecyclerViewElements.contains(invitableHeader)
                    && mRecyclerViewElements[mRecyclerViewElements.indexOf(invitableHeader) + 1] !is Contact) {
                mRecyclerViewElements.remove(invitableHeader)
            }
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
        }

    }

    @OnClick(R.id.share_button)
    fun onShareButtonClicked() {
        lifeCycle.shareApp()
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     *
     *
     * See the Android Training lesson [Communicating with Other Fragments](http://developer.android.com/training/basics/fragments/communicating.html) for more information.
     */
    interface OnFragmentInteractionListener {
        // TODO: Update argument type and name
        fun onFragmentInteraction(uri: Uri)
    }

    override fun onResume() {
        super.onResume()
    }

    fun requestPermissionReadContacts() {
        //Clear explainer on entry, show if necessary i.e. permission previously denied
        displayRequestPermissionExplainer(false)

        val contactsPermission = ContextCompat.checkSelfPermission(AppContext,
                android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        UserMetrics.setPermission(
                UserMetrics.Permission.PERMISSION_CONTACTS,
                contactsPermission)

        if (!contactsPermission) {
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity!!,
                    android.Manifest.permission.READ_CONTACTS)) {

                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.

                displayRequestPermissionExplainer(true)
            } else {

                // No explanation needed, we can request the permission.

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.

                ActivityCompat.requestPermissions(activity!!,
                        arrayOf(android.Manifest.permission.READ_CONTACTS),
                        RequestCode.PERMISSIONS_READ_CONTACTS.ordinal)
            }
        } else {
            //If there is no internet connection, attempt to retrieve invitable contacts from persistence
            if (!jsonPersistence.invitableContacts.isEmpty() && !checkInternetConnection()) {
                mInvitableContacts = jsonPersistence.invitableContacts
                //Populate recycler activityContentView elements
                mRecyclerViewElements.add(invitableHeader)
                mRecyclerViewElements.addAll(mInvitableContacts)
                //Display results
                notifyAdapter()
            }

            executeNodeMyContactsTask()
        }
    }

    private fun executeNodeMyContactsTask() {
        if (!checkInternetConnection()) {
            swiperefresh.isRefreshing = false
        } else {
            if (!swiperefresh.isRefreshing) swiperefresh.isRefreshing = true
            firebaseUser?.getIdToken(true)
                    ?.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val idToken = task.result.token

                            when(processAddableBackground?.status) {
                                AsyncTask.Status.FINISHED -> {
                                    processAddableBackground = null
                                    processAddableBackground = ProcessAddableBackground()
                                    processAddableBackground?.execute(idToken)
                                }
                                AsyncTask.Status.PENDING -> processAddableBackground?.execute(idToken)
                                AsyncTask.Status.RUNNING -> swiperefresh.isRefreshing = false
                            }
                        } else {
                            // Handle error -> task.getException();
                            swiperefresh.isRefreshing = false
                        }
                    }
        }
    }

    private inner class ProcessAddableBackground : AsyncTask<String, Void, String>() {
        override fun onPreExecute() {
            mRecyclerViewElements.clear()
        }

        override fun doInBackground(vararg params: String): String? {
            val idToken = params[0]

            val call = nodeApiService().checkLocalContacts(LocalContacts(myContactsController.nodeNumberList, idToken))

            call.enqueue(object : Callback<NodeUsers> {
                override fun onResponse(response: Response<NodeUsers>,
                                        retrofit: Retrofit) {

                    val statusCode = response.code()
                    val apiResponse = response.body()

                    if (statusCode == 200) {

                        if (apiResponse.users != null) {
                            mAddableContacts = ArrayList()

                            //Get a map of number name pairs from my contacts
                            val numberNamePairs = myContactsController.numberNamePairs

                            apiResponse.users[0]?.filter { !it.user_name.isNullOrEmpty() }?.forEach {
                                //If user in my contacts, use that as user name
                                if (numberNamePairs.containsKey(it.getCell_number())) {
                                    it.setUser_name(numberNamePairs[it.getCell_number()])
                                }
                                mAddableContacts.add(it)
                            }

                            //Sort names alphabetically before notifying adapter
                            sortNamesFriends(mAddableContacts)
                            ProcessInvitableBackground().execute("")
                        }

                        Log.d("apiResponse", apiResponse.toString())
                    }
                }

                override fun onFailure(t: Throwable) {
                    Log.i(TAG, if (t.localizedMessage == null) "" else t.localizedMessage)
                    Toaster.makeToast(getApplicationContext(), "Loading contacts failed, please try again.", Toast.LENGTH_LONG).checkTastyToast()
                    ProcessInvitableBackground().execute("")
                }
            })

            return null
        }

        override fun onPostExecute(result: String?) {

        }
    }

    private inner class ProcessInvitableBackground : AsyncTask<String, Void, String>() {

        private var exit: Boolean? = false

        override fun onPreExecute() {
            //If list is already populated, return
            if (!mInvitableContacts.isEmpty()) exit = true
        }

        override fun doInBackground(vararg params: String): String {
            if (exit!!) return ""

            //Get the list of all local contacts, C
            val contacts = myContactsController.contacts
            //Temporarily set the invitable contacts, I, to all local contacts
            mInvitableContacts.clear()
            mInvitableContacts.addAll(contacts)

            //Check for current friends, F
            var currentFriends: ArrayList<User>? = ArrayList()
            if (jsonPersistence.friends != null && jsonPersistence.friends?.isEmpty() != true) {
                currentFriends = jsonPersistence.friends
            }

            //Make a list of all contact numbers from mAddableContacts and currentFriends, A + F
            val contactNumbersToRemove = mAddableContacts.mapTo(ArrayList<String>()) { it.cell_number }
            currentFriends?.mapTo(contactNumbersToRemove) { it.cell_number }

            //Perform the operation I = C - (A + F)
            for (contact in contacts) {
                for (number in contact.numbers.keys) {
                    if (contactNumbersToRemove.contains(number)) {
                        mInvitableContacts.remove(contact)
                        break
                    }
                }
            }

            //Sort names alphabetically before notifying adapter
            sortNamesContacts(mInvitableContacts)

            return ""
        }

        override fun onPostExecute(result: String?) {
            //Persist invitable contacts for those times where user has no internet connection
            jsonPersistence.invitableContacts = mInvitableContacts

            //Load new content (including section headers) into adapter
            mRecyclerViewElements.add(addableHeader)
            mRecyclerViewElements.addAll(mAddableContacts)
            mRecyclerViewElements.add(invitableHeader)
            mRecyclerViewElements.addAll(mInvitableContacts)
            //Display new content
            notifyAdapter()
            //Make load spinner GONE and recyclerview VISIBLE
            swiperefresh.isRefreshing = false
        }
    }

    @OnClick(R.id.retrieve_contacts_permission_text)
    fun retrieveContactsPermissionRetry() {
        ActivityCompat.requestPermissions(activity!!,
                arrayOf(android.Manifest.permission.READ_CONTACTS),
                RequestCode.PERMISSIONS_READ_CONTACTS.ordinal)
    }

    fun displayRequestPermissionExplainer(display: Boolean?) {
        if (display == true) {
            retrieve_contacts_permission_text.visibility = View.VISIBLE
            swiperefresh.isRefreshing = false
        } else {
            retrieve_contacts_permission_text.visibility = View.GONE
        }
    }

    companion object {

        private val TAG = FriendsFragmentActivity::class.java.simpleName

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         * @return A new instance of fragment FriendsInviteFragment3.
         */
        fun newInstance(param1: String, param2: String): FriendsInviteFragment3 {
            val fragment = FriendsInviteFragment3()
            val args = Bundle()
            fragment.arguments = args
            return fragment
        }
    }
}// Required empty public constructor
