/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.support.design.widget.TabLayout
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.app.FragmentStatePagerAdapter
import android.support.v4.view.PagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.widget.SearchView
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import butterknife.BindView
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.fragment.message_status.MessageStatusReceivedFragment1
import com.roostermornings.android.fragment.message_status.MessageStatusSentFragment2
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.ViewType
import com.roostermornings.android.sqlutil.DeviceAudioQueueItem
import com.roostermornings.android.util.Constants
import javax.inject.Inject

class MessageStatusFragmentActivity : BaseActivity(), MessageStatusReceivedFragment1.OnFragmentInteractionListener, MessageStatusSentFragment2.OnFragmentInteractionListener {

    @BindView(R.id.toolbar_title)
    lateinit var toolbarTitle: TextView
    @BindView(R.id.tabs)
    lateinit var tabLayout: TabLayout

    /**
     * The [ViewPager] that will host the section contents.
     */
    @BindView(R.id.messageViewPager)
    lateinit var mViewPager: ViewPager

    /**
     * The [android.support.v4.view.PagerAdapter] that will provide
     * fragments for each of the sections. We use a
     * [FragmentPagerAdapter] derivative, which will keep every
     * loaded fragment in memory. If this becomes too memory intensive, it
     * may be best to switch to a
     * [android.support.v4.app.FragmentStatePagerAdapter].
     */
    private var mSectionsPagerAdapter: MessageStatusFragmentActivity.SectionsPagerAdapter? = null

    private var messageStatusFragment1: MessageStatusReceivedFragment1? = null
    private var messageStatusFragment2: MessageStatusReceivedFragment1? = null
    private var messageStatusFragment3: MessageStatusSentFragment2? = null

    @Inject lateinit var baseApplication: BaseApplication

    override fun onFragmentInteraction(uri: Uri) {
        //you can leave it empty
    }

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(R.layout.activity_message_status)
        BaseApplication.roosterApplicationComponent.inject(this)

        setButtonBarSelection()

        //Set toolbar title
        val toolbar = setupToolbar(toolbarTitle, getString(R.string.message_status_activity_title))
        toolbar?.setNavigationIcon(R.drawable.md_nav_back)
        toolbar?.setNavigationOnClickListener { startHomeActivity() }

        //Create a viewpager with fragments controlled by SectionsPagerAdapter
        createViewPager(mViewPager)
        //This makes sure activityContentView is not recreated when scrolling, as we have 3 fragment pages
        mViewPager.offscreenPageLimit = 2
        tabLayout.setupWithViewPager(mViewPager)
        //Generate custom tab for tab layout
        createTabIcons()

        //Set volume rocker to alarm stream
        volumeControlStream = AudioManager.STREAM_MUSIC

        //Listen for change to mViewPager page display - used for toggling notifications
        mViewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageSelected(position: Int) {
                if (position == 1) {}
            }
        })

        //FontsOverride.changeTabsFont(getApplicationContext(), tabLayout, Constants.APP_FONT);

        //Handle search intent
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        //Display notifications
        updateRoosterNotification()
        updateRequestNotification()
    }

    override fun onPause() {
        super.onPause()

        messageStatusFragment1?.mAdapterClass?.resetMediaPlayer()
        messageStatusFragment2?.mAdapterClass?.resetMediaPlayer()
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEARCH == intent.action) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            //use the query to search your data somehow
            handleSearch(query)
        }
    }

    private fun handleSearch(query: String) {
        when {
            mViewPager.currentItem == 0 -> messageStatusFragment1?.searchRecyclerViewAdapter(query)
            mViewPager.currentItem == 1 -> messageStatusFragment2?.searchRecyclerViewAdapter(query)
            mViewPager.currentItem == 2 -> messageStatusFragment3?.searchRecyclerViewAdapter(query)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_roosters, menu)

        // Associate searchable configuration with the SearchView
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchView = menu.findItem(R.id.search_roosters).actionView as SearchView
        searchView.setSearchableInfo(
                searchManager.getSearchableInfo(componentName))

        //When searchView is closed, refresh data
        searchView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {

            override fun onViewDetachedFromWindow(arg0: View) {
                // search was detached/closed
                when {
                    mViewPager.currentItem == 0 -> messageStatusFragment1?.notifyAdapter()
                    mViewPager.currentItem == 1 -> messageStatusFragment2?.notifyAdapter()
                    mViewPager.currentItem == 2 -> messageStatusFragment3?.notifyAdapter()
                }
            }

            override fun onViewAttachedToWindow(arg0: View) {
                // search was opened
            }
        })

        return true
    }

    private fun createViewPager(mViewPager: ViewPager) {

        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.
        mSectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager)

        val bundleFragmentToday = Bundle()
        bundleFragmentToday.putString(ViewType.MESSAGE_STATUS_RECEIVED_FRAGMENT.name, ViewType.MESSAGE_STATUS_RECEIVED_FRAGMENT_TODAY.name)
        messageStatusFragment1 = Fragment.instantiate(applicationContext, MessageStatusReceivedFragment1::class.java.name, bundleFragmentToday) as MessageStatusReceivedFragment1

        val bundleFragmentFavourite = Bundle()
        bundleFragmentFavourite.putString(ViewType.MESSAGE_STATUS_RECEIVED_FRAGMENT.name, ViewType.MESSAGE_STATUS_RECEIVED_FRAGMENT_FAVOURITE.name)
        messageStatusFragment2 = Fragment.instantiate(applicationContext, MessageStatusReceivedFragment1::class.java.name, bundleFragmentFavourite) as MessageStatusReceivedFragment1

        messageStatusFragment3 = Fragment.instantiate(applicationContext, MessageStatusSentFragment2::class.java.name) as MessageStatusSentFragment2

        // Set up the ViewPager with the sections adapter.
        mViewPager.adapter = mSectionsPagerAdapter

        //Switch to relevant tab
        when (intent?.action) {
            Action.FROM_ROOSTER_SEND.name ->
                //Smooth scroll to item 2 of [0,1,2]
                mViewPager.setCurrentItem(2, true)
            else -> {}
        }
    }

    /**
     * A [FragmentPagerAdapter] that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    inner class SectionsPagerAdapter constructor(fm: FragmentManager) : FragmentStatePagerAdapter(fm) {

        override fun getItem(position: Int): Fragment? {

            return when (position) {
                0 -> messageStatusFragment1
                1 -> messageStatusFragment2
                2 -> messageStatusFragment3
                else -> null
            }
        }

        override fun getItemPosition(`object`: Any): Int {
            return PagerAdapter.POSITION_NONE
        }

        override fun getCount(): Int {
            return 3
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return when (position) {
                0 -> "TODAY"
                1 -> "FAVOURITE"
                2 -> "SENT"
                else -> null
            }
        }
    }

    private fun createTabIcons() {
        setTabLayout(0, "TODAY")
        setTabLayout(1, "FAVOURITE")
        setTabLayout(2, "SENT")
    }

    //Create custom tab layout
    private fun setTabLayout(position: Int, title: String) {
        val frameLayout = LayoutInflater.from(this).inflate(R.layout.custom_friends_tab, null) as FrameLayout
        val tabText = frameLayout.getChildAt(0) as TextView
        tabText.text = title

        //Disable clipping to ensure notification is shown properly
        val tabs = tabLayout.getChildAt(0) as ViewGroup
        tabs.clipToPadding = false
        tabs.clipChildren = false

        if (tabs.getChildAt(position) is ViewGroup) {
            (tabs.getChildAt(position) as ViewGroup).clipToPadding = false
            (tabs.getChildAt(position) as ViewGroup).clipChildren = false
        }

       tabLayout.getTabAt(position)?.customView = frameLayout
    }

    fun favouriteRooster(audioItem: DeviceAudioQueueItem, favourite: Boolean) {
        audioTableManager.setFavourite(audioItem.id, favourite)

        when(mViewPager.currentItem) {
            0 -> messageStatusFragment2?.manualSwipeRefresh()
            1 -> messageStatusFragment1?.manualSwipeRefresh()
        }

        if (audioItem.type == Constants.AUDIO_TYPE_CHANNEL) {
            FA.Log(FA.Event.roosters_channel_favourite::class.java, FA.Event.roosters_channel_favourite.Param.channel_title, audioItem.name)
        } else {
            FA.Log(FA.Event.roosters_social_favourite::class.java, null, null)
        }
    }
}
