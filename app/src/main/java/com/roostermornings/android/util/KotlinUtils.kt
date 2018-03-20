/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.util

import com.crashlytics.android.Crashlytics

/**
 * com.roostermornings.android.util
 * Rooster Mornings Android
 *
 * Created by bscholtz on 29/08/17.
 * Copyright © 2017 Roosta Media. All rights reserved.
 */

class KotlinUtils {
    companion object {
        inline fun catchAll(log: Boolean, action: () -> Unit) {
            try {
                action()
            } catch (t: Throwable) {
                if(log) Crashlytics.logException(t)
            }
        }
    }
}