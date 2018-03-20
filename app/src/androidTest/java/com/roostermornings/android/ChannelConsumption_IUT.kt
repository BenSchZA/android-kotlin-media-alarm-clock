package com.roostermornings.android

import android.content.Context
import com.roostermornings.android.adapter_data.ChannelManager
import com.roostermornings.android.domain.database.ChannelRooster
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import java.util.*
import java.util.concurrent.CountDownLatch

/**
 * Created by bscholtz on 2018/02/19.
 */
@RunWith(AndroidJUnit4::class)
class ChannelConsumption_IUT {

    val signal = CountDownLatch(1)

    lateinit var appContext: Context
    @Mock
    lateinit var mockCalendar: Calendar

    lateinit var calendar: Calendar
    val channelManager: ChannelManager by lazy { ChannelManager(appContext) }

    @Before
    fun setup() {
        //Initialize any @Mock annotations
        MockitoAnnotations.initMocks(this)

        //Use a spy to enable override of methods
        //Context of the app under test
        appContext = Mockito.spy(InstrumentationRegistry.getTargetContext())

        mockCalendar = Mockito.mock(Calendar::class.java)
        calendar = Calendar.getInstance()
        Mockito.`when`(mockCalendar.get(Calendar.DATE)).thenReturn(calendar.get(Calendar.DATE))
        Mockito.`when`(mockCalendar.timeInMillis).thenReturn(calendar.timeInMillis)

        setDate(1)
    }

    var channelRoosters = ArrayList<ChannelRooster>()

    data class ChannelExperiment(val uid: String, val day: Int, val link: String, val story: Boolean)
    val channelExperiments = ArrayList<ChannelExperiment>()

    @Test
    fun testChannelEvents() {
        signal.await()

        ChannelManager.onFlagChannelManagerDataListener = object : ChannelManager.Companion.OnFlagChannelManagerDataListener {
            override fun onChannelRoosterDataChanged(freshChannelRoosters: java.util.ArrayList<ChannelRooster>) {
                channelRoosters.clear()
                channelRoosters.addAll(freshChannelRoosters)
            }

            override fun onSyncFinished() {
                channelRoosters
                        .filter { it.story }
                        .forEach { testStoryChannel(it) }

                when(calendar.get(Calendar.DATE)) {
                    1 -> {

                    }
                    2 -> {

                    }
                    3 -> {

                    }
                    4 -> {

                    }
                    5 -> {

                    }
                    6 -> {

                    }
                    7 -> {

                    }
                    8 -> {

                    }
                    9 -> {

                    }
                    10 -> {

                    }
                    11 -> {

                    }
                    12 -> {

                    }
                    13 -> {

                    }
                    14 -> {

                    }
                }

                if(calendar.get(Calendar.DATE) == 14) {
                    channelExperiments.groupBy { it.link }.forEach {
                        assertThat(1, `is`(it.value.size))
                    }
                    signal.countDown()
                    return
                }
                incrementDate()
                testChannelEvents()
            }
        }

        channelManager.refreshChannelData(channelRoosters)
    }

    private fun testStoryChannel(channelRooster: ChannelRooster) {
        assertThat(false, `is`(channelManager.isChannelStoryDateLocked(channelRooster.channel_uid)))

        channelExperiments.add(ChannelExperiment(
                uid = channelRooster.channel_uid,
                day = calendar.get(Calendar.DATE),
                link = channelRooster.audio_file_url,
                story = channelRooster.story))

        channelManager.incrementChannelStoryIteration(channelRooster.channel_uid)
        assertThat(true, `is`(channelManager.isChannelStoryDateLocked(channelRooster.channel_uid)))
    }

    private fun setDate(date: Int) {
        calendar.set(Calendar.DATE, date)
    }

    private fun incrementDate() {
        calendar.set(Calendar.DATE, calendar.get(Calendar.DATE) + 1)
    }
}