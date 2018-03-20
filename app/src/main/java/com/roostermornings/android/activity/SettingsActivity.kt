/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.activity

import android.os.Bundle
import android.widget.TextView
import butterknife.BindView
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.activity.base.BaseActivity
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.fragment.SettingsFragment

class SettingsActivity : BaseActivity() {

    @BindView(R.id.toolbar_title)
    lateinit var toolbarTitle: TextView

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize(R.layout.custom_preference_screen_layout)
        BaseApplication.roosterApplicationComponent.inject(this)

        //setDayNightTheme();

        //Display the fragment as the main content
        fragmentManager.beginTransaction()
                .add(R.id.main_content, SettingsFragment())
                .commit()

        //Set toolbar title
        val toolbar = setupToolbar(toolbarTitle, getString(R.string.settings_activity_title))
        toolbar?.setNavigationIcon(R.drawable.md_nav_back)
        toolbar?.setNavigationOnClickListener { startHomeActivity() }
    }
}
