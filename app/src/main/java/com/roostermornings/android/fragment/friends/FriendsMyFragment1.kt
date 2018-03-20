/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment.friends

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import butterknife.BindView
import com.facebook.FacebookSdk.getApplicationContext
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.FriendsFragmentActivity
import com.roostermornings.android.adapter.FriendsMyListAdapter
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.User
import com.roostermornings.android.domain.database.Users
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.util.JSONPersistence
import com.roostermornings.android.util.MyContactsController
import com.roostermornings.android.util.Toaster
import retrofit.Callback
import retrofit.Response
import retrofit.Retrofit
import java.util.*
import javax.inject.Inject

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [FriendsMyFragment1.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [FriendsMyFragment1.newInstance] factory method to
 * create an instance of this fragment.
 */
class FriendsMyFragment1 : BaseFragment() {

    private var mUsers: ArrayList<User> = ArrayList()
    private var firebaseIdToken: String? = ""

    private var mAdapter: RecyclerView.Adapter<*>? = null

    @BindView(R.id.friendsMyListView)
    lateinit var mRecyclerView: RecyclerView
    @BindView(R.id.swiperefresh)
    lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var mListener: OnFragmentInteractionListener? = null

    @Inject lateinit var jsonPersistence: JSONPersistence
    @Inject lateinit var myContactsController: MyContactsController

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //Ensure check for Node complete reset
        statusCode = -1
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view = initiate(inflater, R.layout.fragment_friends_1, container, false)

        /*
        * Sets up a SwipeRefreshLayout.OnRefreshListener that is invoked when the user
        * performs a swipe-to-refresh gesture.
        */
        swipeRefreshLayout.setOnRefreshListener {
            // This method performs the actual data-refresh operation.
            // The method calls setRefreshing(false) when it's finished.
            retrieveMyFriends()
        }

        if (!jsonPersistence.friends.isEmpty()) {
            mUsers = jsonPersistence.friends
        } else if (checkInternetConnection()) {
            if (!swipeRefreshLayout.isRefreshing) swipeRefreshLayout.isRefreshing = true
        }

        //Listen for changes to friends node and refresh on change
        registerFriendsListener()

        return view
    }

    fun manualSwipeRefresh() {
        if (!swipeRefreshLayout.isRefreshing) swipeRefreshLayout.isRefreshing = true
        retrieveMyFriends()
    }

    private fun registerFriendsListener() {
        //Listen for changes to friends node

        val mFriendsReference = databaseReference
                .child("users").child(firebaseUser?.uid).child("friends")
        mFriendsReference.keepSynced(true)

        val friendsListener = object : ValueEventListener {
            internal var firstRun: Boolean? = null
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (firstRun == null) firstRun = true
                if (firstRun == false) {
                    manualSwipeRefresh()
                }
                firstRun = false
            }

            override fun onCancelled(databaseError: DatabaseError) {
                Log.w(TAG, "loadPost:onCancelled", databaseError.toException())
            }
        }
        mFriendsReference.addValueEventListener(friendsListener)
    }

    //NB: bind ButterKnife to activityContentView and then initialise UI elements
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //Sort names alphabetically before notifying adapter
        sortNamesUsers(mUsers)
        moveSelfToStart()

        mAdapter = FriendsMyListAdapter(mUsers, activity)
        mRecyclerView.layoutManager = LinearLayoutManager(AppContext)
        mRecyclerView.adapter = mAdapter

        retrieveMyFriends()
    }

    private fun retrieveMyFriends() {

        if (!checkInternetConnection()) {
            swipeRefreshLayout.isRefreshing = false
            return
        }

        if (firebaseUser == null) {
            Toaster.makeToast(getApplicationContext(), "Loading friends failed, please try again.", Toast.LENGTH_LONG).checkTastyToast()
            swipeRefreshLayout.isRefreshing = false
            return
        }

        if ("" == firebaseIdToken) {
            refreshToken { successful ->
                if(successful) {
                    callNodeMyFriendsAPI()
                } else {
                    // Handle error -> task.getException();
                    Toaster.makeToast(getApplicationContext(), "Loading friends failed, please try again.", Toast.LENGTH_LONG).checkTastyToast()
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        } else {
            callNodeMyFriendsAPI()
        }
    }

    fun refreshToken(result: (Boolean) -> Unit) {
        firebaseUser?.getIdToken(true)
                ?.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        firebaseIdToken = task.result.token
                        result(true)
                    } else {
                        result(false)
                    }
                }
    }

    private fun callNodeMyFriendsAPI() {
        val call = nodeApiService().retrieveUserFriends(firebaseIdToken!!)

        call.enqueue(object : Callback<Users> {
            override fun onResponse(response: Response<Users>,
                                    retrofit: Retrofit) {

                statusCode = response.code()
                val apiResponse = response.body()
                if (apiResponse?.users == null) {
                    swipeRefreshLayout.isRefreshing = false
                    return
                }

                if (statusCode == 200) {

                    swipeRefreshLayout.isRefreshing = false

                    mUsers.clear()
                    apiResponse.users.filterNotNullTo(mUsers)

                    //For each user, check if name appears in contacts, and allocate name
                    val numberNamePairs: HashMap<String, String>
                    if (ContextCompat.checkSelfPermission(getApplicationContext(),
                            android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                        //Get a map of contact numbers to names
                        numberNamePairs = myContactsController.numberNamePairs

                        mUsers.forEach {
                            if (numberNamePairs.containsKey(it.cell_number)) {
                                it.user_name = numberNamePairs[it.cell_number]?:it.user_name
                            }
                        }
                    }

                    sortNamesUsers(mUsers)
                    moveSelfToStart()

                    //Persist friends array to disk
                    jsonPersistence.friends = mUsers
                    mAdapter?.notifyDataSetChanged()
                }
            }

            override fun onFailure(t: Throwable) {
                Log.i(TAG, t.localizedMessage?:"")
                Toaster.makeToast(getApplicationContext(), "Loading friends failed, please try again.", Toast.LENGTH_LONG).checkTastyToast()
                swipeRefreshLayout.isRefreshing = false
            }
        })
    }

    private fun moveSelfToStart() {
        // Move self to beginning of list
        mUsers.firstOrNull { it.uid == firebaseUser?.uid }?.let { self ->
            self.user_name = "Me"
            mUsers.remove(self)
            mUsers.add(0, self)
        }
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)

        inject(BaseApplication.roosterApplicationComponent)

        if (context is OnFragmentInteractionListener) {
            mListener = context
        } else {
            throw RuntimeException(context.toString() + " must implement OnFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        mListener = null
    }

    fun searchRecyclerViewAdapter(query: String) {
        //Filter contacts by CharSequence
        //Get reference to list adapter to access getFilter method
        (mAdapter as FriendsMyListAdapter).refreshAll(mUsers)
        (mAdapter as FriendsMyListAdapter).filter.filter(query)
    }

    fun notifyAdapter() {
        (mAdapter as FriendsMyListAdapter).refreshAll(mUsers)
        mAdapter?.notifyDataSetChanged()
    }

    //mListener.onFragmentInteraction(uri);

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

    companion object {

        private val TAG: String = FriendsFragmentActivity::class.java.simpleName

        private var statusCode = -1

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         * @return A new instance of fragment FriendsMyFragment1.
         */
        fun newInstance(param1: String, param2: String): FriendsMyFragment1 {
            val fragment = FriendsMyFragment1()
            val args = Bundle()
            fragment.arguments = args
            return fragment
        }
    }
}
