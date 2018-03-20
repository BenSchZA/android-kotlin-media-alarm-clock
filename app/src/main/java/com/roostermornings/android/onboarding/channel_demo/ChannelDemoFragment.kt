package com.roostermornings.android.onboarding.channel_demo

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.Fragment
import android.support.v4.content.res.ResourcesCompat
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import butterknife.OnClick
import com.roostermornings.android.R
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.fragment.base.BaseFragment
import kotlinx.android.synthetic.main.fragment_onboarding_channel_demo.view.*
import android.support.v7.widget.GridLayoutManager
import com.roostermornings.android.domain.local.OnboardingJourneyEvent
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.onboarding.*


class ChannelDemoFragment : BaseFragment(), ChannelDemoInterface, FragmentInterface, CustomCommandInterface {
    private var mHostInterface: HostInterface? = null
    private var mShowcaseInterface: ShowcaseInterface? = null
    private var mShowcaseHandler: Handler = Handler()

    private val examples = arrayOf(
            ChannelDemoItem("Inspiration",
                    "Wake up inspired",
                    R.drawable.onboarding_channel_demo_inspiration,
                    R.raw.onboarding_channel_audio_inspiration),
            ChannelDemoItem("Meditation",
                    "Wake up refreshed",
                    R.drawable.onboarding_channel_demo_meditation,
                    R.raw.onboarding_channel_audio_meditation),
            ChannelDemoItem("Bird Calls",
                    "Wake up to Nature's song",
                    R.drawable.onboarding_channel_demo_purple_breasted_roller,
                    R.raw.onboarding_channel_audio_bird_calls),
            ChannelDemoItem("Music",
                    "Wake up to the latest fresh tunes",
                    R.drawable.onboarding_channel_demo_music,
                    R.raw.onboarding_channel_audio_music),
            ChannelDemoItem("Get Pumped",
                    "Wake up motivated",
                    R.drawable.onboarding_channel_demo_get_pumped,
                    R.raw.onboarding_channel_audio_big_red),
            ChannelDemoItem("Comedy",
                    "Wake up with a laugh",
                    R.drawable.onboarding_channel_demo_comedy,
                    R.raw.onboarding_channel_audio_comedy),
            ChannelDemoItem("Nature Sounds",
                    "Wake up to the gentle sounds of the wild",
                    R.drawable.onboarding_channel_demo_nature_sounds,
                    R.raw.onboarding_channel_audio_nature_sounds),
            ChannelDemoItem("Muslim",
                    "",
                    R.drawable.onboarding_channel_demo_tanabbah,
                    R.raw.onboarding_channel_audio_muslim),
            ChannelDemoItem("Christian",
                    "",
                    R.drawable.onboarding_channel_demo_christian,
                    R.raw.onboarding_channel_audio_christian),
            ChannelDemoItem("This Day in History",
                    "Wake up enlightened",
                    R.drawable.onboarding_channel_demo_tdih,
                    R.raw.onboarding_channel_audio_tdih))

    private var mDataSet = ArrayList<ChannelDemoItem>()
    private var mAdapter = ChannelDemoListAdapter(mDataSet, this@ChannelDemoFragment)

    init {
        mDataSet.addAll(examples)
    }

    companion object {
        class ChannelDemoItem(var title: String = "",
                              var description: String = "",
                              var imageID: Int = -1,
                              var audioID: Int = -1)

        /**
         * Returns a new instance of this fragment for the given section
         * number.
         */

        fun newInstance(): ChannelDemoFragment {
            return ChannelDemoFragment()
        }
    }

    override fun inject(component: RoosterApplicationComponent) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        try {
            mHostInterface = context as HostInterface
        } catch (e: ClassCastException) {}
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return initiate(inflater, R.layout.fragment_onboarding_channel_demo, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.gridRecylerView as RecyclerView
        val layoutManager = GridLayoutManager(context, 2)

        // Create a custom SpanSizeLookup where the first item spans both columns
        layoutManager.spanSizeLookup = (object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return when(position) {
                            0 -> 2
                            3 -> 2
                            6 -> 2
                            9 -> 2
                            else -> 1
                        }
                    }
                })

        //layoutManager.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = mAdapter

        try {
            mShowcaseInterface = mAdapter
        } catch (e: ClassCastException) {}

        view.navigationFAB.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.onboarding_blue, null))

        val mScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val pastVisibleItems = layoutManager.findFirstVisibleItemPosition()
                if (pastVisibleItems + visibleItemCount >= totalItemCount) {
                    //End of list
                    UserMetrics.logOnboardingEvent(
                            OnboardingJourneyEvent(subject = "Channel Demo UI")
                                    .setType(OnboardingJourneyEvent.Companion.Event.SCROLL))
                }
            }
        }
        view.gridRecylerView.addOnScrollListener(mScrollListener)
    }

    @OnClick(R.id.navigationFAB)
    fun onClickFAB() {
        mHostInterface?.scrollViewPager(View.FOCUS_RIGHT)
    }

    private var previousOverlayFragment: Fragment? = null

    override fun onCustomCommand(command: InterfaceCommands.Companion.Command) {
        when(command) {
            InterfaceCommands.Companion.Command.HIDE_FAB -> {
                view?.navigationFAB?.hide()
            }
            InterfaceCommands.Companion.Command.SHOW_FAB -> {
                view?.navigationFAB?.show()
            }
            else -> {}
        }
    }

    private var overlayFragment: Fragment? = null
    override fun performChannelImageTransition(uid: String, title: String, description: String, drawableID: Int, imageView: ImageView, media: Int) {
        overlayFragment = ChannelDemoOverlayFragment.newInstance(uid, title, description, drawableID, media)

        val activity = activity ?: return

        if(previousOverlayFragment != null) activity.supportFragmentManager.popBackStack()

        activity.supportFragmentManager
                .beginTransaction()
                .replace(R.id.fragmentOnboardingChannelDemo, overlayFragment)
                .addToBackStack(null)
                .commit()

        previousOverlayFragment = overlayFragment
    }

    override fun fragmentVisible(position: Int) {
        val activity = activity ?: return

        when(position) {
            1 -> {
                mShowcaseInterface?.startShowCase(mShowcaseHandler, activity)
            }
            else -> {
                mShowcaseHandler.removeCallbacksAndMessages(null)
                mShowcaseInterface?.dismissShowcase()
                overlayFragment?.let {
                    activity.supportFragmentManager
                            .beginTransaction()
                            .remove(it)
                            .commit()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mShowcaseHandler.removeCallbacksAndMessages(null)
    }
}