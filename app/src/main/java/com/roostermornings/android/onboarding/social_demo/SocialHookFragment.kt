package com.roostermornings.android.onboarding.social_demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import butterknife.OnClick
import com.roostermornings.android.R
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.local.OnboardingJourneyEvent
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.onboarding.HostInterface
import kotlinx.android.synthetic.main.fragment_onboarding_social_hook.view.*

/**
 * Created by bscholtz on 2017/12/07.
 */
class SocialHookFragment: BaseFragment() {
    private var mHostInterface: HostInterface? = null

    companion object {
        /**
         * Returns a new instance of this fragment for the given section
         * number.
         */

        fun newInstance(): SocialHookFragment {
            return SocialHookFragment()
        }
    }

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
        return initiate(inflater, R.layout.fragment_onboarding_social_hook, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val animationCloud = TranslateAnimation(0f, 0f, -10f, 10f)
        animationCloud.duration = 2000
        animationCloud.fillAfter = true
        animationCloud.repeatCount = -1
        animationCloud.repeatMode = Animation.REVERSE

        view.roosterCloud?.animation = animationCloud
    }

    @OnClick(R.id.button_yes)
    fun onClickSoundsCool() {
        mHostInterface?.scrollViewPager(View.FOCUS_RIGHT)

        UserMetrics.logOnboardingEvent(
                OnboardingJourneyEvent(
                        subject = "Social Hook UI",
                        target = "try_now")
                        .setType(OnboardingJourneyEvent.Companion.Event.CLICK_ON))
    }

    @OnClick(R.id.button_no)
    fun onClickTryLater() {
        UserMetrics.logOnboardingEvent(
                OnboardingJourneyEvent(
                        subject = "Social Hook UI",
                        target = "try_later")
                        .setType(OnboardingJourneyEvent.Companion.Event.CLICK_ON))

        val intent = Intent(context, MyAlarmsFragmentActivity::class.java)
        startActivity(intent)
        FA.Log(FA.Event.onboarding_first_entry::class.java, null, null)
    }
}