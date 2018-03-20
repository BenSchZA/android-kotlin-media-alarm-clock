/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.keys.Action
import com.roostermornings.android.keys.Flag

class FirebaseListenerService : Service() {

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val firebaseUser = FirebaseAuth.getInstance().currentUser

        if (!mRunning) {
            mRunning = true

            // Reset flags - NB onChildChanged called once for each child when first called
            BaseApplication.setNotificationFlag(0, Flag.FRIEND_REQUESTS.name)

            // Listen for changes to Firebase user friend requests, display notification
            if (firebaseUser != null) {
                val mDatabase = FirebaseDatabase.getInstance().reference

                val mRequestsReference = mDatabase
                        .child("friend_requests_received").child(firebaseUser.uid)

                val friendRequestListener = object : ChildEventListener {
                    override fun onChildAdded(dataSnapshot: DataSnapshot, s: String?) {
                        // Send broadcast message to notify all receivers of new notification
                        sendBroadcast(Intent(Action.REQUEST_NOTIFICATION.name))
                    }
                    override fun onChildChanged(dataSnapshot: DataSnapshot, s: String?) {}
                    override fun onChildRemoved(dataSnapshot: DataSnapshot) {}
                    override fun onChildMoved(dataSnapshot: DataSnapshot, s: String?) {}
                    override fun onCancelled(databaseError: DatabaseError) {}
                }
                mRequestsReference.addChildEventListener(friendRequestListener)

                //TODO: implement badge count
                //            DatabaseReference mRoosterReference = mDatabase
                //                    .child("social_rooster_queue").child(getFirebaseUser().getUid());
                //
                //            ChildEventListener roosterListener = new ChildEventListener() {
                //                @Override
                //                public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                //                    BaseActivity.setBadge(getApplicationContext(), ((BaseApplication)getApplication()).getNotificationFlag(Constants.FLAG_ROOSTERCOUNT));
                //                }
                //
                //                @Override
                //                public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                //                    BaseActivity.setBadge(getApplicationContext(), ((BaseApplication)getApplication()).getNotificationFlag(Constants.FLAG_ROOSTERCOUNT));
                //                }
                //
                //                @Override
                //                public void onChildRemoved(DataSnapshot dataSnapshot) {
                //                    BaseActivity.setBadge(getApplicationContext(), ((BaseApplication)getApplication()).getNotificationFlag(Constants.FLAG_ROOSTERCOUNT));
                //                }
                //
                //                @Override
                //                public void onChildMoved(DataSnapshot dataSnapshot, String s) {
                //                    BaseActivity.setBadge(getApplicationContext(), ((BaseApplication)getApplication()).getNotificationFlag(Constants.FLAG_ROOSTERCOUNT));
                //                }
                //
                //                @Override
                //                public void onCancelled(DatabaseError databaseError) {
                //
                //                }
                //            };
                //            mRoosterReference.addChildEventListener(roosterListener);
            }
        }

        return Service.START_STICKY
    }

    companion object {
        var mRunning = false
    }
}
