/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.*
import butterknife.BindView
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.User
import com.roostermornings.android.domain.local.Contact
import com.roostermornings.android.domain.local.Friend
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.fragment.friends.FriendsInviteFragment3
import com.roostermornings.android.fragment.friends.FriendsMyFragment1
import com.roostermornings.android.fragment.friends.FriendsRequestFragment2
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Flag
import com.roostermornings.android.keys.PrefsKey
import com.roostermornings.android.keys.RequestCode
import com.roostermornings.android.onboarding.CustomCommandInterface
import com.roostermornings.android.onboarding.InterfaceCommands
import com.roostermornings.android.onboarding.ProfileCreationFragment
import com.roostermornings.android.onboarding.number_entry.NumberEntryDialogFragment
import com.roostermornings.android.onboarding.number_entry.NumberEntryFragment
import com.roostermornings.android.onboarding.number_entry.NumberEntryListener
import com.roostermornings.android.snackbar.SnackbarManager
import com.roostermornings.android.util.Toaster
import kotlinx.android.synthetic.main.activity_friends.*
import javax.inject.Inject

//Responsible for managing friends: 1) my friends, 2) addable friends, 3) friend invites
class FriendsFragmentActivity : BaseActivity(), FriendsMyFragment1.OnFragmentInteractionListener, FriendsRequestFragment2.OnFragmentInteractionListener, NumberEntryListener, CustomCommandInterface {
    @BindView(R.id.toolbar)
    lateinit var toolbar: Toolbar
    @BindView(R.id.toolbar_title)
    lateinit var toolbarTitle: TextView
    @BindView(R.id.tabs)
    lateinit var tabLayout: TabLayout
    @BindView(R.id.button_bar)
    lateinit var buttonBarLayout: LinearLayout

    /**
     * The [ViewPager] that will host the section contents.
     */
    @BindView(R.id.friendsViewPager)
    lateinit var mViewPager: ViewPager
    /**
     * The [android.support.v4.view.PagerAdapter] that will provide
     * fragments for each of the sections. We use a
     * [FragmentPagerAdapter] derivative, which will keep every
     * loaded fragment in memory. If this becomes too memory intensive, it
     * may be best to switch to a
     * [android.support.v4.app.FragmentStatePagerAdapter].
     */
    private var mSectionsPagerAdapter: SectionsPagerAdapter? = null

    private var mFriendRequestsReceivedReference: DatabaseReference? = null
    private var mFriendRequestsSentReference: DatabaseReference? = null
    private var mCurrentUserReference: DatabaseReference? = null
    private var receiver: BroadcastReceiver? = null

    private var friendsFragment1: FriendsMyFragment1? = null
    private var friendsFragment2: FriendsRequestFragment2? = null
    private var friendsFragment3: FriendsInviteFragment3? = null

    @Inject lateinit var baseApplication: BaseApplication

    private var snackbarManager: SnackbarManager? = null

    interface FriendsInviteListAdapterInterface {
        //Send invite to Rooster user from contact list
        fun inviteUser(friend: Friend)
    }

    interface FriendsRequestListAdapterInterface {
        //Accept friend request and update Firebase DB
        fun acceptFriendRequest(acceptFriend: Friend)
        fun rejectFriendRequest(rejectFriend: Friend)
    }

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initialize(R.layout.activity_friends)
        BaseApplication.roosterApplicationComponent.inject(this)

        // If the user is anonymous, show sign-up fragment
        if (!authManager.isUserSignedIn()) {
            showSignUpFragment()
        } else {
            // If number hasn't been provided
            showNumberEntryFragment()
        }

        snackbarManager = SnackbarManager(this, fillerContainer)

        setButtonBarSelection()

        //Set toolbar title
        val toolbar = setupToolbar(toolbarTitle, getString(R.string.friends))
        toolbar?.setNavigationIcon(R.drawable.md_nav_back)
        toolbar?.setNavigationOnClickListener { startHomeActivity() }

        if(firebaseUser == null) {
            Toaster.makeToast(this, "Couldn't load user. Try reconnect to the internet and try again.", Toast.LENGTH_SHORT)
            return
        }

        //Keep local and Firebase alarm dbs synced, and enable offline persistence
        mFriendRequestsReceivedReference = FirebaseDatabase.getInstance().reference
                .child("friend_requests_received").child((firebaseUser as FirebaseUser).uid)

        mFriendRequestsSentReference = FirebaseDatabase.getInstance().reference
                .child("friend_requests_sent").child((firebaseUser as FirebaseUser).uid)

        mCurrentUserReference = FirebaseDatabase.getInstance().reference
                .child("users").child((firebaseUser as FirebaseUser).uid)

        mFriendRequestsReceivedReference?.keepSynced(true)
        mFriendRequestsSentReference?.keepSynced(true)
        mCurrentUserReference?.keepSynced(true)

        //Create a viewpager with fragments controlled by SectionsPagerAdapter
        createViewPager(mViewPager)
        //This makes sure activityContentView is not recreated when scrolling, as we have 3 fragment pages
        mViewPager.offscreenPageLimit = 2
        tabLayout.setupWithViewPager(mViewPager)
        //Generate custom tab for tab layout
        createTabIcons()

        //Check for new Firebase datachange notifications and register broadcast receiver
        updateNotifications()

        //Listen for change to mViewPager page display - used for toggling notifications
        mViewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                if (position == 1) {
                    //Clear request notification badge
                    setTabNotification(position, false)
                    setButtonBarNotification(false)
                    BaseApplication.setNotificationFlag(0, Flag.FRIEND_REQUESTS.name)
                }
            }

            override fun onPageScrollStateChanged(state: Int) {}
        })

        //Handle search intent
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        // Remove Realm listeners, and close Realm
        snackbarManager?.destroy()
    }

    private var numberEntryFragment: NumberEntryFragment? = null
    private fun showNumberEntryFragment() {
        val mobileNumberValidated = sharedPreferences.getBoolean(PrefsKey.MOBILE_NUMBER_VALIDATED.name, false)

        if(!mobileNumberValidated) {
            FirebaseNetwork.setOnFlagValidMobileNumberCompleteListener(object : FirebaseNetwork.OnFlagValidMobileNumberCompleteListener {
                override fun onEvent(valid: Boolean) {
                    val mobileNumberEntryDismissed = sharedPreferences.getBoolean(
                            PrefsKey.MOBILE_NUMBER_ENTRY_DISMISSED.name, false)

                    if (!valid && !mobileNumberEntryDismissed) {
                        appbar.visibility = View.GONE

                        numberEntryFragment = NumberEntryFragment.newInstance()
                        supportFragmentManager.beginTransaction()
                                .replace(R.id.fillerContainer, numberEntryFragment)
                                .commit()
                    }

                    // Refresh UI fragment to show number entry dialog
                    mSectionsPagerAdapter?.notifyDataSetChanged()
                }
            })
            FirebaseNetwork.flagValidMobileNumber(this, true)
        }
    }

    private var profileCreationFragment: ProfileCreationFragment? = null
    private fun showSignUpFragment() {
        appbar.visibility = View.GONE

        profileCreationFragment = ProfileCreationFragment.newInstance(ProfileCreationFragment.Companion.Source.FRIENDS_PAGE)
        supportFragmentManager.beginTransaction()
                .replace(R.id.fillerContainer, profileCreationFragment)
                .commit()
    }

    private fun clearOnboardingFragments() {
        appbar.visibility = View.VISIBLE

        val transaction = supportFragmentManager
                .beginTransaction()

        profileCreationFragment?.let {
            transaction
                    .remove(it)
        }
        numberEntryFragment?.let {
            transaction
                    .remove(it)
        }
        transaction.commit()
    }

    override fun onStart() {
        super.onStart()
        //Display notifications
        updateRoosterNotification()
        //updateRequestNotification();
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
    }

    override fun onCustomCommand(command: InterfaceCommands.Companion.Command) {
        when(command) {
            InterfaceCommands.Companion.Command.PROCEED -> {
                // Set shared pref to indicate whether mobile number is valid, if valid show request
                showNumberEntryFragment()
                clearOnboardingFragments()
                // Refresh token and friends list if necessary
                friendsFragment1?.refreshToken{}
            }
            else -> {}
        }
    }

    private fun handleIntent(intent: Intent) {
        //mViewPager.setCurrentItem(sharedPreferences.getInt(Constants.FRIENDS_ACTIVITY_CURRENT_FRAGMENT, 0));
        if (Intent.ACTION_SEARCH == intent.action) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            //use the query to search your data somehow
            handleSearch(query)
        }
    }

    private fun handleSearch(query: String) {
        when(mViewPager.currentItem) {
            0 -> friendsFragment1?.searchRecyclerViewAdapter(query)
            1 -> if (friendsFragment2?.isAdded == true)
                friendsFragment2?.searchRecyclerViewAdapter(query)
            2 -> friendsFragment3?.searchRecyclerViewAdapter(query)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_friends, menu)

        // Associate searchable configuration with the SearchView
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchView = menu.findItem(R.id.search_friends).actionView as SearchView
        searchView.setSearchableInfo(
                searchManager.getSearchableInfo(componentName))

        //When searchView is closed, refresh data
        searchView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {

            override fun onViewDetachedFromWindow(arg0: View) {
                // search was detached/closed
                when(mViewPager.currentItem) {
                    0 -> friendsFragment1?.notifyAdapter()
                    1 -> if (friendsFragment2?.isAdded == true)
                            friendsFragment2?.notifyAdapter()
                    2 -> friendsFragment3?.notifyAdapter()
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

        friendsFragment1 = Fragment.instantiate(applicationContext, FriendsMyFragment1::class.java.name) as FriendsMyFragment1
        friendsFragment2 = Fragment.instantiate(applicationContext, FriendsRequestFragment2::class.java.name) as FriendsRequestFragment2
        friendsFragment3 = Fragment.instantiate(applicationContext, FriendsInviteFragment3::class.java.name) as FriendsInviteFragment3

        // Set up the ViewPager with the sections adapter.
        mViewPager.adapter = mSectionsPagerAdapter
    }

    /**
     * A [FragmentPagerAdapter] that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    private inner class SectionsPagerAdapter internal constructor(fm: FragmentManager) : FragmentStatePagerAdapter(fm) {

        override fun getItem(position: Int): Fragment? {

            return when (position) {
                0 -> friendsFragment1
                1 -> {
                    if (sharedPreferences
                            .getBoolean(PrefsKey.MOBILE_NUMBER_VALIDATED.name, false))
                        friendsFragment2
                    else NumberEntryDialogFragment()
                }
                2 -> friendsFragment3
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
                0 -> "FRIENDS"
                1 -> "REQUESTS"
                2 -> "INVITE"
                else -> null
            }
        }
    }

    override fun onMobileNumberValidated(mobileNumber: String) {
        clearOnboardingFragments()
        mSectionsPagerAdapter?.notifyDataSetChanged()
    }

    override fun dismissFragment() {
        clearOnboardingFragments()
    }

    private fun createTabIcons() {
        setTabLayout(0, "FRIENDS")
        setTabLayout(1, "REQUESTS")
        setTabLayout(2, "INVITE")
    }

    //Create custom tab layout
    private fun setTabLayout(position: Int, title: String) {
        val frameLayout = LayoutInflater.from(this).inflate(R.layout.custom_friends_tab, null) as? FrameLayout

        (frameLayout?.getChildAt(0) as? TextView)?.let {
            it.text = title
        }

        //Disable clipping to ensure notification is shown properly
        (tabLayout.getChildAt(0) as? ViewGroup)?.let {
            it.clipToPadding = false
            it.clipChildren = false
        }

        (tabs.getChildAt(position) as? ViewGroup)?.let {
            it.clipToPadding = false
            it.clipChildren = false
        }

        tabLayout.getTabAt(position)?.customView = frameLayout
    }

    //Set current tab notification
    fun setTabNotification(position: Int, notification: Boolean) {
        val tab = tabLayout.getTabAt(position)
        if (tab != null) {
            val frameLayout = tab.customView as? FrameLayout
            if (frameLayout != null) {
                val tabNotification = frameLayout.findViewById<ImageView>(R.id.notification_friends)
                if (notification)
                    tabNotification.visibility = View.VISIBLE
                else
                    tabNotification.visibility = View.GONE
                tab.customView = frameLayout
            }
        }
    }

    fun setButtonBarNotification(notification: Boolean) {
        val buttonBarNotification = buttonBarLayout.findViewById<ImageView>(R.id.notification_friends)
        if (notification)
            buttonBarNotification.visibility = View.VISIBLE
        else
            buttonBarNotification.visibility = View.GONE
    }

    private fun updateNotifications() {
        //Flag check for UI changes on load, broadcastreceiver for changes while activity running
        //If notifications waiting, display new friend request notification
        if (BaseApplication.getNotificationFlag(Flag.FRIEND_REQUESTS.name) > 0) {
            setButtonBarNotification(true)
            setTabNotification(1, true)
        }

        //Broadcast receiver filter to receive UI updates
        val firebaseListenerServiceFilter = IntentFilter()
        firebaseListenerServiceFilter.addAction(Action.REQUEST_NOTIFICATION.name)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                //do something based on the intent's action
                if (BaseApplication.getNotificationFlag(Flag.FRIEND_REQUESTS.name) > 0) {
                    setButtonBarNotification(true)
                    setTabNotification(1, true)
                    manualSwipeRefreshRequests()
                }
            }
        }
        registerReceiver(receiver, firebaseListenerServiceFilter)
    }

    fun manualSwipeRefreshFriends() {
        friendsFragment1?.manualSwipeRefresh()
    }

    fun manualSwipeRefreshRequests() {
        if (friendsFragment2?.isAdded == true) friendsFragment2?.manualSwipeRefresh()
    }

    override fun onDestroy() {
        if (receiver != null) {
            unregisterReceiver(receiver)
            receiver = null
        }
        super.onDestroy()
    }

    override fun onFragmentInteraction(uri: Uri) {
        //you can leave it empty
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            RequestCode.PERMISSIONS_READ_CONTACTS.ordinal -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    friendsFragment3?.requestPermissionReadContacts()
                } else {
                    friendsFragment3?.displayRequestPermissionExplainer(true)
                }
            }
        }// other 'case' lines to check for other
        // permissions this app might request
    }

    //Delete friend from Firebase user friend list
    fun deleteFriend(deleteFriend: User): Boolean {
        if(firebaseUser == null) {
            Toaster.makeToast(this, "Couldn't load user. Try reconnect to the internet and try again.", Toast.LENGTH_SHORT)
            return false
        }

        firebaseUser?.takeIf { it.uid != deleteFriend.uid }?.let {
            val snackbar = SnackbarManager.Companion.SnackbarQueueElement("${deleteFriend.user_name} deleted", action = View.OnClickListener {
                //Undo clear current user's and friend's friend list
                FirebaseNetwork.addFriend(deleteFriend)
            }, actionText = "Undo", priority = 1)

            snackbarManager?.generateSnackbar(snackbar)

            return FirebaseNetwork.removeFriend(deleteFriend)

        } ?: snackbarManager?.generateSnackbar(SnackbarManager.Companion.SnackbarQueueElement("You can't delete yourself!", actionText = "Okay", priority = 1))

        return false
    }

    //Invite contact via Whatsapp or fallback to SMS
    fun inviteContact(contact: Contact) {
        //        Uri uri = Uri.parse("smsto:" + contact.getPrimaryNumber());
        //        Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
        //        intent.putExtra("sms_body", getResources().getString(R.string.invite_to_rooster_message));
        //        intent.setType("text/plain");
        //        intent.setPackage("com.whatsapp");
        //        startActivity(Intent.createChooser(intent, ""));
        //        Intent intent = new Intent(Intent.ACTION_SENDTO);
        //        intent.putExtra(Intent.EXTRA_PHONE_NUMBER, contact.getPrimaryNumber());
        //        intent.putExtra(Intent.EXTRA_TEXT, getResources().getString(R.string.invite_to_rooster_message));
        //        intent.setType("text/plain");
        //        intent.setPackage("com.whatsapp");
        //        startActivity(intent);
        //        Intent sendIntent = new Intent("android.intent.action.MAIN");
        //        //sendIntent.setComponent(new ComponentName("com.whatsapp", "com.whatsapp.Conversation"));
        //        sendIntent.setAction(Intent.ACTION_SEND);
        //        sendIntent.setType("text/plain");
        //        sendIntent.putExtra(Intent.EXTRA_TEXT, "This is my text to send.");
        //        sendIntent.putExtra("jid", contact.getPrimaryNumber() + "@s.whatsapp.net");
        //        sendIntent.setPackage("com.whatsapp");
        //        startActivity(sendIntent);
        //        Intent sendIntent = new Intent("android.intent.action.SEND");
        //        sendIntent.setComponent(new ComponentName("com.whatsapp","com.whatsapp.ContactPicker"));
        //        sendIntent.putExtra("jid", contact.getPrimaryNumber() + "@s.whatsapp.net");
        //        sendIntent.putExtra(Intent.EXTRA_TEXT,"sample text you want to send along with the image");
        //        startActivity(sendIntent);

        val uri = Uri.parse("smsto:" + contact.primaryNumber)
        val intent = Intent(Intent.ACTION_SENDTO, uri)
        intent.putExtra("sms_body", resources.getString(R.string.invite_to_rooster_message))
        startActivity(intent)
        FA.Log(FA.Event.invitation_to_join_rooster_sent::class.java, null, null)
    }

    companion object {
        val TAG: String = FriendsFragmentActivity::class.java.simpleName
    }
}
