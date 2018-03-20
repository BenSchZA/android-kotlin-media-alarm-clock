package com.roostermornings.android.onboarding.social_demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.content.res.ResourcesCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.roostermornings.android.R
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.fragment.base.BaseFragment
import kotlinx.android.synthetic.main.fragment_onboarding_social_demo.view.*
import kotlinx.android.synthetic.main.onboarding_audio_demo.view.*
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.support.v7.app.AppCompatDelegate
import android.support.v7.content.res.AppCompatResources
import butterknife.OnClick
import com.roostermornings.android.activity.MyAlarmsFragmentActivity
import com.roostermornings.android.domain.local.OnboardingJourneyEvent
import com.roostermornings.android.firebase.FA
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.onboarding.HostInterface
import com.roostermornings.android.util.RoosterUtils


/**
 * Created by bscholtz on 2017/12/07.
 */
class SocialDemoFragment: BaseFragment() {
    private var mHostInterface: HostInterface? = null

    private var mMediaPlayer = MediaPlayer()
    private var mPlaying  = false

    private val media = intArrayOf(
            R.raw.onboarding_social_audio_anathie,
            R.raw.onboarding_social_audio_dom,
            R.raw.onboarding_social_audio_kathy,
            R.raw.onboarding_social_audio_peza)
    private val imageDrawables = intArrayOf(
            R.drawable.onboarding_social_demo_anathi,
            R.drawable.onboarding_social_demo_dom,
            R.drawable.onboarding_social_demo_mother,
            R.drawable.onboarding_social_demo_pez)
    private val imageTitles = arrayOf(
            "Sister",
            "Friend",
            "Mom",
            "Classmate")
    private val imageDescriptions = arrayOf(
            "A sweet message",
            "Laugh your way out of bed",
            "A thought provoking start",
            "Motivation to crush Monday")

    companion object {
        /**
         * Returns a new instance of this fragment for the given section
         * number.
         */

        fun newInstance(): SocialDemoFragment {
            return SocialDemoFragment()
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
        return initiate(inflater, R.layout.fragment_onboarding_social_demo, container, false)
    }

    @OnClick(R.id.button_proceed)
    fun onClickProceed() {
        val intent = Intent(context, MyAlarmsFragmentActivity::class.java)
        startActivity(intent)
        FA.Log(FA.Event.onboarding_first_entry::class.java, null, null)

        UserMetrics.logOnboardingEvent(
                OnboardingJourneyEvent(
                        subject = "Social Demo UI",
                        target = "proceed")
                        .setType(OnboardingJourneyEvent.Companion.Event.CLICK_ON))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.viewFlipper.isAutoStart = true
        view.viewFlipper.setFlipInterval(3000)

        imageDrawables.forEachIndexed { index, drawableID ->
            val drawablePerson = ResourcesCompat.getDrawable(resources, drawableID, null)
            val socialTitle = imageTitles[index]
            val socialDescription = imageDescriptions[index]

            val vfElement = View.inflate(context, R.layout.onboarding_audio_demo, null)

            // For pre-Lollipop devices use AppCompatResources (not VectorDrawableCompat) to get your vector from resources
            // https://github.com/aurelhubert/ahbottomnavigation/issues/110
            context?.let {
                if(!RoosterUtils.hasLollipop()) {
                    AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
                }
                val vectorDrawable = AppCompatResources.getDrawable(it, R.drawable.rooster_vector_play_button)
                vfElement.playPause?.setImageDrawable(vectorDrawable)
            }

            val color = ColorDrawable(ResourcesCompat.getColor(resources, R.color.white, null))
            val ld = LayerDrawable(arrayOf(color, drawablePerson))
            vfElement.audioDemoImage.setImageDrawable(ld)
            vfElement.socialDemoPerson.text = socialTitle
            vfElement.socialDemoMessage.text = socialDescription
            vfElement.socialDemoText.visibility = View.VISIBLE

            view.viewFlipper.addView(vfElement)
        }
        view.viewFlipper.startFlipping()

        for(childIndex in 0 until view.viewFlipper.childCount) {

            val mediaOnClickListener = View.OnClickListener {

                view.viewFlipper?.stopFlipping()
                val childView = view.viewFlipper.getChildAt(childIndex)

                if(mPlaying) {
                    mMediaPlayer.pause()
                    mPlaying = false
                    childView.playPause.isSelected = false
                    view.viewFlipper?.startFlipping()

                    UserMetrics.logOnboardingEvent(
                            OnboardingJourneyEvent(
                                    subject = "Social Demo UI",
                                    content_uid = imageTitles[childIndex],
                                    length = mMediaPlayer.currentPosition/1000)
                                    .setType(OnboardingJourneyEvent.Companion.Event.LISTEN))
                } else {
                    mMediaPlayer = MediaPlayer.create(context, media[childIndex])
                    childView.playPause.isSelected = true
                    mMediaPlayer.start()
                    mPlaying = true

                    UserMetrics.logOnboardingEvent(
                            OnboardingJourneyEvent(
                                    subject = "Social Demo UI",
                                    content_uid = imageTitles[childIndex])
                                    .setType(OnboardingJourneyEvent.Companion.Event.CLICK_CONTENT))
                }

                mMediaPlayer.setOnCompletionListener {
                    childView.playPause.isSelected = false
                    view.viewFlipper?.startFlipping()
                    mPlaying = false

                    UserMetrics.logOnboardingEvent(
                            OnboardingJourneyEvent(
                                    subject = "Social Demo UI",
                                    content_uid = imageTitles[childIndex],
                                    length = mMediaPlayer.currentPosition/1000)
                                    .setType(OnboardingJourneyEvent.Companion.Event.LISTEN))
                }
            }
            view.viewFlipper.getChildAt(childIndex)
                    .playPause.setOnClickListener(mediaOnClickListener)
            view.viewFlipper.getChildAt(childIndex)
                    .audioDemoImage.setOnClickListener(mediaOnClickListener)
        }
    }
}