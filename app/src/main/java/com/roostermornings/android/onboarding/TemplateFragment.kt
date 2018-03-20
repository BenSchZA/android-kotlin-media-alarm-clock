package com.roostermornings.android.onboarding

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.fragment.base.BaseFragment

class TemplateFragment : BaseFragment() {
    private var mHostInterface: HostInterface? = null

    companion object {
        /**
         * Returns a new instance of this fragment for the given section
         * number.
         */

        fun newInstance(): TemplateFragment {
            return TemplateFragment()
        }
    }

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
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
        return initiate(inflater, R.layout.fragment_onboarding_intro, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }
}