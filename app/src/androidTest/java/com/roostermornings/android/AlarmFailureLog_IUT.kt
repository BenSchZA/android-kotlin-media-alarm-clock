package com.roostermornings.android

import android.content.Context
import com.roostermornings.android.realm.AlarmFailureLog
import com.roostermornings.android.realm.RealmAlarmFailureLog
import com.roostermornings.android.util.Constants
import io.realm.Realm
import io.realm.RealmConfiguration


/**
 * Created by bscholtz on 2017/11/05.
 */

@RunWith(AndroidJUnit4::class)
class AlarmFailureLog_IUT {
    private lateinit var mockContext: Context
    private lateinit var mockRealm: Realm
    private lateinit var realmAlarmFailureLog: RealmAlarmFailureLog

    private val afl: AlarmFailureLog = AlarmFailureLog()

    @Rule
    @JvmField
    val testFolder = TemporaryFolder()

    @Before
    fun setup() {
        //Use a spy to enable override of methods
        //Context of the app under test
        mockContext = InstrumentationRegistry.getTargetContext()

        // Initialize Realm database
        Realm.init(mockContext)

        val tempFolder = testFolder.newFolder("realmdata")

        val alarmFailureLogRealmConfig = RealmConfiguration.Builder()
                .directory(tempFolder)
                .name("alarm_failure_log.realm")
                .schemaVersion(1)
                .build()
        Realm.setDefaultConfiguration(alarmFailureLogRealmConfig)

        this.mockRealm = Realm.getDefaultInstance()

        realmAlarmFailureLog = RealmAlarmFailureLog(mockContext)
        realmAlarmFailureLog.clearOldAlarmFailureLogs()
    }

    private fun configureAlarmFailureLogEntry() {
        afl.let {
            it.setFired(true)
            it.setActivated(true)
            it.setRunning(true)
            it.setSeen(true)
            it.setContent(true)
            it.setHeard(true)
            it.setInteraction(true)
            it.setDef(false)
            it.setFailsafe(false)
            it.setFailure(false)
            it.setContent(true)
            it.setStream(false)
            it.setChannel(true)
            it.setAlarmUid("test")
            it.setPendingIntentID(1)
            it.setScheduledTime(Constants.TIME_MILLIS_1_HOUR)
            it.setFiredTime(Constants.TIME_MILLIS_1_HOUR)
            realmAlarmFailureLog.updateOrCreateAlarmFailureLogEntry(it)
        }
    }

    @After
    fun finish() {
        mockRealm.close()
    }

    @Test
    fun shouldBeAbleToGetDefaultInstance() {
        assertThat(Realm.getDefaultInstance(), `is`(mockRealm))
    }

    @Test
    fun failurePermutationTests() {
        configureAlarmFailureLogEntry()

        for (permutation in 1..18) {
            when(permutation) {
                1 -> testPermutation(true) { it.setFired(false) }
                2 -> testPermutation(true) { it.setActivated(false) }
                3 -> testPermutation(false) {
                    it.setFired(true)
                    it.setActivated(true)
                    it.setRunning(false)
                }
                4 -> testPermutation(true) { it.setSeen(false) }
                5 -> testPermutation(true) { it.setHeard(false) }
                6 -> testPermutation(false) { it.setInteraction(false) }
                7 -> testPermutation(true) { it.setDef(true) }
                8 -> testPermutation(false) {
                    it.setChannel(false)
                    it.setContent(false)
                    it.setDef(true)
                }
                9 -> testPermutation(true) { it.setFailsafe(true) }
                10 -> testPermutation(false) {
                    it.setFired(true)
                    it.setActivated(true)
                    it.setRunning(false)
                    it.setHeard(false)
                    it.setSeen(false)
                    it.setFailsafe(true)
                }
                11 -> testPermutation(true) {
                    it.setChannel(true)
                    it.setContent(false)
                }
                12 -> testPermutation(true) {
                    it.setDef(true)
                    it.setContent(true)
                }
                13 -> testPermutation(true) {
                    it.setDef(true)
                    it.setChannel(true)
                }
                14 -> testPermutation(true) {
                    it.setStream(true)
                }
                15 -> testPermutation(true) {
                    it.setFiredTime(Constants.TIME_MILLIS_1_HOUR + Constants.TIME_MILLIS_1_MINUTE*6)
                }
                16 -> testPermutation(false) {
                    it.setFiredTime(Constants.TIME_MILLIS_1_HOUR + Constants.TIME_MILLIS_1_MINUTE*4)
                }
                17 -> testPermutation(true) {
                    it.setFiredTime(Constants.TIME_MILLIS_1_HOUR - Constants.TIME_MILLIS_1_MINUTE*6)
                }
                18 -> testPermutation(false) {
                    it.setFiredTime(Constants.TIME_MILLIS_1_HOUR - Constants.TIME_MILLIS_1_MINUTE*4)
                }
            }
        }
    }

    /**Check that the AlarmFailureLog permutation results in the expected failure result, assertFailure.
    * Pass a Realm database operation as a lambda (still need to check this is what I think it is...)*/

    private fun testPermutation(assertFailure: Boolean, operation: (AlarmFailureLog) -> Unit) {
        realmAlarmFailureLog.getAlarmFailureLogMillisSlot(Constants.TIME_MILLIS_1_HOUR) {
            alarmFailureLog ->
            operation(alarmFailureLog)
        }

        realmAlarmFailureLog.processAlarmFailures(false)
        assertThat(realmAlarmFailureLog.getAlarmFailures().isNotEmpty(), `is`(assertFailure))

        realmAlarmFailureLog.getAlarmFailureLogMillisSlot(Constants.TIME_MILLIS_1_HOUR) {
            alarmFailureLog ->
            alarmFailureLog.deleteFromRealm()
        }

        configureAlarmFailureLogEntry()
    }
}