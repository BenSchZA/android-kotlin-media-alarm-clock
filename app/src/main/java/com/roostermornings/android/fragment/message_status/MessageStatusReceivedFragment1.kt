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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.adapter.MessageStatusReceivedListAdapter
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.keys.ViewType
import com.roostermornings.android.sqlutil.AudioTableManager
import com.roostermornings.android.sqlutil.DeviceAudioQueueItem
import com.roostermornings.android.util.JSONPersistence
import com.roostermornings.android.util.MyContactsController
import kotlinx.android.synthetic.main.fragment_message_status.*
import java.util.*
import javax.inject.Inject

class MessageStatusReceivedFragment1 : BaseFragment() {

    private var mRoosters = ArrayList<DeviceAudioQueueItem>()

    private var mAdapter: RecyclerView.Adapter<*>? = null
    var mAdapterClass: MessageStatusReceivedListAdapter? = null
    private var mListener: MessageStatusReceivedFragment1.OnFragmentInteractionListener? = null

    private var fragmentType: String? = ""

    @Inject
    lateinit var jsonPersistence: JSONPersistence
    @Inject
    lateinit var myContactsController: MyContactsController
    @Inject
    lateinit var audioTableManager: AudioTableManager

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)

        inject(BaseApplication.roosterApplicationComponent)

        if (context is MessageStatusReceivedFragment1.OnFragmentInteractionListener) {
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

        /*
        * Sets up a SwipeRefreshLayout.OnRefreshListener that is invoked when the user
        * performs a swipe-to-refresh gesture.
        */
        swiperefresh.setOnRefreshListener {
            // This method performs the actual data-refresh operation.
            // The method calls setRefreshing(false) when it's finished.
            retrieveSocialAudioItems()
            mAdapterClass?.resetMediaPlayer()
        }

        mAdapter = MessageStatusReceivedListAdapter(mRoosters, activity, fragmentType)
        mAdapterClass = mAdapter as MessageStatusReceivedListAdapter?
        message_statusListView.layoutManager = LinearLayoutManager(AppContext)
        message_statusListView.adapter = mAdapter

        //Retrieve DeviceAudioQueueItems to display
        retrieveSocialAudioItems()

        //Log how many favourites the user has
        if (fragmentType == ViewType.MESSAGE_STATUS_RECEIVED_FRAGMENT_FAVOURITE.name) {
            FA.SetUserProp(FA.UserProp.user_favourites::class.java, mRoosters.size.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bundle = arguments
        fragmentType = bundle?.getString(ViewType.MESSAGE_STATUS_RECEIVED_FRAGMENT.name)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return initiate(inflater, R.layout.fragment_message_status, container, false)
    }

    private fun retrieveSocialAudioItems() {
        if (fragmentType == ViewType.MESSAGE_STATUS_RECEIVED_FRAGMENT_TODAY.name) {
            mRoosters = audioTableManager.extractTodaySocialAudioFiles()
            mRoosters.addAll(audioTableManager.extractTodayChannelAudioFiles())
            //Sort names alphabetically before notifying adapter
            sortDeviceAudioQueueItems(mRoosters)
            notifyAdapter()

            if(mRoosters.isEmpty()) {
                filler_layout.visibility = View.VISIBLE
                filler_frame.background = ResourcesCompat.getDrawable(resources, R.drawable.rooster_channels, null)
                filler_text.text = "Add your friends and get a surprise rooster"
            } else filler_layout.visibility = View.GONE

            swiperefresh.isRefreshing = false
        } else {
            mRoosters = audioTableManager.extractFavouriteSocialAudioFiles()
            mRoosters.addAll(audioTableManager.extractFavouriteChannelAudioFiles())
            //Sort names alphabetically before notifying adapter
            sortDeviceAudioQueueItems(mRoosters)
            notifyAdapter()

            if(mRoosters.isEmpty()) {
                filler_layout.visibility = View.VISIBLE
                filler_frame.background = ResourcesCompat.getDrawable(resources, R.drawable.rooster_singing, null)
                filler_text.text = "Listen to your favourite channels & roosters later"
            } else filler_layout.visibility = View.GONE

            swiperefresh.isRefreshing = false
        }
    }

    fun manualSwipeRefresh() {
        if (swiperefresh?.isRefreshing == false) swiperefresh?.isRefreshing = true
        retrieveSocialAudioItems()
    }

    fun searchRecyclerViewAdapter(query: String) {
        //Filter contacts by CharSequence
        //Get reference to list adapter to access getFilter method
        (mAdapter as MessageStatusReceivedListAdapter).refreshAll(mRoosters)
        (mAdapter as MessageStatusReceivedListAdapter).filter.filter(query)
    }

    fun notifyAdapter() {
        (mAdapter as MessageStatusReceivedListAdapter).refreshAll(mRoosters)
    }
}
