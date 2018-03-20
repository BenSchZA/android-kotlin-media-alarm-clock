/*
 * Rooster Mornings Android.
 * Copyright (c)  2017 Roosta Media. All rights reserved.
 */

package com.roostermornings.android

import android.content.Context
import android.telephony.TelephonyManager
import com.roostermornings.android.geolocation.GeolocationRequest
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
class GeolocationRequest_IUT {

    val emptyString: String = ""
    val nullString: String? = null
    val normalNetworkOperator: String = "65501"
    val malformedNetworkOperator: String = "6550"

    lateinit var telephonyManager: TelephonyManager
    lateinit var appContext: Context

    @Before
    fun setup() {
        //Initialize any @Mock annotations
        MockitoAnnotations.initMocks(this)

        //Use a spy to enable override of methods
        //Context of the app under test
        appContext = Mockito.spy(InstrumentationRegistry.getTargetContext())
        telephonyManager = Mockito.spy(appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)

        //Return the spy telephonyManager when the system service is accessed from the spy context... 007
        Mockito.`when`(appContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(telephonyManager)
    }

    @Test
    fun networkOperator_NormalCase() {
        Mockito.`when`(telephonyManager.networkOperator).thenReturn(normalNetworkOperator)

        //Test will show if exceptions thrown
        val geolocationRequest = GeolocationRequest(appContext, false)
        assertThat(geolocationRequest, isA(GeolocationRequest::class.java))
        assertThat(geolocationRequest.homeMobileCountryCode.toString(), not(isEmptyOrNullString()))
        assertThat(geolocationRequest.homeMobileNetworkCode.toString(), not(isEmptyOrNullString()))
    }

    @Test
    fun networkOperator_FringeCase_MalformedString() {
        Mockito.`when`(telephonyManager.networkOperator).thenReturn(malformedNetworkOperator)

        //Test will show if exceptions thrown
        val geolocationRequest = GeolocationRequest(appContext, false)
        assertThat(geolocationRequest, isA(GeolocationRequest::class.java))
    }

    @Test
    fun networkOperator_FringeCase_EmptyString() {
        Mockito.`when`(telephonyManager.networkOperator).thenReturn(emptyString)

        //Test will show if exceptions thrown
        val geolocationRequest = GeolocationRequest(appContext, false)
        assertThat(geolocationRequest, isA(GeolocationRequest::class.java))
    }

    @Test
    fun networkOperator_FringeCase_NullString() {
        Mockito.`when`(telephonyManager.networkOperator).thenReturn(nullString)

        //Test will show if exceptions thrown
        val geolocationRequest = GeolocationRequest(appContext, false)
        assertThat(geolocationRequest, isA(GeolocationRequest::class.java))
    }
}
