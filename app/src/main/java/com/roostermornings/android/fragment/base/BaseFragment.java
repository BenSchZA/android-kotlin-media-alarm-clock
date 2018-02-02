/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment.base;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.mobsandgeeks.saripaar.ValidationError;
import com.mobsandgeeks.saripaar.Validator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.inject.Inject;

import com.roostermornings.android.BaseApplication;
import com.roostermornings.android.activity.MyAlarmsFragmentActivity;
import com.roostermornings.android.apis.GoogleIHTTPClient;
import com.roostermornings.android.dagger.RoosterApplicationComponent;
import com.roostermornings.android.domain.local.Contact;
import com.roostermornings.android.domain.local.Friend;
import com.roostermornings.android.domain.database.SocialRooster;
import com.roostermornings.android.domain.database.User;
import com.roostermornings.android.apis.NodeIHTTPClient;
import com.roostermornings.android.sqlutil.DeviceAudioQueueItem;

import butterknife.ButterKnife;

public abstract class BaseFragment extends Fragment implements Validator.ValidationListener {

    protected DatabaseReference mDatabase;

    @Inject Context AppContext;
    @Inject BaseApplication baseApplication;

    public static BaseActivityListener baseActivityListener;

    protected abstract void inject(RoosterApplicationComponent component);

    public interface BaseActivityListener {
        void onValidationSucceeded();
        void onValidationFailed(List<ValidationError> errors);
        boolean checkInternetConnection();
        NodeIHTTPClient getNodeApiService();
        GoogleIHTTPClient getGoogleApiService();
    }

    public boolean checkInternetConnection() {
        return baseActivityListener.checkInternetConnection();
    }

    public NodeIHTTPClient nodeApiService() {
        return baseActivityListener.getNodeApiService();
    }

    public GoogleIHTTPClient googleApiService() {
        return baseActivityListener.getGoogleApiService();
    }

    @Override
    public void onValidationSucceeded() {
        baseActivityListener.onValidationSucceeded();
    }

    @Override
    public void onValidationFailed(List<ValidationError> errors) {
        baseActivityListener.onValidationFailed(errors);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BaseApplication.Companion.getRoosterApplicationComponent().inject(this);

        getDatabaseReference();

        try {
            baseActivityListener = (BaseActivityListener) getActivity();
        } catch (ClassCastException castException) {
            /* The activity does not implement the listener. */
        }
    }

    protected View initiate(LayoutInflater inflater, int resource, ViewGroup root, boolean attachToRoot){
        View view = inflater.inflate(resource, root, attachToRoot);

        ButterKnife.bind(this, view);
        return view;
    }

    protected DatabaseReference getDatabaseReference() {
        mDatabase = FirebaseDatabase.getInstance().getReference();
        return mDatabase;
    }

    public void sortNamesFriends(ArrayList<Friend> mUsers){
        //Take arraylist and sort alphabetically
        Collections.sort(mUsers, new Comparator<Friend>() {
            @Override
            public int compare(Friend lhs, Friend rhs) {
                //If null, pretend equal
                if(lhs == null || rhs == null || lhs.getUser_name() == null || rhs.getUser_name() == null) return 0;
                return lhs.getUser_name().compareTo(rhs.getUser_name());
            }
        });
    }

    public void sortNamesUsers(ArrayList<User> mUsers){
        //Take arraylist and sort alphabetically
        Collections.sort(mUsers, new Comparator<User>() {
            @Override
            public int compare(User lhs, User rhs) {
                //If null, pretend equal
                if(lhs == null || rhs == null || lhs.getUser_name() == null || rhs.getUser_name() == null) return 0;
                return lhs.getUser_name().compareTo(rhs.getUser_name());
            }
        });
    }

    public void sortNamesContacts(ArrayList<Contact> contacts) {
        //Take arraylist and sort alphabetically
        Collections.sort(contacts, new Comparator<Contact>() {
            @Override
            public int compare(Contact lhs, Contact rhs) {
                //If null, pretend equal
                if(lhs == null || rhs == null || lhs.getName() == null || rhs.getName() == null) return 0;
                return lhs.getName().compareTo(rhs.getName());
            }
        });
    }

    public void sortSocialRoosters(ArrayList<SocialRooster> socialRoosters){
        //Take arraylist and sort by date
        Collections.sort(socialRoosters, new Comparator<SocialRooster>() {
            @Override
            public int compare(SocialRooster lhs, SocialRooster rhs) {
                //If null, pretend equal
                if(lhs == null || rhs == null || lhs.getDate_uploaded() == null || rhs.getDate_uploaded() == null) return 0;
                return rhs.getDate_uploaded().compareTo(lhs.getDate_uploaded());
            }
        });
    }

    public void sortDeviceAudioQueueItems(ArrayList<DeviceAudioQueueItem> socialRoosters){
        //Take arraylist and sort by date
        Collections.sort(socialRoosters, new Comparator<DeviceAudioQueueItem>() {
            @Override
            public int compare(DeviceAudioQueueItem lhs, DeviceAudioQueueItem rhs) {
                //If null, pretend equal
                if(lhs == null || rhs == null || lhs.getDate_uploaded() == null || rhs.getDate_uploaded() == null) return 0;
                return rhs.getDate_uploaded().compareTo(lhs.getDate_uploaded());
            }
        });
    }

    protected void startHomeActivity() {
        Intent homeIntent = new Intent(AppContext, MyAlarmsFragmentActivity.class);
        startActivity(homeIntent);
    }
}
