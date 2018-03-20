/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.domain.local

import com.google.firebase.database.IgnoreExtraProperties
import java.io.Serializable

/**
 * com.roostermornings.android.domain
 * Rooster Mornings Android
 *
 * Created by bscholtz on 30/08/17.
 * Copyright © 2017 Roosta Media. All rights reserved.
 */
@IgnoreExtraProperties
class GeoHashChannel : Serializable {
    var g: ArrayList<String> = ArrayList()
    var l: ArrayList<Double> = ArrayList()
    var rad: Int = -1
    var uid: String = ""

    // Required default constructor for Firebase object mapping
    constructor()

    constructor(g: ArrayList<String>, l: ArrayList<Double>, rad: Int, uid: String) {
        this.g = g
        this.l = l
        this.rad = rad
        this.uid = uid
    }
}