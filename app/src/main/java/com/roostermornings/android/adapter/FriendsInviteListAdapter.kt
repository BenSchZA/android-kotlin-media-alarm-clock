/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.adapter

import android.content.Context
import android.os.Handler
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.roostermornings.android.R
import com.roostermornings.android.activity.FriendsFragmentActivity
import com.roostermornings.android.domain.local.Contact
import com.roostermornings.android.domain.local.Friend
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.keys.ViewType
import com.roostermornings.android.util.RoosterUtils
import com.roostermornings.android.util.Toaster
import java.util.*

/**
 * Created by bscholtz on 06/03/17.
 */

class FriendsInviteListAdapter// Provide a suitable constructor (depends on the kind of dataset)
(private var mDataset: ArrayList<Any>?) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), Filterable, FriendsFragmentActivity.FriendsInviteListAdapterInterface {
    private var context: Context? = null

    // Provide a reference to the views for each data item
    // Complex data items may need more than one activityContentView per item, and
    // you provide access to all the views for a data item in a activityContentView holder
    inner class AddViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        // each data item is just a string in this case
        var txtName: TextView = itemView.findViewById(R.id.my_friends_profile_name)
        var txtInitials: TextView = itemView.findViewById(R.id.txtInitials)
        var btnAdd: Button = itemView.findViewById(R.id.friends_add_button)
    }

    inner class InviteViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        // each data item is just a string in this case
        var txtName: TextView = itemView.findViewById(R.id.my_friends_profile_name)
        var txtInitials: TextView = itemView.findViewById(R.id.txtInitials)
        var btnAdd: Button = itemView.findViewById(R.id.friends_add_button)
    }

    inner class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        var txtHeader: TextView = itemView.findViewById(R.id.list_header)
    }

    override fun getItemViewType(position: Int): Int {
        val item = mDataset?.get(position)
        return when (item) {
            is Friend -> ViewType.FRIENDS_ADD.ordinal
            is Contact -> ViewType.FRIENDS_INVITE.ordinal
            is String -> ViewType.FRIENDS_HEADER.ordinal
            else -> ViewType.UNKNOWN.ordinal
        }
    }

    fun add(position: Int, item: Any) {
        mDataset?.add(position, item)?.let {
            notifyItemInserted(position)
        }
    }

    fun refreshAll(myDataset: ArrayList<Any>) {
        mDataset = myDataset
        notifyDataSetChanged()
    }

    fun remove(position: Int, item: Any) {
        mDataset?.remove(item)?.let {
            notifyItemRemoved(position)
        }
    }

    // Create new views (invoked by the layout manager)
    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): RecyclerView.ViewHolder {
        context = parent.context
        val v: View

        return when (viewType) {
            ViewType.FRIENDS_ADD.ordinal -> {
                v = LayoutInflater
                        .from(parent.context)
                        .inflate(R.layout.list_layout_friends_invite, parent, false)
                AddViewHolder(v)
            }
            ViewType.FRIENDS_INVITE.ordinal -> {
                v = LayoutInflater
                        .from(parent.context)
                        .inflate(R.layout.list_layout_friends_invite, parent, false)
                InviteViewHolder(v)
            }
            ViewType.FRIENDS_HEADER.ordinal -> {
                v = LayoutInflater
                        .from(parent.context)
                        .inflate(R.layout.list_header, parent, false)
                HeaderViewHolder(v)
            }
            else -> {
                v = LayoutInflater
                        .from(parent.context)
                        .inflate(R.layout.list_header, parent, false)
                HeaderViewHolder(v)
            }
        }
    }

    // Replace the contents of a activityContentView (invoked by the layout manager)
    override fun onBindViewHolder(objectHolder: RecyclerView.ViewHolder, position: Int) {
        // - get element from your dataset at this position
        // - replace the contents of the activityContentView with that element

        when (objectHolder) {
            is AddViewHolder -> {
                val user = mDataset?.get(position) as Friend? ?: return
                user.selected = false
                objectHolder.txtName.text = user.getUser_name()
                objectHolder.txtInitials.text = RoosterUtils.getInitials(user.getUser_name())
                objectHolder.btnAdd.setOnClickListener {
                    user.selected = !user.selected
                    objectHolder.btnAdd.isSelected = user.selected

                    inviteUser(user)

                    Handler().postDelayed({
                        //Do something after 200ms
                        remove(objectHolder.adapterPosition, user)
                        notifyDataSetChanged()
                    }, 200)
                }
            }
            is InviteViewHolder -> {
                val contact = mDataset?.get(position) as Contact? ?: return
                contact.selected = false
                objectHolder.txtName.text = contact.name
                objectHolder.txtInitials.text = RoosterUtils.getInitials(contact.name)
                objectHolder.btnAdd.setText(R.string.text_friends_button_invite)
                objectHolder.btnAdd.setOnClickListener {
                    contact.selected = !contact.selected
                    objectHolder.btnAdd.isSelected = contact.selected

                    //Invite contact via SMS
                    if (context is FriendsFragmentActivity) {
                        (context as FriendsFragmentActivity).inviteContact(contact)
                    }

                    Handler().postDelayed({
                        //Do something after 200ms
                        remove(objectHolder.adapterPosition, contact)
                        notifyDataSetChanged()
                    }, 200)
                }
            }
            is HeaderViewHolder -> {
                val title = mDataset?.get(position) as String?
                objectHolder.txtHeader.text = title
                objectHolder.txtHeader.visibility = View.VISIBLE
            }
        }
    }

    // Return the size of your dataset (invoked by the layout manager)
    override fun getItemCount(): Int {
        return mDataset?.size ?: -1
    }

    override fun getFilter(): Filter {

        return object : Filter() {

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                mDataset = results.values as? ArrayList<Any>
                notifyDataSetChanged()
            }

            override fun performFiltering(constraint: CharSequence): FilterResults {
                val results = FilterResults()
                val filteredContacts = ArrayList<Any>()

                // Perform your search here using the search constraint string
                val mConstraint = constraint.toString().toLowerCase()
                mDataset?.indices?.forEach {
                    val contactData: String
                    val tempObject = mDataset?.get(it)
                    contactData = when (tempObject) {
                        is Friend -> tempObject.user_name
                        is Contact -> tempObject.name
                        else -> ""
                    }

                    if (contactData.toLowerCase().contains(mConstraint)) {
                        mDataset?.get(it)?.let { filteredContacts.add(it) }
                    }
                }

                results.count = filteredContacts.size
                results.values = filteredContacts

                return results
            }
        }
    }

    override fun inviteUser(friend: Friend) {
        FirebaseNetwork.inviteFriend(friend)
        Toaster.makeToast(context, friend.getUser_name() + " invited!", Toast.LENGTH_LONG).checkTastyToast()
    }
}
