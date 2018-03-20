package com.roostermornings.android.onboarding.number_entry

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.support.design.widget.TextInputEditText
import android.support.design.widget.TextInputLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import butterknife.BindView
import butterknife.OnClick
import com.mobsandgeeks.saripaar.ValidationError
import com.mobsandgeeks.saripaar.Validator
import com.mobsandgeeks.saripaar.annotation.NotEmpty
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.firebase.FirebaseNetwork
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.keys.PrefsKey
import com.roostermornings.android.util.Toaster
import javax.inject.Inject

/**
 * Created by bscholtz on 2017/12/12.
 */
class NumberEntryFragment: BaseFragment(), Validator.ValidationListener {

    @BindView(R.id.mobile_number_edit_text)
    @NotEmpty
    lateinit var mobileNumber: EditText

    private var validator = Validator(this)
    private var mListener: NumberEntryListener? = null

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    companion object {
        /**
         * Returns a new instance of this fragment for the given section
         * number.
         */

        fun newInstance(): NumberEntryFragment {
            return NumberEntryFragment()
        }
    }

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
        // Instantiate Saripaar validator to validate fields with NotEmpty annotations
        validator.setValidationListener(this)
    }

    override fun inject(component: RoosterApplicationComponent) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        if (context is NumberEntryListener) {
            mListener = context
        } else {
            TODO(context.toString() + " must implement OnFragmentInteractionListener")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return initiate(inflater, R.layout.fragment_number_entry, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onValidationSucceeded() {
        val editor = sharedPreferences.edit()
        editor.putBoolean(PrefsKey.MOBILE_NUMBER_VALIDATED.name, true)
        //If it was a popup, and number was validated, don't show again when invalidated (e.g. through profile)
        editor.putBoolean(PrefsKey.MOBILE_NUMBER_ENTRY_DISMISSED.name, true)
        editor.apply()

        val mobileNumberString = mobileNumber.text.toString().trim { it <= ' ' }

        context?.let { context ->
            FirebaseNetwork.updateProfileCellNumber(context, mobileNumber.text.toString()
                    .trim { it <= ' ' })
        }

        mListener?.onMobileNumberValidated(mobileNumberString)
    }

    override fun onValidationFailed(errors: List<ValidationError>) {
        for (error in errors) {
            val view = error.view
            val message = error.getCollatedErrorMessage(activity)

            if (view is TextInputEditText) {

                val parent = view.getParent() as TextInputLayout
                parent.error = message

            } else {
                Toaster.makeToast(activity, message, Toast.LENGTH_LONG)
            }
        }
    }

    @OnClick(R.id.button_okay)
    fun onOkayClick() {
        validator.validate()
    }

    @OnClick(R.id.button_later)
    fun onLaterClick() {
        val editor = sharedPreferences.edit()
        editor.putBoolean(PrefsKey.MOBILE_NUMBER_ENTRY_DISMISSED.name, true)
        editor.apply()

        mListener?.dismissFragment()
    }
}