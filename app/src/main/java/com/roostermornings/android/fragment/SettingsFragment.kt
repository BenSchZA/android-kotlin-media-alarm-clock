/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment

import android.os.Bundle
import android.preference.PreferenceFragment
import com.roostermornings.android.R
import com.roostermornings.android.keys.About
import com.roostermornings.android.util.DetailsUtils

class SettingsFragment : PreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.application_user_settings)

        // Display app, device, and Android details to user
        val versionName = DetailsUtils.appVersionName()
        findPreference(About.APP_VERSION_NAME.name).summary = versionName

        val versionCode = DetailsUtils.appVersionCode()
        findPreference(About.APP_VERSION_CODE.name).summary = versionCode.toString()

        val deviceBrand = DetailsUtils.deviceBrand()
        findPreference(About.DEVICE_BRAND.name).summary = deviceBrand

        val deviceModel = DetailsUtils.deviceModel()
        findPreference(About.DEVICE_MODEL.name).summary = deviceModel

        val androidVersionCodeName = DetailsUtils.androidVersionCodeName()
        findPreference(About.ANDROID_CODE_NAME.name).summary = androidVersionCodeName

        val androidVersionSdkInt = DetailsUtils.androidVersionSdkInt()
        findPreference(About.ANDROID_SDK_INT.name).summary = androidVersionSdkInt.toString()
    }
}