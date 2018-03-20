/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment.message_status

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.content.res.ResourcesCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.firebase.database.*
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.BuildConfig
import com.roostermornings.android.R
import com.roostermornings.android.adapter.MessageStatusSentListAdapter
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.SocialRooster
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.util.Constants
import com.roostermornings.android.util.JSONPersistence
import com.roostermornings.android.util.MyContactsController
import com.roostermornings.android.util.Toaster
import kotlinx.android.synthetic.main.fragment_message_status.*
import java.util.*
import javax.inject.Inject

class MessageStatusSentFragment2 : BaseFragment() {

    private var mRoosters = ArrayList<SocialRooster>()

    private var mSocialRoosterUploadsReference: DatabaseReference? = null
    private var mSocialRoosterQueueReference: DatabaseReference? = null

    private var mAdapter: RecyclerView.Adapter<*>? = null
    private var mListener: MessageStatusSentFragment2.OnFragmentInteractionListener? = null
    private var calendar = Calendar.getInstance()

    @Inject
    lateinit var jsonPersistence: JSONPersistence
    @Inject
    lateinit var myContactsController: MyContactsController
    @Inject lateinit var firebaseDatabaseReference: DatabaseReference

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)

        inject(BaseApplication.roosterApplicationComponent)

        if (context is MessageStatusSentFragment2.OnFragmentInteractionListener) {
            mListener = context
        } else {
            throw RuntimeException(context.toString() + " must implement OnFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        mListener = null
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
            updateMessageStatus()
        }

        //Sort names alphabetically before notifying adapter
        sortSocialRoosters(mRoosters)
        mAdapter = MessageStatusSentListAdapter(mRoosters, activity)
        message_statusListView.layoutManager = LinearLayoutManager(AppContext)
        message_statusListView.adapter = mAdapter

        //Reload adapter data and set message status, set listener for new data
        updateMessageStatus()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return initiate(inflater, R.layout.fragment_message_status, container, false)
    }

    private fun updateMessageStatus() {
        if (firebaseUser == null) {
            Toaster.makeToast(context, "Couldn't load user. Try reconnect to the internet and try again.", Toast.LENGTH_SHORT)
            return
        }

        mSocialRoosterUploadsReference = firebaseDatabaseReference
                .child("social_rooster_uploads").child(firebaseUser?.uid)
        mSocialRoosterUploadsReference?.keepSynced(true)

        val socialRoosterUploadsListener = object : ChildEventListener {
            override fun onChildAdded(dataSnapshot: DataSnapshot, s: String?) {
                val socialRoosterU = dataSnapshot.getValue(SocialRooster::class.java)
                for (item in mRoosters)
                    if (item.queue_id == socialRoosterU?.queue_id) {
                        mRoosters.remove(item)
                        break
                    }
                processSocialRoosterUploadsItem(socialRoosterU)
                notifyAdapter()
            }

            override fun onChildChanged(dataSnapshot: DataSnapshot, s: String?) {
                val socialRoosterU = dataSnapshot.getValue(SocialRooster::class.java)
                for (item in mRoosters)
                    if (item.queue_id == socialRoosterU?.queue_id) {
                        mRoosters.remove(item)
                        break
                    }
                processSocialRoosterUploadsItem(socialRoosterU)
                notifyAdapter()
            }

            override fun onChildRemoved(dataSnapshot: DataSnapshot) {
                val socialRoosterU = dataSnapshot.getValue(SocialRooster::class.java)
                for (item in mRoosters)
                    if (item.queue_id == socialRoosterU?.queue_id) {
                        mRoosters.remove(item)
                        break
                    }
                notifyAdapter()
            }

            override fun onChildMoved(dataSnapshot: DataSnapshot, s: String?) {
                notifyAdapter()
            }

            override fun onCancelled(databaseError: DatabaseError) {

            }
        }
        mSocialRoosterUploadsReference?.addChildEventListener(socialRoosterUploadsListener)

        //https://stackoverflow.com/questions/34530566/find-out-if-child-event-listener-on-firebase-completely-load-all-data
        //Value events are always triggered last and are guaranteed to contain updates from any other events which occurred before that snapshot was taken.
        mSocialRoosterUploadsReference?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if(mRoosters.isEmpty()) {
                    filler_layout.visibility = View.VISIBLE
                    filler_frame.background = ResourcesCompat.getDrawable(resources, R.drawable.rooster_nudge, null)
                    filler_text.text = "Wake your friends up to a surprise rooster"
                } else filler_layout.visibility = View.GONE

                swiperefresh.isRefreshing = false
            }

            override fun onCancelled(databaseError: DatabaseError) {

            }
        })
    }

    private fun processSocialRoosterUploadsItem(socialRoosterU: SocialRooster?) {
        val dateUploaded = socialRoosterU?.date_uploaded ?: return

        if (dateUploaded > calendar.timeInMillis - 2 * Constants.TIME_MILLIS_1_DAY) {
            //Clear old entries on change
            mRoosters.add(socialRoosterU)
            if (mRoosters.indexOf(socialRoosterU) > -1)
                mRoosters[mRoosters.indexOf(socialRoosterU)].status = Constants.MESSAGE_STATUS_SENT
            if (socialRoosterU.getListened()) {
                if (mRoosters.indexOf(socialRoosterU) > -1)
                    mRoosters[mRoosters.indexOf(socialRoosterU)].status = Constants.MESSAGE_STATUS_RECEIVED
                notifyAdapter()
            } else {
                mSocialRoosterQueueReference = firebaseDatabaseReference
                        .child("social_rooster_queue").child(socialRoosterU.receiver_id).child(socialRoosterU.queue_id)
                mSocialRoosterQueueReference?.keepSynced(true)

                val socialRoosterQueueListener = object : ValueEventListener {
                    override fun onDataChange(dataSnapshot: DataSnapshot) {
                        if (!dataSnapshot.exists()) {
                            if (mRoosters.indexOf(socialRoosterU) > -1)
                                mRoosters[mRoosters.indexOf(socialRoosterU)].status = Constants.MESSAGE_STATUS_DELIVERED
                            notifyAdapter()
                        }
                    }

                    override fun onCancelled(databaseError: DatabaseError) {
                        Log.w(TAG, "loadPost:onCancelled", databaseError.toException())
                        if (BuildConfig.DEBUG)
                            Toaster.makeToast(context, "Failed to load message status.",
                                    Toast.LENGTH_SHORT).checkTastyToast()
                    }
                }
                mSocialRoosterQueueReference?.addValueEventListener(socialRoosterQueueListener)
            }
        }
    }

    fun searchRecyclerViewAdapter(query: String) {
        //Filter contacts by CharSequence
        //Get reference to list adapter to access getFilter method
        (mAdapter as MessageStatusSentListAdapter).refreshAll(mRoosters)
        (mAdapter as MessageStatusSentListAdapter).filter.filter(query)
    }

    fun notifyAdapter() {
        sortSocialRoosters(mRoosters)
        mAdapter?.notifyDataSetChanged()
    }

    companion object {

        val TAG = MessageStatusSentFragment2::class.java.simpleName
    }

}
