/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment.friends;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.database.DatabaseReference;
import com.roostermornings.android.BuildConfig;
import com.roostermornings.android.R;
import com.roostermornings.android.activity.FriendsFragmentActivity;
import com.roostermornings.android.adapter.FriendsMyListAdapter;
import com.roostermornings.android.domain.Friend;
import com.roostermornings.android.domain.User;
import com.roostermornings.android.domain.Users;
import com.roostermornings.android.fragment.base.BaseFragment;
import com.roostermornings.android.util.Constants;

import java.util.ArrayList;

import butterknife.BindView;
import retrofit.Call;
import retrofit.Callback;
import retrofit.Response;
import retrofit.Retrofit;

import static com.facebook.FacebookSdk.getApplicationContext;

/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * {@link FriendsMyFragment1.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link FriendsMyFragment1#newInstance} factory method to
 * create an instance of this fragment.
 */
public class FriendsMyFragment1 extends BaseFragment {

    protected static final String TAG = FriendsFragmentActivity.class.getSimpleName();

    ArrayList<User> mUsers = new ArrayList<>();
    private DatabaseReference mFriendsReference;
    private DatabaseReference mUserReference;
    private String firebaseIdToken = "";

    private RecyclerView.Adapter mAdapter;

    private static int statusCode = -1;

    @BindView(R.id.progressBar)
    ProgressBar progressBar;

    @BindView(R.id.friendsMyListView)
    RecyclerView mRecyclerView;

    private OnFragmentInteractionListener mListener;

    public FriendsMyFragment1() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     * @return A new instance of fragment FriendsMyFragment1.
     */
    public static FriendsMyFragment1 newInstance(String param1, String param2) {
        FriendsMyFragment1 fragment = new FriendsMyFragment1();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Ensure check for Node complete reset
        statusCode = -1;

        if (getArguments() != null) {
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = initiate(inflater, R.layout.fragment_friends_fragment1, container, false);
        //Check if node response already exists
        if(statusCode == 200) {
            progressBar.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
        } else {
            if (!checkInternetConnection()) {
                progressBar.setVisibility(View.GONE);
            } else {
                progressBar.setVisibility(View.VISIBLE);
                mRecyclerView.setVisibility(View.GONE);
                getFirebaseUser().getToken(true)
                        .addOnCompleteListener(new OnCompleteListener<GetTokenResult>() {
                            public void onComplete(@NonNull Task<GetTokenResult> task) {
                                if (task.isSuccessful()) {
                                    firebaseIdToken = task.getResult().getToken();
                                    retrieveMyFriends();
                                } else {
                                    // Handle error -> task.getException();
                                    progressBar.setVisibility(View.GONE);
                                }
                            }
                        });
            }
        }
        // Inflate the layout for this fragment
        return view;
    }

    //NB: bind ButterKnife to view and then initialise UI elements
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        //Sort names alphabetically before notifying adapter
        sortNamesUsers(mUsers);
        mAdapter = new FriendsMyListAdapter(mUsers, getActivity(), getContext());
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setAdapter(mAdapter);
    }

    private void retrieveMyFriends() {

        if (!checkInternetConnection()) return;

        FirebaseUser firebaseUser = getFirebaseUser();

        if (firebaseUser == null) {
            if(BuildConfig.DEBUG) Log.d(TAG, "User not authenticated on FB!");
            return;
        }

        if("".equals(firebaseIdToken)) {
            Toast.makeText(getApplicationContext(), "Loading friends failed, please try again.", Toast.LENGTH_LONG).show();
            return;
        }
        Call<Users> call = apiService().retrieveUserFriends(firebaseIdToken);

        call.enqueue(new Callback<Users>() {
            @Override
            public void onResponse(Response<Users> response,
                                   Retrofit retrofit) {

                statusCode = response.code();
                Users apiResponse = response.body();

                if (statusCode == 200) {

                    progressBar.setVisibility(View.GONE);
                    mRecyclerView.setVisibility(View.VISIBLE);

                    mUsers.clear();
                    mUsers.addAll(apiResponse.users);

                    sortNamesUsers(mUsers);
                    mAdapter.notifyDataSetChanged();
//TODO:
//                    SharedPreferences.Editor editor =  sharedPreferences.edit();
//                    editor
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Log.i(TAG, t.getLocalizedMessage());
                Toast.makeText(getApplicationContext(), "Loading friends failed, please try again.", Toast.LENGTH_LONG).show();
                progressBar.setVisibility(View.GONE);
            }
        });
    }

//    //Retrieve list of friends from Firebase for current user
//    private void getFriends() {
//        mFriendsReference = mDatabase
//                .child("users").child(getFirebaseUser().getUid()).child("friends");
//        mFriendsReference.keepSynced(true);
//
//        ChildEventListener friendsListener = new ChildEventListener() {
//            @Override
//            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
//                mUsers.add(dataSnapshot.getValue(Friend.class));
//                //Sort names alphabetically before notifying adapter
//                sortNamesFriends(mUsers);
//                mAdapter.notifyDataSetChanged();
//            }
//
//            @Override
//            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
//                Friend friend = dataSnapshot.getValue(Friend.class);
//                Friend friendRemove = null;
//                for (Friend oldUser:mUsers) {
//                    if(oldUser.getUid().equals(friend.getUid())){
//                        friendRemove = oldUser;
//                    }
//                }
//                if(friendRemove != null) {
//                    mUsers.remove(friendRemove);
//                    mUsers.add(friend);
//                    //Sort names alphabetically before notifying adapter
//                    sortNamesFriends(mUsers);
//                    mAdapter.notifyDataSetChanged();
//                }
//            }
//
//            @Override
//            public void onChildRemoved(DataSnapshot dataSnapshot) {
//                mUsers.remove(dataSnapshot.getValue(Friend.class));
//                //Sort names alphabetically before notifying adapter
//                sortNamesFriends(mUsers);
//                mAdapter.notifyDataSetChanged();
//            }
//
//            @Override
//            public void onChildMoved(DataSnapshot dataSnapshot, String s) {
//
//            }
//
//            @Override
//            public void onCancelled(DatabaseError databaseError) {
//                Log.w(TAG, "loadPost:onCancelled", databaseError.toException());
//                showToast(getContext(), "Failed to load user.", Toast.LENGTH_SHORT);
//            }
//        };
//        mFriendsReference.addChildEventListener(friendsListener);
//    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        getDatabaseReference();

        if (context instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement OnFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    public void searchRecyclerViewAdapter(String query) {
        //Filter contacts by CharSequence
        //Get reference to list adapter to access getFilter method
        ((FriendsMyListAdapter)mAdapter).refreshAll(mUsers);
        ((FriendsMyListAdapter)mAdapter).getFilter().filter(query);
    }

    public void notifyAdapter() {
        ((FriendsMyListAdapter)mAdapter).refreshAll(mUsers);
        mAdapter.notifyDataSetChanged();
    }

    //mListener.onFragmentInteraction(uri);

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnFragmentInteractionListener {
        // TODO: Update argument type and name
        void onFragmentInteraction(Uri uri);
    }
}
