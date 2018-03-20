package com.roostermornings.android.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.roostermornings.android.BuildConfig
import com.roostermornings.android.domain.local.MetricsErrorEvent
import com.roostermornings.android.domain.local.MetricsSyncEvent
import com.roostermornings.android.domain.local.OnboardingJourneyEvent
import java.util.*

/**
 * Created by bscholtz on 2017/12/14.
 */
object UserMetrics {

    private val USER_METRICS = "user_metrics"

    private fun isUserSignedIn(): Boolean {
        return FirebaseAuth.getInstance().currentUser != null
                && FirebaseAuth.getInstance().currentUser?.isAnonymous == false
    }

    fun migrateUserUID(anonymousUID: String?, signInUID: String?) {
        val fDB = FirebaseDatabase.getInstance().reference

        val childUpdates = HashMap<String, Any>()

        if (!anonymousUID.isNullOrBlank() && !signInUID.isNullOrBlank()) {
            childUpdates.put("$USER_METRICS/$anonymousUID/migrate_uid", signInUID!!)
            fDB.updateChildren(childUpdates)
        }
    }

    fun logErrorEvent(errorEvent: MetricsErrorEvent) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/error_log/${errorEvent.timestamp}", errorEvent)
            fDB.updateChildren(childUpdates)
        }
    }

    fun logSyncEvent(syncEvent: MetricsSyncEvent) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/sync_log/${syncEvent.timestamp}", syncEvent)
            fDB.updateChildren(childUpdates)
        }
    }

    fun logOnboardingEvent(event: OnboardingJourneyEvent) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/onboarding_journey/${event.timestamp}", event)
            fDB.updateChildren(childUpdates)
        }
    }

    fun updateLastSeen() {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser
        val calendar = Calendar.getInstance()

        val childUpdates = HashMap<String, Any>()
        calendar.timeZone = TimeZone.getTimeZone("UTC")

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/last_seen", calendar.timeInMillis)
            fDB.updateChildren(childUpdates)
        }
    }

    fun updateGeohash(geohash: String) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/geohash", geohash)
            fDB.updateChildren(childUpdates)
        }
    }

    fun setAuthMethod() {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        var providerId = "anonymous"
        fUser?.providerData?.forEach { userInfo ->
            when {
                userInfo.providerId.contains("google") -> {providerId = "google"}
                userInfo.providerId.contains("facebook") -> {providerId = "facebook"}
                userInfo.providerId.contains("password") -> {providerId = "email"}
                else -> {}
            }
        }

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/auth", providerId)
            fDB.updateChildren(childUpdates)
        }
    }

    fun setGender(gender: String) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/gender", gender)
            fDB.updateChildren(childUpdates)
        }
    }

    fun setEmail(email: String) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true && email.isNotBlank()) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/email", email)
            fDB.updateChildren(childUpdates)
        }
    }

    fun setBirthday(birthday: String) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/birthday", birthday)
            fDB.updateChildren(childUpdates)
        }
    }

    enum class Permission {
        PERMISSION_CONTACTS, PERMISSION_MIC, PERMISSION_STORAGE
    }

    fun setPermission(permission: Permission, granted: Boolean) {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/${permission.name.toLowerCase()}", granted)
            fDB.updateChildren(childUpdates)
        }
    }

    fun updateVersionCode() {
        // Handled by FCF as version name
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/app_version", BuildConfig.VERSION_CODE)
            fDB.updateChildren(childUpdates)
        }
    }

    fun logActiveDays() {
        val fUser = FirebaseAuth.getInstance().currentUser

        if(fUser?.uid?.isNotBlank() == true) {
            FA.Log(FA.Event.active_day::class.java, null, null)
        }
    }

    fun generateNewUserMetricsEntry() {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser
        val calendar = Calendar.getInstance()

        val childUpdates = HashMap<String, Any>()
        calendar.timeZone = TimeZone.getTimeZone("UTC")

        if (fUser?.uid?.isNotBlank() == true) {
            val signUpDateListener = object : ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    if(!dataSnapshot.exists()) {
                        childUpdates.put("$USER_METRICS/${fUser.uid}/signup_date", calendar.timeInMillis)
                        childUpdates.put("$USER_METRICS/${fUser.uid}/uid", fUser.uid)
                        childUpdates.put("$USER_METRICS/${fUser.uid}/device_type", "android")
                        childUpdates.put("$USER_METRICS/${fUser.uid}/friends", 1)
                        setAuthMethod()
                        fDB.updateChildren(childUpdates)
                    }
                }
                override fun onCancelled(databaseError: DatabaseError) {}
            }

            val ref = fDB.child("$USER_METRICS/${fUser.uid}/signup_date")
            ref.keepSynced(true)
            ref.addListenerForSingleValueEvent(signUpDateListener)
        }
    }

    fun updateUserMetricsEntry() {
        val fDB = FirebaseDatabase.getInstance().reference
        val fUser = FirebaseAuth.getInstance().currentUser

        val childUpdates = HashMap<String, Any>()

        if (fUser?.uid?.isNotBlank() == true) {
            childUpdates.put("$USER_METRICS/${fUser.uid}/uid", fUser.uid)
            childUpdates.put("$USER_METRICS/${fUser.uid}/user_name", fUser.displayName?:"")
            fUser.email?.takeIf { it.isNotBlank() }?.let {
                childUpdates.put("$USER_METRICS/${fUser.uid}/email", fUser.email?:"")
            }
            setAuthMethod()
            fDB.updateChildren(childUpdates)
        }
    }
}