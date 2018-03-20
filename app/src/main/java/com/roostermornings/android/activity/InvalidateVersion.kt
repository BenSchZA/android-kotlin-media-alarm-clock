/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import butterknife.BindView
import butterknife.OnClick
import com.roostermornings.android.R
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.util.Constants

class InvalidateVersion : BaseActivity() {

    @BindView(R.id.update_title)
    lateinit var updateTitle: TextView
    @BindView(R.id.update_description)
    lateinit var updateDescription: TextView

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(R.layout.activity_invalidate_version)

        if (intent.extras != null) {
            updateTitle.text = if (intent.getStringExtra(Constants.FORCE_UPDATE_TITLE).isEmpty())
                "Update Required"
            else
                intent.getStringExtra(Constants.FORCE_UPDATE_TITLE)
            updateDescription.text = if (intent.getStringExtra(Constants.FORCE_UPDATE_DESCRIPTION).isEmpty())
                "You have an old version of Rooster, you need to update. But don't worry, the update brings new features!"
            else
                intent.getStringExtra(Constants.FORCE_UPDATE_DESCRIPTION)
        }
    }

    @OnClick(R.id.update_button)
    fun onUpdateButtonClick() {
        val appPackageName = packageName // getPackageName() from Context or Activity object
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appPackageName)))
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + appPackageName)))
        }

    }
}
