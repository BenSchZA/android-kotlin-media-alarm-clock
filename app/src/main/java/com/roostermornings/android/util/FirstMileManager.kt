/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.util

import android.app.Activity
import android.support.v4.content.res.ResourcesCompat
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.github.amlcurran.showcaseview.MaterialShowcaseDrawer
import com.github.amlcurran.showcaseview.ShowcaseView
import com.github.amlcurran.showcaseview.targets.Target
import com.roostermornings.android.R

/**
 * com.roostermornings.android.util
 * Rooster Mornings Android
 *
 * Created by bscholtz on 17/10/17.
 * Copyright © 2017 Roosta Media. All rights reserved.
 */
class FirstMileManager {

    fun createShowcase(activity: Activity, target: Target, id: Int) {
        val showcaseDrawer = MaterialShowcaseDrawer(activity.resources)
        showcaseDrawer.setBackgroundColour(ResourcesCompat.getColor(activity.resources, R.color.rooster_dark_blue, null))

        val showcaseView = ShowcaseView.Builder(activity)
                .setTarget(target)
                .setContentTitle("ShowcaseView")
                .setContentText("This is highlighting the Home button")
                //.singleShot(id.toLong())
                .withMaterialShowcase()
                .setStyle(R.style.RoosterShowcaseTheme)
                .setShowcaseDrawer(showcaseDrawer)
                .build()

        val layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
        val margin = ((activity.resources.displayMetrics.density * 12) as Number).toInt()
        layoutParams.setMargins(margin, margin, margin, margin)

        showcaseView.setButtonPosition(layoutParams)
    }

}