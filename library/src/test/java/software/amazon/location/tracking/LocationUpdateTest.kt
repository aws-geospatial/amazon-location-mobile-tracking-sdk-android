package software.amazon.location.tracking

import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import aws.sdk.kotlin.services.location.LocationClient
import aws.sdk.kotlin.services.location.model.BatchUpdateDevicePositionRequest
import aws.sdk.kotlin.services.location.model.BatchUpdateDevicePositionResponse
import aws.sdk.kotlin.services.location.model.GetDevicePositionRequest
import aws.sdk.kotlin.services.location.model.GetDevicePositionResponse
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import junit.framework.TestCase.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import software.amazon.location.auth.EncryptedSharedPreferences
import software.amazon.location.auth.LocationCredentialsProvider
import software.amazon.location.tracking.aws.AmazonTrackingHttpClient
import software.amazon.location.tracking.config.SdkConfig.MAX_RETRY
import software.amazon.location.tracking.providers.DeviceIdProvider
import software.amazon.location.tracking.util.StoreKey

class LocationUpdateTest {
    private lateinit var context: Context
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mockkStatic(Log::class)
        mockkConstructor(EncryptedSharedPreferences::class)
        every { anyConstructed<EncryptedSharedPreferences>().initEncryptedSharedPreferences() } just runs
        every { anyConstructed<EncryptedSharedPreferences>().get(any()) } returns "mockDeviceID"
        every { anyConstructed<EncryptedSharedPreferences>().contains(StoreKey.DEVICE_ID) } returns true
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Looper::class)
    }

    @Test
    fun `test update Tracker Device Locations`() {
        val locationCredentialProvider = mockk<LocationCredentialsProvider>()
        mockkConstructor(LocationClient::class)
        val mockAmazonLocationClient = mockk<LocationClient>()
        val mockLocation = mockk<Location>()
        val mockDeviceIdProvider = mockk<DeviceIdProvider>()

        val deviceID = "mockDeviceID"
        every { mockDeviceIdProvider.getDeviceID() } returns deviceID
        val currentTimeMillis = System.currentTimeMillis()
        every { mockLocation.time } returns currentTimeMillis
        every { mockLocation.latitude } returns 23.455
        every { mockLocation.longitude } returns 103.556
        val exception = Exception("Mock exception")
        coEvery {
            anyConstructed<LocationClient>().batchUpdateDevicePosition(any())
        } throws exception

        val amazonTrackingHttpClient = AmazonTrackingHttpClient(context, "mockTrackerName")

        runBlocking(Dispatchers.Default) {
            try {
                amazonTrackingHttpClient.updateTrackerDeviceLocation(
                    mockAmazonLocationClient,
                    arrayOf(mockLocation)
                )
            } catch (e: Exception) {
                coVerify(exactly = MAX_RETRY) {
                    mockAmazonLocationClient.batchUpdateDevicePosition(any())
                }
            }
        }
    }

    @Test
    fun `test get Tracker Device Location`() {
        val locationCredentialProvider = mockk<LocationCredentialsProvider>()
        val mockAmazonLocationClient = mockk<LocationClient>()
        val mockDeviceIdProvider = mockk<DeviceIdProvider>()

        val deviceID = "mockDeviceID"
        every { mockDeviceIdProvider.getDeviceID() } returns deviceID
        val amazonTrackingHttpClient = AmazonTrackingHttpClient(context, "mockTrackerName")

        val mockGetDevicePositionResult = mockk<GetDevicePositionResponse>()
        coEvery {
            mockAmazonLocationClient.getDevicePosition(any())
        } returns mockGetDevicePositionResult

        runBlocking(Dispatchers.IO) {
            val result = amazonTrackingHttpClient.getTrackerDeviceLocation(mockAmazonLocationClient)

            coEvery {
                mockAmazonLocationClient.getDevicePosition(
                    match<GetDevicePositionRequest> {
                        it.deviceId == deviceID && it.trackerName == "mockTrackerName"
                    }
                )
            }

            assertEquals(mockGetDevicePositionResult, result)
        }
    }

    @Test
    fun `test update Tracker Device single Location`() {
        val locationCredentialProvider = mockk<LocationCredentialsProvider>()
        val mockAmazonLocationClient = mockk<LocationClient>()
        val mockLocation = mockk<Location>()

        val mockLocationTime = 123456789L
        every { mockLocation.time } returns mockLocationTime
        every { mockLocation.latitude } returns 23.455
        every { mockLocation.longitude } returns 103.556

        val mockBatchUpdateDevicePositionResult = mockk<BatchUpdateDevicePositionResponse>()
        coEvery {
            mockAmazonLocationClient.batchUpdateDevicePosition(any())
        } returns mockBatchUpdateDevicePositionResult

        val amazonTrackingHttpClient = AmazonTrackingHttpClient(context, "mockTrackerName")

        runBlocking(Dispatchers.Default) {
            val result = amazonTrackingHttpClient.updateTrackerDeviceLocation(
                mockAmazonLocationClient,
                mockLocation
            )

            coEvery {
                mockAmazonLocationClient.batchUpdateDevicePosition(
                    match<BatchUpdateDevicePositionRequest> {
                        it.updates.size == 1 && it.updates[0].sampleTime.epochMilliseconds == mockLocationTime
                    }
                )
            }
            assertNotNull(result)
        }
    }
}
