package com.roostermornings.android.firebase

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.facebook.AccessToken
import com.facebook.Profile
import com.facebook.internal.ImageRequest
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInResult
import com.google.android.gms.tasks.Task
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.*
import com.google.firebase.iid.FirebaseInstanceId
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.firebase.FirebaseNetwork.createOrUpdateRoosterUser
import com.roostermornings.android.keys.PrefsKey
import com.roostermornings.android.util.ConnectivityUtils
import javax.inject.Inject

/**
 * Created by bscholtz on 2017/12/05.
 */
class AuthManager(val context: Context) {

    @Inject
    lateinit var firebaseAuth: FirebaseAuth

    @Inject
    lateinit var sharedPrefs: SharedPreferences

    @Inject
    lateinit var conUtils: ConnectivityUtils

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    internal companion object {
        val TAG = AuthManager::class.java.simpleName?:"nullSimpleName"
    }

    private fun onAuthSuccess() {
        FirebaseAnalytics.getInstance(context).setUserId(firebaseAuth.currentUser?.uid)
    }

    interface AuthInterface {
        fun onAuthSuccess(task: Task<AuthResult>)
        fun onAuthFailure()
    }

    interface AnonymousLinkingListener {
        fun onLinkSuccess(task: Task<AuthResult>)
        fun onLinkFailure(exception: Exception)
    }

    fun isUserSignedIn(): Boolean {
        return FirebaseAuth.getInstance().currentUser != null
                && FirebaseAuth.getInstance().currentUser?.isAnonymous == false
    }

    fun signOut() {
        firebaseAuth.signOut()
        signInAnonymouslyIfNecessary{}
    }

    fun signInAnonymouslyIfNecessary(operation: (String?) -> Unit) {
        // If current user is anonymous, or no current user, then login anonymously
        if(firebaseAuth.currentUser?.isAnonymous == true || firebaseAuth.currentUser == null) {
            firebaseAuth.signInAnonymously().addOnCompleteListener {
                this@AuthManager.onAuthSuccess()
                clearPersistedAnonymousUID()
                persistAnonymousUID(firebaseAuth.currentUser)
                operation(firebaseAuth.currentUser?.uid)
            }.addOnFailureListener {
                Log.e(it.cause.toString(),it.message)
            }
        }
    }

    private fun getPersistedAnonymousUID(): String? {
        return sharedPrefs.getString(PrefsKey.ANONYMOUS_USER_UID.name, null)
    }

    private fun persistAnonymousUID(firebaseUser: FirebaseUser?) {
        if(firebaseUser?.isAnonymous == true) {
            sharedPrefs.edit()
                    .putString(PrefsKey.ANONYMOUS_USER_UID.name, firebaseUser.uid)
                    .apply()
        }
    }

    private fun clearPersistedAnonymousUID() {
        sharedPrefs.edit()
                .remove(PrefsKey.ANONYMOUS_USER_UID.name)
                .apply()
    }

    //Facebook
    fun firebaseAuthWithFacebook(token: AccessToken, listener: AuthInterface) {
        if (!conUtils.isConnected()) return

        Log.d(TAG, "firebaseAuthWithFacebook:" + token)

        val credential = FacebookAuthProvider.getCredential(token.token)

        attemptAnonymousLinking(credential, object: AnonymousLinkingListener {
            override fun onLinkSuccess(task: Task<AuthResult>) {
                this@AuthManager.onAuthSuccess()
                onSuccessfulFacebookAuth()
                listener.onAuthSuccess(task)
            }

            override fun onLinkFailure(exception: Exception) {
                firebaseAuth.signInWithCredential(credential).addOnCompleteListener { task ->
                    Log.d(TAG, "signInWithCredential:onComplete:" + task.isSuccessful)

                    // If sign in fails, display a message to the user. If sign in succeeds
                    // the auth state listener will be notified and logic to handle the
                    // signed in user can be handled in the listener.
                    if (task.isSuccessful) {
                        this@AuthManager.onAuthSuccess()
                        performMigration()
                        onSuccessfulFacebookAuth()
                        listener.onAuthSuccess(task)
                    } else {
                        Log.d(TAG, "firebaseAuthWithFacebook:" + exception)
                        listener.onAuthFailure()
                    }
                }
            }
        })
    }

    private fun onSuccessfulFacebookAuth() {
        val deviceToken = FirebaseInstanceId.getInstance().token

        val photoURL = try {
            //photoURLString = firebaseAuth.getCurrentUser().getPhotoUrl().toString();
            val dimensionPixelSize = context.resources.getDimensionPixelSize(com.facebook.R.dimen.com_facebook_profilepictureview_preset_size_normal)
            val profile = Profile.getCurrentProfile()
            ImageRequest.getProfilePictureUri(profile.id, dimensionPixelSize, dimensionPixelSize).toString()
        } catch (e: java.lang.NullPointerException) {
            e.printStackTrace()
            null
        }

        createOrUpdateRoosterUser(deviceToken, photoURL)
    }

    fun firebaseAuthWithGoogle(result: GoogleSignInResult, listener: AuthInterface) {
        if (!conUtils.isConnected()) return

        val account = result.signInAccount
        Log.d(TAG, "firebaseAuthWithGoogle:" + account?.id)

        val credential = GoogleAuthProvider.getCredential(account?.idToken, null)

        attemptAnonymousLinking(credential, object: AnonymousLinkingListener {
            override fun onLinkSuccess(task: Task<AuthResult>) {
                this@AuthManager.onAuthSuccess()
                onSuccessfulGoogleAuth(account)
                listener.onAuthSuccess(task)
            }

            override fun onLinkFailure(exception: Exception) {
                firebaseAuth.signInWithCredential(credential).addOnCompleteListener { task ->
                    Log.d(TAG, "signInWithCredential:onComplete:" + task.isSuccessful)

                    // If sign in fails, display a message to the user. If sign in succeeds
                    // the auth state listener will be notified and logic to handle the
                    // signed in user can be handled in the listener.
                    if (task.isSuccessful) {
                        this@AuthManager.onAuthSuccess()
                        performMigration()
                        onSuccessfulGoogleAuth(account)
                        listener.onAuthSuccess(task)
                    } else {
                        Log.d(TAG, "firebaseAuthWithGoogle:" + exception)
                        listener.onAuthFailure()
                    }
                }
            }
        })
    }

    private fun onSuccessfulGoogleAuth(account: GoogleSignInAccount?) {
        val deviceToken = FirebaseInstanceId.getInstance().token
        val photoURL = account?.photoUrl?.toString()

        FirebaseNetwork.createOrUpdateRoosterUser(deviceToken, photoURL)
    }

    fun firebaseAuthWithEmail(name: String, email: String, password: String, mAlreadyUser: Boolean, listener: AuthInterface) {

        if (!conUtils.isConnected()) return

        Log.d(TAG, "firebaseAuthWithEmail")

        val credential = EmailAuthProvider.getCredential(email, password)

        attemptAnonymousLinking(credential, object: AnonymousLinkingListener {
            override fun onLinkSuccess(task: Task<AuthResult>) {
                this@AuthManager.onAuthSuccess()
                onSuccessfulEmailAuth(name, mAlreadyUser)
                listener.onAuthSuccess(task)
            }

            override fun onLinkFailure(exception: Exception) {
                firebaseAuth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            Log.d(TAG, "signInWithCredential:onComplete:" + task.isSuccessful)

                            // If sign in fails, display a message to the user. If sign in succeeds
                            // the auth state listener will be notified and logic to handle the
                            // signed in user can be handled in the listener.
                            if (task.isSuccessful) {
                                this@AuthManager.onAuthSuccess()
                                performMigration()
                                onSuccessfulEmailAuth(name, mAlreadyUser)
                                listener.onAuthSuccess(task)
                            } else {
                                Log.d(TAG, "firebaseAuthWithEmail:" + exception)
                                listener.onAuthFailure()
                            }
                        }
            }
        })
    }

    private fun onSuccessfulEmailAuth(name: String, mAlreadyUser: Boolean) {
        val deviceToken = FirebaseInstanceId.getInstance().token

        FirebaseNetwork.getRoosterUser(firebaseAuth.currentUser?.uid) { roosterUser ->

            val userName = if(!roosterUser?.user_name.isNullOrBlank()) {
                roosterUser?.user_name
            } else {
                if(name.isNotBlank()) name else "Me"
            }

            val userPhoto = if(!roosterUser?.profile_pic.isNullOrBlank()) {
                Uri.parse(roosterUser?.profile_pic)
            } else null

            val builder = UserProfileChangeRequest.Builder()
            builder.setDisplayName(userName)
            if(userPhoto != null) builder.setPhotoUri(userPhoto)
            firebaseAuth.currentUser?.updateProfile(builder.build())

            FirebaseNetwork.createOrUpdateRoosterUser(deviceToken, "")
        }
    }

    fun firebaseAnonymousAuth() {
        val deviceToken = FirebaseInstanceId.getInstance().token

        FirebaseNetwork.createOrUpdateRoosterUser(deviceToken, null)
    }

    fun performMigration() {
        UserMetrics.migrateUserUID(getPersistedAnonymousUID(), firebaseAuth.currentUser?.uid)
    }

    private fun attemptAnonymousLinking(credential: AuthCredential, resultListener: AnonymousLinkingListener) {
        firebaseAuth.currentUser
                ?.linkWithCredential(credential)
                ?.addOnCompleteListener { task ->
                    if(task.isSuccessful) {
                        resultListener.onLinkSuccess(task)
                    }
        }?.addOnFailureListener {exception ->
            resultListener.onLinkFailure(exception)
        }
    }

    fun updateFirebaseUserDisplayName(displayName: String): Boolean {
        val changeRequest = UserProfileChangeRequest.Builder().setDisplayName(displayName).build()
        return FirebaseAuth.getInstance().currentUser?.updateProfile(changeRequest) != null
    }
}