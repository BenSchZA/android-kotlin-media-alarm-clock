package com.roostermornings.android.onboarding

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import butterknife.OnClick
import com.roostermornings.android.R
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.local.OnboardingJourneyEvent
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.fragment.base.BaseFragment
import kotlinx.android.synthetic.main.fragment_onboarding_intro.view.*
import java.util.*

class IntroFragment : BaseFragment() {

    private var logoAnimated: Boolean = false

    companion object {
        private val CLOUD_MAX_WIDTH = 300
        private val CLOUD_DURATION = 30000L
        private val LOGO_DURATION = 2000L
        private val HOOK_TEXT_DURATION = 500L

        private val MIN_RANDOM = -2000L
        private val MAX_RANDOM = 2000L

        /**
         * Returns a new instance of this fragment for the given section
         * number.
         */
        private val ARG_WINDOW_SIZE = "ARG_WINDOW_SIZE"

        fun newInstance(windowSize: Point): IntroFragment {
            val fragment = IntroFragment()
            val args = Bundle()
            args.putParcelable(ARG_WINDOW_SIZE, windowSize)
            fragment.arguments = args
            return fragment
        }
    }

    private val randomOffset = fun(): Long {
        return (MIN_RANDOM + (Random().nextDouble()*(MAX_RANDOM - MIN_RANDOM))).toLong()
    }

    @OnClick(R.id.button)
    fun onClickContinueButton() {
        mHostInterface?.scrollViewPager(View.FOCUS_RIGHT)

        UserMetrics.logOnboardingEvent(
                OnboardingJourneyEvent(
                        subject = "Intro UI",
                        target = "onClickContinueButton")
                        .setType(OnboardingJourneyEvent.Companion.Event.CLICK_ON))
    }

    private var mHostInterface: HostInterface? = null

    override fun inject(component: RoosterApplicationComponent) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        try {
            mHostInterface = context as HostInterface
        } catch (e: ClassCastException) {
            TODO("HostInterface not implemented") //To change body of created functions use File | Settings | File Templates.
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return initiate(inflater, R.layout.fragment_onboarding_intro, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val windowSize = arguments?.getParcelable(ARG_WINDOW_SIZE) as Point
        val windowWidth = windowSize.x.toFloat()
        val windowHeight = windowSize.y.toFloat()

        if(!logoAnimated) animateLogo(view) else view.hookText.alpha = 1f

        val animationCloud1 = TranslateAnimation(-(windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), (windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), 0F, 0F)
        animationCloud1.duration = CLOUD_DURATION + randomOffset()*8
        animationCloud1.fillAfter = true
        animationCloud1.repeatCount = -1
        animationCloud1.repeatMode = Animation.REVERSE

        val animationCloud2 = TranslateAnimation((windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), -(windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), 0F, 0F)
        animationCloud2.duration = CLOUD_DURATION + randomOffset()*8
        animationCloud2.fillAfter = true
        animationCloud2.repeatCount = -1
        animationCloud2.repeatMode = Animation.REVERSE
        animationCloud2.startOffset = 2000

        val animationCloud3 = TranslateAnimation(-(windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), (windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), 0F, 0F)
        animationCloud3.duration = CLOUD_DURATION + randomOffset()*8
        animationCloud3.fillAfter = true
        animationCloud3.repeatCount = -1
        animationCloud3.repeatMode = Animation.REVERSE
        animationCloud3.startOffset = 1000

        val animationCloud4 = TranslateAnimation((windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), -(windowWidth / 2 + CLOUD_MAX_WIDTH / 2F), 0F, 0F)
        animationCloud4.duration = CLOUD_DURATION + randomOffset()*8
        animationCloud4.fillAfter = true
        animationCloud4.repeatCount = -1
        animationCloud4.repeatMode = Animation.REVERSE

        view.cloud1.animation = animationCloud1
        view.cloud2.animation = animationCloud2
        view.cloud3.animation = animationCloud3
        view.cloud4.animation = animationCloud4

        val cloudAnimationListener = object: Animation.AnimationListener {
            override fun onAnimationRepeat(p0: Animation?) {
                p0?.startOffset = randomOffset()
                p0?.duration = CLOUD_DURATION + randomOffset()*8
            }
            override fun onAnimationEnd(p0: Animation?) {}
            override fun onAnimationStart(p0: Animation?) {}
        }

        animationCloud1.setAnimationListener(cloudAnimationListener)
        animationCloud2.setAnimationListener(cloudAnimationListener)
        animationCloud3.setAnimationListener(cloudAnimationListener)
        animationCloud4.setAnimationListener(cloudAnimationListener)
    }

    private fun animateLogo(view: View) {
        logoAnimated = true

        val logoLocation = IntArray(2)
        view.logo.getLocationInWindow(logoLocation)

        val logoAnimation = TranslateAnimation(Animation.RELATIVE_TO_SELF, 0F, Animation.RELATIVE_TO_SELF, 0F, Animation.RELATIVE_TO_PARENT, 1F, Animation.RELATIVE_TO_SELF, 0F)
        logoAnimation.duration = LOGO_DURATION
        logoAnimation.fillAfter = true

        val logoAnimationListener = object: Animation.AnimationListener {
            override fun onAnimationRepeat(p0: Animation?) {}
            override fun onAnimationEnd(p0: Animation?) {
                val hookTextAnimation = ObjectAnimator.ofFloat(view.hookText, "alpha", 0f, 1f)
                hookTextAnimation.duration = HOOK_TEXT_DURATION
                hookTextAnimation.start()
            }
            override fun onAnimationStart(p0: Animation?) {}
        }
        logoAnimation.setAnimationListener(logoAnimationListener)

        view.logo.animation = logoAnimation
    }
}