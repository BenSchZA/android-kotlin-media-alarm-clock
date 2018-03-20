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
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.FriendsFragmentActivity
import com.roostermornings.android.adapter.FriendsRequestListAdapter
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.local.Friend
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.util.JSONPersistence
import com.roostermornings.android.util.MyContactsController
import com.roostermornings.android.util.Toaster
import kotlinx.android.synthetic.main.fragment_friends_2.*
import java.util.*
import javax.inject.Inject

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [FriendsRequestFragment2.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [FriendsRequestFragment2.newInstance] factory method to
 * create an instance of this fragment.
 */
class FriendsRequestFragment2 : BaseFragment() {

    internal var mUsers = ArrayList<Friend>()
    private var mRequestsReference: DatabaseReference? = null

    private var mAdapter: RecyclerView.Adapter<*>? = null

    private var mListener: OnFragmentInteractionListener? = null

    @Inject lateinit var myContactsController: MyContactsController
    @Inject lateinit var jsonPersistence: JSONPersistence

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return initiate(inflater, R.layout.fragment_friends_2, container, false)
    }

    //NB: bind ButterKnife to activityContentView and then initialise UI elements
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swiperefresh.isRefreshing = true
        /*
        * Sets up a SwipeRefreshLayout.OnRefreshListener that is invoked when the user
        * performs a swipe-to-refresh gesture.
        */
        swiperefresh.setOnRefreshListener {
            // This method performs the actual data-refresh operation.
            // The method calls setRefreshing(false) when it's finished.
            getRequests()
        }

        sortNamesFriends(mUsers)
        mAdapter = FriendsRequestListAdapter(mUsers)
        friendsRequestListView.layoutManager = LinearLayoutManager(AppContext)
        friendsRequestListView.adapter = mAdapter
    }

    fun manualSwipeRefresh() {
        if (swiperefresh != null) swiperefresh.isRefreshing = true
        getRequests()
    }

    private fun getRequests() {
        mRequestsReference = databaseReference
                .child("friend_requests_received").child(firebaseUser?.uid)
        mRequestsReference?.keepSynced(true)

        val friendsListener = object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                //Clear before repopulating
                mUsers.clear()

                //Get a map of number name pairs from my contacts
                //For each user, check if name appears in contacts, and allocate name
                var numberNamePairs = HashMap<String, String>()
                if (ContextCompat.checkSelfPermission(AppContext,
                        android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    //Get a map of contact numbers to names
                    numberNamePairs = myContactsController.numberNamePairs
                }

                for (postSnapshot in dataSnapshot.children) {
                    val user = postSnapshot.getValue(Friend::class.java)

                    //For each user, check if name appears in contacts, and allocate name
                    if (numberNamePairs.containsKey(user?.cell_number)) {
                        user?.setUser_name(numberNamePairs[user.cell_number])
                    }

                    user?.let { mUsers.add(it) }
                }

                //Sort names alphabetically before notifying adapter
                sortNamesFriends(mUsers)
                mAdapter?.notifyDataSetChanged()
                swiperefresh.isRefreshing = false
            }

            override fun onCancelled(databaseError: DatabaseError) {
                Log.w(TAG, "loadPost:onCancelled", databaseError.toException())
                Toaster.makeToast(AppContext, "Failed to load user.", Toast.LENGTH_SHORT).checkTastyToast()
            }
        }
        mRequestsReference?.addListenerForSingleValueEvent(friendsListener)
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)

        inject(BaseApplication.roosterApplicationComponent)

        getRequests()

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
        (mAdapter as FriendsRequestListAdapter).refreshAll(mUsers)
        (mAdapter as FriendsRequestListAdapter).filter.filter(query)
    }

    fun notifyAdapter() {
        (mAdapter as FriendsRequestListAdapter).refreshAll(mUsers)
        mAdapter?.notifyDataSetChanged()
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

    companion object {

        private val TAG = FriendsFragmentActivity::class.java.simpleName

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         * @return A new instance of fragment FriendsRequestFragment2.
         */
        fun newInstance(param1: String, param2: String): FriendsRequestFragment2 {
            val fragment = FriendsRequestFragment2()
            val args = Bundle()
            fragment.arguments = args
            return fragment
        }
    }
}

