package com.roostermornings.android.custom_ui

import android.content.Context
import android.support.v4.view.ViewPager
import android.util.AttributeSet
import android.view.MotionEvent

/**
 * Created by bscholtz on 2017/12/14.
 */

class LockableViewPager : ViewPager {

    var isPagingEnabled = true
    var isRightScrollEnabled = true
    var isLeftScrollEnabled = true

    private var lastDirection = Direction.NONE
    enum class Direction {
        NONE, RIGHT, LEFT
    }

    private var mStartDragX: Float = 0f

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if(isSwipeAllowed(event)) {
            performClick()
            isPagingEnabled && super.onTouchEvent(event)
        } else false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return if(isSwipeAllowed(event)) {
            isPagingEnabled && super.onInterceptTouchEvent(event)
        } else false
    }

    private fun isSwipeAllowed(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mStartDragX = event.x
                true
            }
            MotionEvent.ACTION_MOVE -> {
                when {
                    mStartDragX < event.x -> {
                        // Scroll left
                        lastDirection = Direction.LEFT
                        isLeftScrollEnabled
                    }
                    mStartDragX > event.x -> {
                        // Scroll right
                        lastDirection = Direction.RIGHT
                        isRightScrollEnabled
                    }
                    else -> true
                }
            }
            else -> true
        }
    }
}
