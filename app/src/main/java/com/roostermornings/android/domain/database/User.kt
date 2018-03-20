/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.domain.database

import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties
import java.io.Serializable
import java.util.*

@IgnoreExtraProperties
class User : Serializable {

    var channels: HashMap<String, Boolean>? = HashMap()
    var device_type :String = ""
    var device_token :String = ""
    var profile_pic :String = ""
    var user_name :String = ""
    var cell_number :String = ""
    var friends: HashMap<String, Any> = HashMap()
    var uid :String = ""
    var unseen_roosters :Int = 0
    var geohash_location :String = ""

    @Exclude
    @get:Exclude
    @set:Exclude
    var selected = false //this is important for list of friends that need to be selected eg for creating a new alarm

    // Required default constructor for Firebase object mapping
    constructor() {}

    constructor(channels: HashMap<String, Boolean>?,
                device_type: String?,
                device_token: String?,
                profile_pic: String?,
                user_name: String?,
                cell_number: String?,
                uid: String?,
                friends: HashMap<String, Any>?,
                unseen_roosters: Int?,
                geohash_location: String?) {

        this.channels = channels?:HashMap()
        this.cell_number = cell_number?:""
        this.device_token = device_token?:""
        this.device_type = device_type?:""
        this.profile_pic = profile_pic?:""
        this.user_name = user_name?:""
        this.uid = uid?:""
        this.unseen_roosters = unseen_roosters?:0
        this.geohash_location = geohash_location?:""
    }
}
