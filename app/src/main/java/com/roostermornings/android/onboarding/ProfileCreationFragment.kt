package com.roostermornings.android.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import butterknife.BindView
import butterknife.OnClick
import com.facebook.*
import com.facebook.internal.CallbackManagerImpl
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.facebook.login.widget.LoginButton
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.mobsandgeeks.saripaar.Validator
import com.mobsandgeeks.saripaar.annotation.Email
import com.mobsandgeeks.saripaar.annotation.NotEmpty
import com.mobsandgeeks.saripaar.annotation.Password
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.R
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.local.OnboardingJourneyEvent
import com.roostermornings.android.firebase.AuthManager
import com.roostermornings.android.firebase.UserMetrics
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.util.Toaster
import kotlinx.android.synthetic.main.fragment_onboarding_profile_creation.*
import org.json.JSONObject
import java.util.*
import javax.inject.Inject

/**
 * Created by bscholtz on 2017/12/04.
 */
class ProfileCreationFragment : BaseFragment(), FragmentInterface, Validator.ValidationListener, CustomCommandInterface {
    private var mHostInterface: HostInterface? = null
    private var mCustomCommandInterface: CustomCommandInterface? = null

    private val RC_SIGN_IN = 7

    private var validator: Validator = Validator(this)
    private var mAlreadyUser = false

    private var facebookCallbackManager: CallbackManager? = null
    private var mGoogleApiClient: GoogleApiClient? = null

    @BindView(R.id.login_button)
    lateinit var facebookLoginButton: LoginButton

    @BindView(R.id.progressBar)
    lateinit var progressBar: ProgressBar

    @NotEmpty
    @BindView(R.id.signup_username_edittext)
    lateinit var mUserName: EditText

    @Email
    @BindView(R.id.signup_email_address_edittext)
    lateinit var mEmailAddress: EditText

    @Password(min = 6, scheme = Password.Scheme.ALPHA_NUMERIC)
    @BindView(R.id.signup_password_edittext)
    lateinit var mPassword: EditText

    @Inject
    lateinit var authManager: AuthManager

    companion object {

        val TAG = ProfileCreationFragment::class.java.simpleName?:"nullSimpleName"

        val ARG_FROM_SOURCE = "ARG_FROM_SOURCE"

        enum class Source {
            ONBOARDING, FRIENDS_PAGE, HOME_PAGE, PROFILE
        }

        /**
         * Returns a new instance of this fragment for the given section
         * number.
         */
        fun newInstance(fromSource: Source): ProfileCreationFragment {
            val fragment = ProfileCreationFragment()
            val args = Bundle()
            args.putSerializable(ARG_FROM_SOURCE, fromSource)
            fragment.arguments = args
            return fragment
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
        // If class cast exception, no worries, will perform safe interface call
        try {
            mHostInterface = context as HostInterface
        } catch (e: ClassCastException) {}
        try {
            mCustomCommandInterface = context as CustomCommandInterface
        } catch (e: ClassCastException) {}
    }

    override fun onPause() {
        super.onPause()
        activity?.let { mGoogleApiClient?.stopAutoManage(it) }
        mGoogleApiClient?.disconnect()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return initiate(inflater, R.layout.fragment_onboarding_profile_creation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        when(arguments?.getSerializable(ARG_FROM_SOURCE) as Source) {
            Source.FRIENDS_PAGE -> {
                signin_button_notnow.visibility = View.GONE
                terms_and_conditions_link.visibility = View.GONE
                signUpBenefits.text = getString(R.string.onboarding_in_app_sign_up_benefits)
            }
            Source.HOME_PAGE -> {
                signin_button_notnow.text = getString(R.string.signup_cancel)
                terms_and_conditions_link.visibility = View.GONE
                signUpBenefits.text = getString(R.string.onboarding_in_app_sign_up_benefits)
            }
            Source.PROFILE -> {
                signin_button_notnow.text = getString(R.string.signup_not_now)
                terms_and_conditions_link.visibility = View.GONE
                signUpBenefits.text = getString(R.string.onboarding_in_profile_sign_up_benefits)
            }
            else -> {}
        }

        if(authManager.isUserSignedIn()) {
            signUpLayout?.visibility = View.INVISIBLE
            emailLayout?.visibility = View.INVISIBLE
            signedInLayout.visibility = View.VISIBLE
        }

        initializeAuthProviders()

        //instantiate saripaar validator to validate fields with NotEmpty annotations
        validator.setValidationListener(this)

        val animationCloud = TranslateAnimation(0f, 0f, -10f, 10f)
        animationCloud.duration = 2000
        animationCloud.fillAfter = true
        animationCloud.repeatCount = -1
        animationCloud.repeatMode = Animation.REVERSE

        roosterCloud?.animation = animationCloud
    }

    override fun fragmentVisible(position: Int) {}

    override fun onCustomCommand(command: InterfaceCommands.Companion.Command) {
        when(command) {
            InterfaceCommands.Companion.Command.PROCEED -> {
                mHostInterface?.scrollViewPager(View.FOCUS_RIGHT)
            }
            else -> {}
        }
    }

    private fun initializeAuthProviders() {
        //Facebook
        facebookLoginButton.setReadPermissions("email", "public_profile", "user_birthday")
        facebookCallbackManager = CallbackManager.Factory.create()
        LoginManager.getInstance().registerCallback(
                facebookCallbackManager,
                object : FacebookCallback<LoginResult> {
                    override fun onSuccess(loginResult: LoginResult) {
                        Log.d(TAG, "facebook:onLinkSuccess:" + loginResult)

                        val graphRequest = GraphRequest.newMeRequest(loginResult.accessToken) {
                            graphObject: JSONObject?, _: GraphResponse? ->
                            authWithFacebook(loginResult.accessToken, graphObject)
                        }
                        val parameters = Bundle()
                        parameters.putString("fields", "id, name, email, gender, birthday")
                        graphRequest.parameters = parameters
                        graphRequest.executeAsync()
                    }
                    override fun onCancel() {
                        Log.e("Facebook: ", "onCancel")
                        //Remove progress bar on failure
                        progressBar.visibility = View.GONE
                    }
                    override fun onError(exception: FacebookException) {
                        Log.e("Facebook: ", "onError")
                        //Remove progress bar on failure
                        progressBar.visibility = View.GONE
                    }
                }
        )

        //Google
        // Configure sign-in to request the user's ID, email address, and basic
        // profile. ID and basic profile are included in DEFAULT_SIGN_IN.
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build()

        val context = context ?: return
        val activity = activity ?: return
        // Build a GoogleApiClient with access to the Google Sign-In API and the
        // options specified by gso.
        mGoogleApiClient = GoogleApiClient.Builder(context)
                .enableAutoManage(activity, null)
                .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
                .build()
    }

    @OnClick(R.id.already_user_textview)
    fun onAlreadyUserClicked() {
        mAlreadyUser = true
        mUserName.visibility = View.INVISIBLE
        signUpLayout?.visibility = View.INVISIBLE
        emailLayout?.visibility = View.VISIBLE
        signedInLayout.visibility = View.INVISIBLE
    }

    @OnClick(R.id.sign_up_email_textview)
    fun onSignUpEmailClicked() {
        mAlreadyUser = false
        mUserName.visibility = View.VISIBLE
        signUpLayout?.visibility = View.INVISIBLE
        emailLayout?.visibility = View.VISIBLE
        signedInLayout.visibility = View.INVISIBLE
    }

    @OnClick(R.id.cancel_button)
    fun onClickCancel() {
        signUpLayout?.visibility = View.VISIBLE
        emailLayout?.visibility = View.INVISIBLE
        signedInLayout.visibility = View.INVISIBLE
    }

    @OnClick(R.id.sign_out_button)
    fun onClickSignOut() {
        signUpLayout.visibility = View.VISIBLE
        emailLayout.visibility = View.INVISIBLE
        signedInLayout.visibility = View.INVISIBLE
        authManager.signOut()
    }

    @OnClick(R.id.terms_and_conditions_link)
    fun onTermsAndConditionsClicked() {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse(getString(R.string.rooster_website_t_and_c_url))
        startActivity(i)

        UserMetrics.logOnboardingEvent(
                OnboardingJourneyEvent(
                        subject = "Sign-In UI",
                        target = "onTermsAndConditionsClicked")
                        .setType(OnboardingJourneyEvent.Companion.Event.CLICK_ON))
    }

    @OnClick(R.id.signin_button_facebook)
    fun onFacebookSigninButtonClick(v: View) {
        LoginManager.getInstance().logInWithReadPermissions(
                this,
                Arrays.asList("public_profile", "email"))
    }

    @OnClick(R.id.signin_button_google)
    fun onGoogleSigninButtonClick(v: View) {
        val signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient)
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    @OnClick(R.id.signin_button_notnow)
    fun onNotNowSigninButtonClick() {
        // Create anonymous profile
        authManager.firebaseAnonymousAuth()
        proceedToNextPage()
    }

    //Receive auth activity result and start callback
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (!checkInternetConnection()) return

        //Result returned from launching Facebook authentication
        if (requestCode == CallbackManagerImpl.RequestCodeOffset.Login.toRequestCode()) {
            progressBar.visibility = View.VISIBLE
            facebookCallbackManager?.onActivityResult(requestCode, resultCode, data)
        }

        // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
        if (requestCode == RC_SIGN_IN) {
            progressBar.visibility = View.VISIBLE
            val result = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
            handleGoogleSignInResult(result)
        }
    }

    //Google
    private fun handleGoogleSignInResult(result: GoogleSignInResult) {
        Log.d(TAG, "handleGoogleSignInResult:" + result.isSuccess)
        if (result.isSuccess) {
            // Google Sign In was successful, authenticate with Firebase
            authWithGoogle(result)
        } else {
            //Remove progress bar on failure
            progressBar.visibility = View.GONE

            // Signed out, show unauthenticated UI.
            Toaster.makeToast(context,
                    "Google sign-in failed.",
                    Toast.LENGTH_LONG)
        }
    }

    private fun authWithFacebook(token: AccessToken, graphObject: JSONObject?) {
        authManager.firebaseAuthWithFacebook(token, object: AuthManager.AuthInterface {
            override fun onAuthSuccess(task: Task<AuthResult>) {
                UserMetrics.generateNewUserMetricsEntry()

                authManager.updateFirebaseUserDisplayName(Profile.getCurrentProfile().name)

                //TODO: get permission
                //UserMetrics.setBirthday(`object`?.getString("birthday")?:"")

                val gender = if (graphObject?.has("gender") == true)
                    graphObject.getString("gender") else ""
                val email = if (graphObject?.has("email") == true)
                    graphObject.getString("email") else ""

                UserMetrics.setGender(gender)
                UserMetrics.setEmail(email)

                changeLayoutSignedIn()
                proceedToNextPage()

                Toaster.makeToast(context,
                        "Facebook sign-in successful.",
                        Toast.LENGTH_LONG)
            }

            override fun onAuthFailure() {
                //Remove progress bar on failure
                progressBar.visibility = View.GONE

                Log.w(AuthManager.TAG, "Facebook: signInWithCredential failed")
                Toaster.makeToast(context,
                        "Facebook sign-in failed.",
                        Toast.LENGTH_LONG)
            }
        })
    }

    private fun authWithGoogle(result: GoogleSignInResult) {
        authManager.firebaseAuthWithGoogle(result, object: AuthManager.AuthInterface {
            override fun onAuthSuccess(task: Task<AuthResult>) {
                UserMetrics.generateNewUserMetricsEntry()

                changeLayoutSignedIn()
                proceedToNextPage()

                Toaster.makeToast(context,
                        "Google sign-in successful.",
                        Toast.LENGTH_LONG)
            }

            override fun onAuthFailure() {
                //Remove progress bar on failure
                progressBar.visibility = View.GONE

                Log.w(AuthManager.TAG, "Google: signInWithCredential failed")
                Toaster.makeToast(context,
                        "Google sign-in failed.",
                        Toast.LENGTH_LONG)
            }
        })
    }

    //On successful authentication, proceed to alarms activity
    private fun proceedToNextPage() {
        //Remove progress bar on success
        progressBar.visibility = View.GONE

        mHostInterface?.scrollViewPager(View.FOCUS_RIGHT)
        mCustomCommandInterface?.onCustomCommand(InterfaceCommands.Companion.Command.PROCEED)

        // Set auth method in user_metrics node
        var providerId = "anonymous"
        FirebaseAuth.getInstance().currentUser?.providerData?.forEach { userInfo ->
            when {
                userInfo.providerId.contains("google") -> {providerId = "google"}
                userInfo.providerId.contains("facebook") -> {providerId = "facebook"}
                userInfo.providerId.contains("password") -> {providerId = "email"}
                else -> {}
            }
        }

        UserMetrics.logOnboardingEvent(
                OnboardingJourneyEvent(
                        subject = "Sign-In UI",
                        target = providerId)
                        .setType(OnboardingJourneyEvent.Companion.Event.CLICK_ON))
    }

    @OnClick(R.id.signup_button_email)
    fun onSignupButtonClicked() {
        validator.validate()
    }

    override fun onValidationSucceeded() {
        super.onValidationSucceeded()

        val email = mEmailAddress.text.toString().trim({ it <= ' ' })
        val password = mPassword.text.toString().trim({ it <= ' ' })
        val name = mUserName.text.toString().trim({ it <= ' ' })

        progressBar.visibility = View.VISIBLE

        authManager.firebaseAuthWithEmail(name, email, password, mAlreadyUser,
                object: AuthManager.AuthInterface {
            override fun onAuthSuccess(task: Task<AuthResult>) {
                UserMetrics.generateNewUserMetricsEntry()
                authManager.updateFirebaseUserDisplayName(name)

                changeLayoutSignedIn()
                proceedToNextPage()

                Toaster.makeToast(context,
                        "Email sign-in successful.",
                        Toast.LENGTH_LONG)
            }

            override fun onAuthFailure() {
                //Remove progress bar on failure
                progressBar.visibility = View.GONE

                Log.w(AuthManager.TAG, "Email: signInWithCredential failed")
                Toaster.makeToast(context,
                        "Email sign-in failed.",
                        Toast.LENGTH_LONG)
            }
        })
    }

    private fun changeLayoutSignedIn() {
        if(arguments?.getBoolean(ARG_FROM_SOURCE) == true) {
            signUpLayout?.visibility = View.INVISIBLE
            emailLayout?.visibility = View.INVISIBLE
            signedInLayout?.visibility = View.GONE
        } else {
            signUpLayout?.visibility = View.INVISIBLE
            emailLayout?.visibility = View.INVISIBLE
            signedInLayout?.visibility = View.VISIBLE
        }
    }
}