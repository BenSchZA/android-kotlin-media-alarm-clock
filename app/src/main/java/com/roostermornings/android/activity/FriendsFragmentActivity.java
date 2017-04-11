/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.roostermornings.android.BaseApplication;
import com.roostermornings.android.R;
import com.roostermornings.android.activity.base.BaseActivity;
import com.roostermornings.android.domain.Friend;
import com.roostermornings.android.domain.User;
import com.roostermornings.android.fragment.friends.FriendsInviteFragment3;
import com.roostermornings.android.fragment.friends.FriendsMyFragment1;
import com.roostermornings.android.fragment.friends.FriendsRequestFragment2;
import com.roostermornings.android.util.Constants;
import com.roostermornings.android.util.FontsOverride;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.OnClick;

//Responsible for managing friends: 1) my friends, 2) addable friends, 3) friend invites
public class FriendsFragmentActivity extends BaseActivity implements
        FriendsMyFragment1.OnFragmentInteractionListener,
        FriendsRequestFragment2.OnFragmentInteractionListener,
        FriendsInviteFragment3.OnFragmentInteractionListener {

    public static final String TAG = FriendsFragmentActivity.class.getSimpleName();
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.toolbar_title)
    TextView toolbarTitle;
    @BindView(R.id.tabs)
    TabLayout tabLayout;
    @BindView(R.id.home_friends)
    ImageButton buttonMyFriends;
    @BindView(R.id.button_bar)
    LinearLayout buttonBarLayout;
    /**
     * The {@link ViewPager} that will host the section contents.
     */
    @BindView(R.id.main_content)
    ViewPager mViewPager;
    /**
     * The {@link android.support.v4.view.PagerAdapter} that will provide
     * fragments for each of the sections. We use a
     * {@link FragmentPagerAdapter} derivative, which will keep every
     * loaded fragment in memory. If this becomes too memory intensive, it
     * may be best to switch to a
     * {@link android.support.v4.app.FragmentStatePagerAdapter}.
     */
    private SectionsPagerAdapter mSectionsPagerAdapter;
    private DatabaseReference mFriendRequestsReceivedReference;
    private DatabaseReference mFriendRequestsSentReference;
    private DatabaseReference mCurrentUserReference;
    private BroadcastReceiver receiver;

    private int position;
    protected FriendsFragmentActivity mThis = this;

    FriendsMyFragment1 friendsInviteFragment1;
    FriendsRequestFragment2 friendsInviteFragment2;
    FriendsInviteFragment3 friendsInviteFragment3;

    public FriendsFragmentActivity() {

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initialize(R.layout.activity_friends);

        setDayNight();

        //Set toolbar title
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        toolbarTitle.setText(getString(R.string.friends));

        //Keep local and Firebase alarm dbs synced, and enable offline persistence
        mFriendRequestsReceivedReference = FirebaseDatabase.getInstance().getReference()
                .child("friend_requests_received").child(getFirebaseUser().getUid());

        mFriendRequestsSentReference = FirebaseDatabase.getInstance().getReference()
                .child("friend_requests_sent").child(getFirebaseUser().getUid());

        mCurrentUserReference = FirebaseDatabase.getInstance().getReference()
                .child("users").child(getFirebaseUser().getUid());

        mFriendRequestsReceivedReference.keepSynced(true);
        mFriendRequestsSentReference.keepSynced(true);
        mCurrentUserReference.keepSynced(true);

        //Create a viewpager with fragments controlled by SectionsPagerAdapter
        createViewPager(mViewPager);
        tabLayout.setupWithViewPager(mViewPager);
        //Generate custom tab for tab layout
        createTabIcons();

        //Check for new Firebase datachange notifications and register broadcast receiver
        updateNotifications();

        //Listen for change to mViewPager page display - used for toggling notifications
        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }

            @Override
            public void onPageSelected(int position) {
                if (position == 1) {
                    setTabNotification(position, false);
                    setButtonBarNotification(false);
                    ((BaseApplication) getApplication()).setNotificationFlag(0, Constants.FLAG_FRIENDREQUESTS);
                } else if(position == 2) {
                    friendsInviteFragment3.requestGetContacts();
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });

        FontsOverride.changeTabsFont(getApplicationContext(), tabLayout, Constants.APP_FONT);

        //Handle search intent
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        //mViewPager.setCurrentItem(sharedPreferences.getInt(Constants.FRIENDS_ACTIVITY_CURRENT_FRAGMENT, 0));
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            //use the query to search your data somehow
            handleSearch(query);
        }
    }

    private void handleSearch(String query) {
        if(mViewPager.getCurrentItem() == 0) {
            friendsInviteFragment1.searchRecyclerViewAdapter(query);
        } else if(mViewPager.getCurrentItem() == 1) {
            friendsInviteFragment2.searchRecyclerViewAdapter(query);
        } else if(mViewPager.getCurrentItem() == 2) {
            friendsInviteFragment3.searchRecyclerViewAdapter(query);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_friends, menu);

        // Associate searchable configuration with the SearchView
        SearchManager searchManager =
                (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        SearchView searchView =
                (SearchView) menu.findItem(R.id.search).getActionView();
        searchView.setSearchableInfo(
                searchManager.getSearchableInfo(getComponentName()));

        //When searchView is closed, refresh data
        searchView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {

            @Override
            public void onViewDetachedFromWindow(View arg0) {
                // search was detached/closed
                if(mViewPager.getCurrentItem() == 0) {
                    friendsInviteFragment1.notifyAdapter();
                } else if(mViewPager.getCurrentItem() == 1) {
                    friendsInviteFragment2.notifyAdapter();
                } else if(mViewPager.getCurrentItem() == 2) {
                    friendsInviteFragment3.notifyAdapter();
                }
            }

            @Override
            public void onViewAttachedToWindow(View arg0) {
                // search was opened
            }
        });

        return true;
    }

    @OnClick(R.id.home_record_audio)
    public void recordNewAudio() {
        startActivity(new Intent(FriendsFragmentActivity.this, NewAudioRecordActivity.class));
    }

    @OnClick(R.id.home_my_alarms)
    public void manageAlarms() {
        startHomeActivity();
    }

    @OnClick(R.id.home_my_uploads)
    public void manageUploads() {
        startActivity(new Intent(FriendsFragmentActivity.this, MessageStatusActivity.class));
    }

    private void createViewPager(ViewPager mViewPager) {

        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.
        mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

        mSectionsPagerAdapter.addFrag(Fragment.instantiate(getApplicationContext(), FriendsMyFragment1.class.getName()), "FRIENDS");
        mSectionsPagerAdapter.addFrag(Fragment.instantiate(getApplicationContext(), FriendsRequestFragment2.class.getName()), "REQUESTS");
        mSectionsPagerAdapter.addFrag(Fragment.instantiate(getApplicationContext(), FriendsInviteFragment3.class.getName()), "INVITE");

        friendsInviteFragment1 = (FriendsMyFragment1) mSectionsPagerAdapter.getItem(0);
        friendsInviteFragment2 = (FriendsRequestFragment2) mSectionsPagerAdapter.getItem(1);
        friendsInviteFragment3 = (FriendsInviteFragment3) mSectionsPagerAdapter.getItem(2);

        // Set up the ViewPager with the sections adapter.
        mViewPager.setAdapter(mSectionsPagerAdapter);
    }

    /**
     * A {@link FragmentPagerAdapter} that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    public class SectionsPagerAdapter extends FragmentPagerAdapter {

        private final List<Fragment> mFragmentList = new ArrayList<>();
        private final List<String> mFragmentTitleList = new ArrayList<>();

        public SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            return mFragmentList.get(position);
        }

        @Override
        public int getCount() {
            return mFragmentList.size();
        }

        public void addFrag(Fragment fragment, String title) {
            mFragmentList.add(fragment);
            mFragmentTitleList.add(title);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return mFragmentTitleList.get(position);
        }
    }

    private void createTabIcons() {

        setTabLayout(0, "FRIENDS");
        setTabLayout(1, "REQUESTS");
        setTabLayout(2, "INVITE");
    }

    //Create custom tab layout
    public void setTabLayout(int position, String title) {
        FrameLayout frameLayout = (FrameLayout) LayoutInflater.from(this).inflate(R.layout.custom_friends_tab, null);
        TextView tabText = (TextView) frameLayout.getChildAt(0);
        tabText.setText(title);
        //Disable clipping to ensure notification is shown properly
        ViewGroup tabs = ((ViewGroup)tabLayout.getChildAt(0));
        tabs.setClipToPadding(false);
        tabs.setClipChildren(false);
        if(tabs.getChildAt(position) instanceof ViewGroup) {
            ((ViewGroup) tabs.getChildAt(position)).setClipToPadding(false);
            ((ViewGroup) tabs.getChildAt(position)).setClipChildren(false);
        }

        tabLayout.getTabAt(position).setCustomView(frameLayout);
    }

    //Set current tab notification
    public void setTabNotification(int position, boolean notification) {
        TabLayout.Tab tab = tabLayout.getTabAt(position);
        FrameLayout frameLayout = (FrameLayout) tab.getCustomView();
        ImageView tabNotification = (ImageView) tab.getCustomView().findViewById(R.id.notification);
        if (notification) tabNotification.setVisibility(View.VISIBLE);
        else tabNotification.setVisibility(View.GONE);
        tab.setCustomView(frameLayout);
    }

    public void setButtonBarNotification(boolean notification) {
        ImageView buttonBarNotification = (ImageView) buttonBarLayout.findViewById(R.id.notification);
        if (notification) buttonBarNotification.setVisibility(View.VISIBLE);
        else buttonBarNotification.setVisibility(View.GONE);
    }

    private void updateNotifications() {
        //Flag check for UI changes on load, broadcastreceiver for changes while activity running
        //If notifications waiting, display new friend request notification
        if (((BaseApplication) getApplication()).getNotificationFlag(Constants.FLAG_FRIENDREQUESTS) > 0) {
            setButtonBarNotification(true);
            setTabNotification(1, true);
        }

        //Broadcast receiver filter to receive UI updates
        IntentFilter firebaseListenerServiceFilter = new IntentFilter();
        firebaseListenerServiceFilter.addAction(Constants.ACTION_REQUESTNOTIFICATION);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                //do something based on the intent's action
                if (((BaseApplication) getApplication()).getNotificationFlag(Constants.FLAG_FRIENDREQUESTS) > 0) {
                    setButtonBarNotification(true);
                    setTabNotification(1, true);
                }
            }
        };
        registerReceiver(receiver, firebaseListenerServiceFilter);
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        super.onDestroy();
    }

    public int getTabNotification(int position) {
        TabLayout.Tab tab = tabLayout.getTabAt(position);
        ImageView imageNotification = (ImageView) tab.getCustomView().findViewById(R.id.notification);
        return imageNotification.getVisibility();
    }

    public void onFragmentInteraction(Uri uri) {
        //you can leave it empty
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case Constants.MY_PERMISSIONS_REQUEST_READ_CONTACTS: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.

                } else {
                    //TODO: add button to ask for permission again, display blank fragment with explainer
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    //Send invite to Rooster user from contact list
    public void inviteUser(Friend inviteFriend) {

        String inviteUrl = String.format("friend_requests_received/%s/%s", inviteFriend.getUid(), mCurrentUser.getUid());
        String currentUserUrl = String.format("friend_requests_sent/%s/%s", mCurrentUser.getUid(), inviteFriend.getUid());

        //Create friend object from current signed in user
        Friend currentUserFriend = new Friend(mCurrentUser.getUid(), mCurrentUser.getUser_name(), mCurrentUser.getProfile_pic(), mCurrentUser.getCell_number());

        //Append to received and sent request list
        mDatabase.getDatabase().getReference(inviteUrl).setValue(currentUserFriend);
        mDatabase.getDatabase().getReference(currentUserUrl).setValue(inviteFriend);

        Toast.makeText(this, inviteFriend.getUser_name() + " invited!", Toast.LENGTH_LONG).show();
    }

    //Delete friend from Firebase user friend list
    public void deleteFriend(User deleteFriend) {

        String currentUserUrl = String.format("users/%s/friends/%s", mCurrentUser.getUid(), deleteFriend.getUid());
        String friendUserUrl = String.format("users/%s/friends/%s", deleteFriend.getUid(), mCurrentUser.getUid());

        //Clear current user's and friend's friend list
        mDatabase.getDatabase().getReference(currentUserUrl).setValue(null);
        mDatabase.getDatabase().getReference(friendUserUrl).setValue(null);
    }

    //Accept friend request and update Firebase DB
    public void acceptFriendRequest(Friend acceptFriend) {

        String currentUserUrl = String.format("users/%s/friends/%s", mCurrentUser.getUid(), acceptFriend.getUid());
        String friendUserUrl = String.format("users/%s/friends/%s", acceptFriend.getUid(), mCurrentUser.getUid());

        //Create friend object from current signed in user
        Friend currentUserFriend = new Friend(mCurrentUser.getUid(), mCurrentUser.getUser_name(), mCurrentUser.getProfile_pic(), mCurrentUser.getCell_number());

        mDatabase.getDatabase().getReference(currentUserUrl).setValue(acceptFriend);
        mDatabase.getDatabase().getReference(friendUserUrl).setValue(currentUserFriend);

        String receivedUrl = String.format("friend_requests_received/%s/%s", mCurrentUser.getUid(), acceptFriend.getUid());
        String sentUrl = String.format("friend_requests_sent/%s/%s", acceptFriend.getUid(), mCurrentUser.getUid());

        //Clear received and sent request list
        mDatabase.getDatabase().getReference(receivedUrl).setValue(null);
        mDatabase.getDatabase().getReference(sentUrl).setValue(null);

        //Notify user that friend request accepted
        Toast.makeText(this, acceptFriend.getUser_name() + "'s friend request accepted!", Toast.LENGTH_LONG).show();
    }

    public void rejectFriendRequest(Friend rejectFriend) {

        String receivedUrl = String.format("friend_requests_received/%s/%s", mCurrentUser.getUid(), rejectFriend.getUid());
        String sentUrl = String.format("friend_requests_sent/%s/%s", rejectFriend.getUid(), mCurrentUser.getUid());

        //Clear received and sent request list
        mDatabase.getDatabase().getReference(receivedUrl).setValue(null);
        mDatabase.getDatabase().getReference(sentUrl).setValue(null);

        //Notify user that friend request accepted
        Toast.makeText(this, rejectFriend.getUser_name() + "'s friend request rejected!", Toast.LENGTH_LONG).show();
    }
}
