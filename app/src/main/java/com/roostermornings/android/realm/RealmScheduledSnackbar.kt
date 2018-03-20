package com.roostermornings.android.realm

import android.app.Activity
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.snackbar.SnackbarManager
import io.realm.Realm
import io.realm.RealmObject
import io.realm.RealmResults
import java.lang.reflect.Modifier
import java.util.*
import javax.inject.Inject

/**
 * Created by bscholtz on 2017/11/15.
 */
class RealmScheduledSnackbar {
    @Inject lateinit var realm: Realm

    private var realmResults: RealmResults<ScheduledSnackbar>? = null

    init {
        BaseApplication.roosterApplicationComponent.inject(this)
    }

    fun closeRealm() {
        if(!realm.isClosed) realm.close()
    }

    // Note: this will delete scheduled snackbars once fetched
    fun getScheduledSnackbarsForActivity(activity: Activity): ArrayList<ScheduledSnackbar> {
        val gson = GsonBuilder().create()
        val snackbarsForActivity = ArrayList<ScheduledSnackbar>()
        val calendar = Calendar.getInstance()

        realm.where(ScheduledSnackbar::class.java)
                .equalTo("localDisplayClassName", activity.javaClass.name)
                .findAll().toList()
                .filter { (it.displayTime == -1L) || (it.displayTime < calendar.timeInMillis) }
                .onEach { snackbar ->

            val unmanagedScheduledSnackbar = realm.copyFromRealm(snackbar)

            val type = object : TypeToken<SnackbarManager.Companion.SnackbarQueueElement>() {}.type
            gson.fromJson<SnackbarManager.Companion.SnackbarQueueElement>(unmanagedScheduledSnackbar.jsonSnackbarQueueElement, type)?.let {
//                it.action = View.OnClickListener {  }
                unmanagedScheduledSnackbar.snackbarQueueElement = it
            }
            snackbarsForActivity.add(unmanagedScheduledSnackbar)

            realm.executeTransaction {
                snackbar.deleteFromRealm()
            }
        }.takeIf { it.isNotEmpty() }?.also {
            return snackbarsForActivity
        }
        return snackbarsForActivity
    }

    fun listenForScheduledSnackbars(activity: Activity, operation: () -> Unit) {
        realmResults = realm.where(ScheduledSnackbar::class.java)
                .equalTo("localDisplayClassName", activity.javaClass.name)
                .findAll()
        realmResults?.addChangeListener { _ ->
            operation()
        }
    }

    fun removeListeners() {
        if(!realm.isClosed) {
            realmResults?.removeAllChangeListeners()
            realm.removeAllChangeListeners()
        }
    }

    fun updateOrCreateScheduledSnackbarEntry(snackbarQueueElement: SnackbarManager.Companion.SnackbarQueueElement, localDisplayClassName: String, displayTimeMillis: Long) {
        // Ignore serialization of RealmObject subclass to avoid StackOverFlow error
        val gson = GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .excludeFieldsWithModifiers(Modifier.ABSTRACT)
                .addSerializationExclusionStrategy(object : ExclusionStrategy {
                    override fun shouldSkipField(f: FieldAttributes): Boolean {
                        return f.declaringClass == RealmObject::class.java
                    }

                    override fun shouldSkipClass(clazz: Class<*>): Boolean {
                        return false
                    }
                }).create()

        val scheduledSnackbar = ScheduledSnackbar()
        scheduledSnackbar.jsonSnackbarQueueElement = gson.toJson(snackbarQueueElement)
        scheduledSnackbar.localDisplayClassName = localDisplayClassName
        scheduledSnackbar.displayTime = displayTimeMillis

        // Insert a new entry, or update if it exists.
        // Working with an unmanaged Realm object, you're only guaranteed of no duplication if
        // primary key present.
        realm.executeTransaction {
            realm.insertOrUpdate(scheduledSnackbar)
        }
    }
}