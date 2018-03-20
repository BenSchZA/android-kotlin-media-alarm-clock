/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android.fragment.new_alarm

import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v7.widget.SwitchCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import butterknife.BindView
import butterknife.OnCheckedChanged
import butterknife.OnClick
import com.roostermornings.android.BaseApplication
import com.roostermornings.android.BuildConfig
import com.roostermornings.android.R
import com.roostermornings.android.activity.NewAlarmFragmentActivity
import com.roostermornings.android.adapter.MyAlarmsListAdapter
import com.roostermornings.android.dagger.RoosterApplicationComponent
import com.roostermornings.android.domain.database.Alarm
import com.roostermornings.android.fragment.IAlarmSetListener
import com.roostermornings.android.fragment.base.BaseFragment
import com.roostermornings.android.sqlutil.DeviceAlarmController
import com.roostermornings.android.sqlutil.DeviceAlarmTableManager
import com.roostermornings.android.util.Constants
import com.roostermornings.android.util.RoosterUtils
import com.roostermornings.android.util.Toaster
import java.util.*
import javax.inject.Inject
import javax.inject.Named

class NewAlarmFragment1 : BaseFragment() {

    @BindView(R.id.new_alarm_time)
    lateinit var textViewAlarmTime: TextView

    @BindView(R.id.new_alarm_am_pm)
    lateinit var textViewAlarmAmPm: TextView

    @BindView(R.id.days_parent1)
    lateinit var daysParentLinearLayout1: LinearLayout

    @BindView(R.id.days_parent2)
    lateinit var daysParentLinearLayout2: LinearLayout

    @BindView(R.id.new_alarm_fragment1_alarm_day_mon)
    lateinit var textViewMonday: TextView

    @BindView(R.id.new_alarm_fragment1_alarm_day_tue)
    lateinit var textViewTuesday: TextView

    @BindView(R.id.new_alarm_fragment1_alarm_day_wed)
    lateinit var textViewWednesday: TextView

    @BindView(R.id.new_alarm_fragment1_alarm_day_thu)
    lateinit var textViewThursday: TextView

    @BindView(R.id.new_alarm_fragment1_alarm_day_fri)
    lateinit var textViewFriday: TextView

    @BindView(R.id.new_alarm_fragment1_alarm_day_sat)
    lateinit var textViewSaturday: TextView

    @BindView(R.id.new_alarm_fragment1_alarm_day_sun)
    lateinit var textViewSunday: TextView

    @BindView(R.id.new_alarm_fragment1_switch_audio)
    lateinit var switchAudio: SwitchCompat

    @BindView(R.id.new_alarm_fragment1_switch_recurring)
    lateinit var switchRecurring: SwitchCompat

    @BindView(R.id.new_alarm_fragment1_save_alarm)
    lateinit var saveAlarm: Button

    private var mUserUidParam: String? = null
    private var mListener: IAlarmSetListener? = null
    private var mAlarm = Alarm()

    private var mCalendar = Calendar.getInstance()

    private var hour = mCalendar.get(Calendar.HOUR_OF_DAY)
    private var minute = mCalendar.get(Calendar.MINUTE)
    private var mTimePickerDialog: TimePickerDialog? = null

    @Inject lateinit var deviceAlarmController: DeviceAlarmController
    @Inject lateinit var deviceAlarmTableManager: DeviceAlarmTableManager

    @Inject
    @field:Named("default")
    lateinit var defaultSharedPreferences: SharedPreferences

    private var newAlarmInterface: NewAlarmInterface? = null

    override fun inject(component: RoosterApplicationComponent) {
        component.inject(this)
    }

    interface NewAlarmInterface {
        fun saveAndExit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            newAlarmInterface = activity as NewAlarmInterface?
        } catch (castException: ClassCastException) {
            /* The activity does not implement the listener. */
        }

        mUserUidParam = arguments?.getString(ARG_USER_UID_PARAM)

        mAlarm = mListener?.alarmDetails ?: Alarm()
        //Set current time
        mAlarm.hour = hour
        mAlarm.minute = minute

        //Set 24-hour or 12-hour time format
        val timeFormat = defaultSharedPreferences.getBoolean(Constants.USER_SETTINGS_TIME_FORMAT, true)

        mTimePickerDialog = TimePickerDialog(activity, R.style.TimeDialogTheme, TimePickerDialog.OnTimeSetListener { view, hourOfDay, minute -> setAlarmTime(hourOfDay, minute) }, hour, minute, timeFormat)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        val view = initiate(inflater, R.layout.fragment_new_alarm_step1, container, false)

        //If in edit mode, delete button should be visible and alarm details should be updated
        updateAlarmUIIfEdit()
        setAlarmTime(mAlarm.hour, mAlarm.minute)
        textViewAlarmTime.startAnimation(AnimationUtils.loadAnimation(AppContext, R.anim.pulse))
        return view
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)

        inject(BaseApplication.roosterApplicationComponent)

        if (context is IAlarmSetListener) {
            mListener = context
        } else {
            throw RuntimeException(context.toString() + " must implement IAlarmSetListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        mListener = null
    }

    override fun setMenuVisibility(visible: Boolean) {
        super.setMenuVisibility(visible)
        if (visible) {
            mListener?.setNextButtonCaption(getString(R.string.next))
        }
    }

    private fun setAlarmTime(hour: Int, minute: Int) {
        this.hour = hour
        this.minute = minute
        mAlarm.setHour(hour)
        mAlarm.setMinute(minute)

        //Set 24-hour or 12-hour time format
        val timeFormat = defaultSharedPreferences.getBoolean(Constants.USER_SETTINGS_TIME_FORMAT, true)

        textViewAlarmTime.text = RoosterUtils.setAlarmTimeFromHourAndMinute(mAlarm.hour, mAlarm.minute, timeFormat)

        //If using 12 hour format
        if (!timeFormat) {
            textViewAlarmAmPm.visibility = View.VISIBLE
            if (mAlarm.getHour() >= 12) {
                textViewAlarmAmPm.text = resources.getString(R.string.alarm_12_hour_pm)
            } else {
                textViewAlarmAmPm.text = resources.getString(R.string.alarm_12_hour_am)
            }
        }
    }

    @OnClick(R.id.new_alarm_time)
    fun onTimeClick() {
        mTimePickerDialog?.show()
        textViewAlarmTime.clearAnimation()
    }

    private fun updateAlarmUIIfEdit() {
        if (deviceAlarmTableManager.isSetInDB(NewAlarmFragmentActivity.currentAlarmId)!!) {
            mListener?.configureAlarmDetails()
            saveAlarm.visibility = View.VISIBLE
        }
    }

    @OnClick(R.id.new_alarm_fragment1_delete_alarm)
    fun deleteAlarm() {
        try {
            //Remove alarm *set* from local SQL database using retrieved Uid from firebase && Remove alarm from firebase
            deviceAlarmController.deleteAlarmSetGlobal(mAlarm.getUid())

            val myAlarmsListAdapter = MyAlarmsListAdapter()
            myAlarmsListAdapter.notifyDataSetChanged()

            startHomeActivity()
            Toaster.makeToast(AppContext, "Alarm deleted.", Toast.LENGTH_SHORT).checkTastyToast()
        } catch (e: NullPointerException) {
            e.printStackTrace()
            if (BuildConfig.DEBUG) Toaster.makeToast(AppContext, "Oi! Don't delete me. Delete alarm failed!", Toast.LENGTH_SHORT)
        }

    }

    @OnClick(R.id.new_alarm_fragment1_save_alarm)
    fun saveAlarm() {
        newAlarmInterface?.saveAndExit()
    }

    @OnClick(R.id.new_alarm_fragment1_alarm_day_mon, R.id.new_alarm_fragment1_alarm_day_tue, R.id.new_alarm_fragment1_alarm_day_wed, R.id.new_alarm_fragment1_alarm_day_thu, R.id.new_alarm_fragment1_alarm_day_fri, R.id.new_alarm_fragment1_alarm_day_sat, R.id.new_alarm_fragment1_alarm_day_sun)
    fun selectWeekDay(selectedDay: TextView) {
        val day = Integer.parseInt(selectedDay.tag as String)
        var selected = false
        when (day) {
            1 -> {
                selected = mAlarm.isMonday
                mAlarm.isMonday = !selected
            }
            2 -> {
                selected = mAlarm.isTuesday
                mAlarm.isTuesday = !selected
            }
            3 -> {
                selected = mAlarm.isWednesday
                mAlarm.isWednesday = !selected
            }
            4 -> {
                selected = mAlarm.isThursday
                mAlarm.isThursday = !selected
            }
            5 -> {
                selected = mAlarm.isFriday
                mAlarm.isFriday = !selected
            }
            6 -> {
                selected = mAlarm.isSaturday
                mAlarm.isSaturday = !selected
            }
            7 -> {
                selected = mAlarm.isSunday
                mAlarm.isSunday = !selected
            }
        }

        selectedDay.isSelected = !selected
        mListener?.alarmDetails = mAlarm
    }

    @OnCheckedChanged(R.id.new_alarm_fragment1_switch_audio)
    fun audioSwitchOnCheckedChanged(isChecked: Boolean) {

        mAlarm.isAllow_friend_audio_files = isChecked
        mListener?.alarmDetails = mAlarm
    }

    @OnCheckedChanged(R.id.new_alarm_fragment1_switch_recurring)
    fun recurringSwitchOnCheckedChanged(isChecked: Boolean) {

        if (isChecked) {
            mAlarm.isRecurring = true
            daysParentLinearLayout1.visibility = View.VISIBLE
            daysParentLinearLayout2.visibility = View.VISIBLE

            //Start animation
            val animation = AnimationSet(true)
            animation.addAnimation(AlphaAnimation(0.0f, 1.0f))
            animation.addAnimation(ScaleAnimation(0.8f, 1f, 0.8f, 1f))
            animation.duration = 300
            daysParentLinearLayout1.startAnimation(animation)
            daysParentLinearLayout2.startAnimation(animation)
        } else {
            mAlarm.isRecurring = false
            //Only allow day selection if recurring
            mAlarm.clearDays()

            //Start animation
            val animation = AnimationSet(true)
            animation.addAnimation(AlphaAnimation(1.0f, 0.0f))
            animation.addAnimation(ScaleAnimation(1f, 0.8f, 1f, 0.8f))
            animation.duration = 300
            daysParentLinearLayout1.startAnimation(animation)
            daysParentLinearLayout2.startAnimation(animation)
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}
                override fun onAnimationRepeat(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    daysParentLinearLayout1.visibility = View.GONE
                    daysParentLinearLayout2.visibility = View.GONE
                }
            })
        }
        mListener?.alarmDetails = mAlarm
    }

    fun configureEditedAlarmUISettings() {

        mAlarm = mListener?.alarmDetails ?: Alarm()

        textViewMonday.setBackgroundResource(R.drawable.selectable_circle_background)
        textViewTuesday.setBackgroundResource(R.drawable.selectable_circle_background)
        textViewWednesday.setBackgroundResource(R.drawable.selectable_circle_background)
        textViewThursday.setBackgroundResource(R.drawable.selectable_circle_background)
        textViewFriday.setBackgroundResource(R.drawable.selectable_circle_background)
        textViewSaturday.setBackgroundResource(R.drawable.selectable_circle_background)
        textViewSunday.setBackgroundResource(R.drawable.selectable_circle_background)

        if (mAlarm.monday) {
            textViewMonday.isSelected = true
        }
        if (mAlarm.tuesday) {
            textViewTuesday.isSelected = true
        }
        if (mAlarm.wednesday) {
            textViewWednesday.isSelected = true
        }
        if (mAlarm.thursday) {
            textViewThursday.isSelected = true
        }
        if (mAlarm.friday) {
            textViewFriday.isSelected = true
        }
        if (mAlarm.saturday) {
            textViewSaturday.isSelected = true
        }
        if (mAlarm.sunday) {
            textViewSunday.isSelected = true
        }

        this.hour = mAlarm.hour
        this.minute = mAlarm.minute
        setAlarmTime(this.hour, this.minute)
        mTimePickerDialog?.updateTime(this.hour, this.minute)

        switchAudio.isChecked = mAlarm.allow_friend_audio_files
        switchRecurring.isChecked = mAlarm.recurring
    }

    companion object {

        private val ARG_USER_UID_PARAM = "user_uid_param"
        val TAG = NewAlarmFragment1::class.java.simpleName

        fun newInstance(userUid: String?): NewAlarmFragment1 {
            val fragment = NewAlarmFragment1()
            val args = Bundle()
            args.putString(ARG_USER_UID_PARAM, userUid)
            fragment.arguments = args
            return fragment
        }
    }

}// Required empty public constructor
