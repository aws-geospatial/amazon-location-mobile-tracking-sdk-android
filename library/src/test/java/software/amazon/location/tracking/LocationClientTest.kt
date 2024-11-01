package software.amazon.location.tracking

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.room.RoomDatabase
import aws.sdk.kotlin.services.location.LocationClient
import aws.sdk.kotlin.services.location.model.BatchEvaluateGeofencesResponse
import aws.sdk.kotlin.services.location.model.BatchUpdateDevicePositionResponse
import aws.sdk.kotlin.services.location.model.GetDevicePositionResponse
import aws.sdk.kotlin.services.location.model.PositionalAccuracy
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.time.Instant
import aws.smithy.kotlin.runtime.util.PlatformProvider
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.gson.GsonBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import software.amazon.location.auth.EncryptedSharedPreferences
import software.amazon.location.auth.LocationCredentialsProvider
import software.amazon.location.tracking.TestConstants.LAST_LOCATION
import software.amazon.location.tracking.TestConstants.TEST_CLIENT_CONFIG
import software.amazon.location.tracking.TestConstants.TEST_IDENTITY_POOL_ID
import software.amazon.location.tracking.TestConstants.TEST_LATITUDE
import software.amazon.location.tracking.TestConstants.TEST_LONGITUDE
import software.amazon.location.tracking.TestConstants.TRACKER_NAME
import software.amazon.location.tracking.aws.AmazonTrackingHttpClient
import software.amazon.location.tracking.aws.LocationTrackingCallback
import software.amazon.location.tracking.config.LocationTrackerConfig
import software.amazon.location.tracking.database.LocationEntry
import software.amazon.location.tracking.database.LocationEntryDao_Impl
import software.amazon.location.tracking.filters.AccuracyLocationFilter
import software.amazon.location.tracking.filters.DistanceLocationFilter
import software.amazon.location.tracking.filters.TimeLocationFilter
import software.amazon.location.tracking.providers.BackgroundLocationService
import software.amazon.location.tracking.providers.BackgroundTrackingWorker
import software.amazon.location.tracking.providers.LocationProvider
import software.amazon.location.tracking.util.BackgroundTrackingMode
import software.amazon.location.tracking.util.Helper
import software.amazon.location.tracking.util.ServiceCallback
import software.amazon.location.tracking.util.StoreKey
import software.amazon.location.tracking.util.TrackingSdkLogLevel

class LocationClientTest {


    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private lateinit var context: Context
    private lateinit var locationManager: LocationManager
    private lateinit var locationCredentialsProvider: LocationCredentialsProvider
    private lateinit var locationTrackingCallback: LocationTrackingCallback
    private lateinit var serviceCallback: ServiceCallback
    private lateinit var locationClientConfig: LocationTrackerConfig
    private lateinit var gsonBuilderMock: GsonBuilder
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    @OptIn(InternalApi::class)
    @Before
    fun setUp() {
        // These are mocked out so that the construction of LocationClient doesn't crash
        // from trying to access null Build.VERSION fields.
        mockkObject(PlatformProvider.System)
        every { PlatformProvider.System.isAndroid } returns false

        context = mockk(relaxed = true)
        locationManager = mockk()
        serviceCallback = mockk()
        locationTrackingCallback = mockk()
        locationCredentialsProvider = mockk()
        every { locationCredentialsProvider.getLocationClientConfig()} returns {}
        locationClientConfig = LocationTrackerConfig(
            trackerName = TRACKER_NAME,
            logLevel = TrackingSdkLogLevel.DEBUG,
            accuracy = Priority.PRIORITY_HIGH_ACCURACY,
            waitForAccurateLocation = false,
        )
        gsonBuilderMock = mockk()
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { gsonBuilderMock.create() } returns mockk()
        every { gsonBuilderMock.registerTypeAdapter(any(), any()) } returns gsonBuilderMock

        val locationClientConfig = mockk<LocationTrackerConfig>()
        every { locationClientConfig.logLevel } returns TrackingSdkLogLevel.DEBUG

        mockkConstructor(Helper::class)
        every { anyConstructed<Helper>().isGooglePlayServicesAvailable(any()) } returns true
        mockkConstructor(EncryptedSharedPreferences::class)
        every { anyConstructed<EncryptedSharedPreferences>().initEncryptedSharedPreferences() } just runs
        every { anyConstructed<EncryptedSharedPreferences>().get(any()) } returns "mockDeviceID"
        every { anyConstructed<EncryptedSharedPreferences>().contains(StoreKey.DEVICE_ID) } returns true
        every { anyConstructed<EncryptedSharedPreferences>().put(any(), any<String>()) } just runs
        mockkConstructor(LocationCredentialsProvider::class)
        mockkConstructor(AmazonTrackingHttpClient::class)
        mockkConstructor(LocationProvider::class)
        mockkConstructor(RoomDatabase::class)
        mockkConstructor(LocationClient::class)
        fusedLocationProviderClient = mockk()
        mockkStatic(LocationServices::class)
        mockkStatic(Build.VERSION::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationProviderClient
        every { anyConstructed<EncryptedSharedPreferences>().get(any()) } returns TRACKER_NAME
        val location = mock(Location::class.java)
        `when`(location.latitude).thenReturn(TEST_LATITUDE)
        `when`(location.longitude).thenReturn(TEST_LONGITUDE)
        every {
            fusedLocationProviderClient.getCurrentLocation(
                ofType(CurrentLocationRequest::class), any()
            )
        } answers {
            Tasks.forResult(location)
        }
        val mockTask: Task<Void?> = mockk()
        coEvery {
            fusedLocationProviderClient.removeLocationUpdates(any<LocationCallback>())
        } returns mockTask
        every { fusedLocationProviderClient.locationAvailability } returns mockk()
        val locationAvailability = mockk<LocationAvailability>()

        every { locationAvailability.isLocationAvailable } returns true

        coEvery { fusedLocationProviderClient.locationAvailability } returns Tasks.forResult(
            locationAvailability
        )
        mockkStatic(Log::class)

        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Looper::class)
        val mainLooper = mockk<Looper>()
        every { Looper.getMainLooper() } returns mainLooper

        val mainThread = thread { }
        every { mainLooper.thread } returns mainThread

        val mockBatchUpdateDevicePositionResult = mockk<BatchUpdateDevicePositionResponse>()
        coEvery {
            anyConstructed<LocationClient>().batchUpdateDevicePosition(any())
        } returns mockBatchUpdateDevicePositionResult

        val getDevicePositionResult = mockk<GetDevicePositionResponse>()
        coEvery {
            anyConstructed<LocationClient>().getDevicePosition(any())
        } returns getDevicePositionResult
        coEvery {
            anyConstructed<LocationClient>().getDevicePosition(any()).position
        } returns listOf(TEST_LONGITUDE, TEST_LATITUDE)
        coEvery {
            anyConstructed<LocationClient>().getDevicePosition(any()).sampleTime
        } returns Instant.now()
        coEvery {
            anyConstructed<LocationClient>().getDevicePosition(any()).accuracy
        } returns PositionalAccuracy { horizontal = 10.0 }

        mockkConstructor(Location::class)

        every { anyConstructed<Location>().latitude = any() } just runs
        every { anyConstructed<Location>().longitude = any() } just runs
        every { anyConstructed<Location>().time = any() } just runs
        every { anyConstructed<Location>().accuracy = any() } just runs
        every { anyConstructed<Location>().hasAccuracy() } returns true

        val location1 = LocationEntry(1, 10.0, 20.0, System.currentTimeMillis(), 5.0f)
        val locations = listOf(location1)
        mockkConstructor(LocationEntryDao_Impl::class)
        coEvery { anyConstructed<LocationEntryDao_Impl>().getAllEntries() } returns locations
        coEvery { anyConstructed<LocationEntryDao_Impl>().insert(any()) } just runs
        coEvery { anyConstructed<LocationEntryDao_Impl>().deleteEntriesByIds(any()) } just runs
        coEvery { anyConstructed<LocationEntryDao_Impl>().deleteEntryById(any()) } just runs

        every { anyConstructed<Location>().getAccuracy() } returns 10f
        every { anyConstructed<Location>().getTime() } returns System.currentTimeMillis()
        every { anyConstructed<Location>().getLatitude() } returns TEST_LATITUDE
        every { anyConstructed<Location>().getLongitude() } returns TEST_LONGITUDE
    }

    @Test
    fun `test constructor with LocationClientConfig`() {
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)

        assertNotNull(locationClient)
    }

    @Test
    fun `test constructor with tracker name`() {
        val locationClient = LocationTracker(context, locationCredentialsProvider, TRACKER_NAME)

        assertNotNull(locationClient)
    }

    @Test
    fun `test constructor with LocationCredentialsProvider`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(StoreKey.CLIENT_CONFIG) } returns TEST_CLIENT_CONFIG
        val locationClient = LocationTracker(context, locationCredentialsProvider)

        assertNotNull(locationClient)
    }

    @Test
    fun `start and stop location tracking`() {
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        val locationTrackingCallback = mockk<LocationTrackingCallback>(relaxed = true)
        val task: Task<LocationAvailability> = mockk()
        every { task.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<LocationAvailability>>(0)
            val locationAvailability = mockk<LocationAvailability> {
                every { isLocationAvailable } returns true
            }
            listener.onSuccess(locationAvailability)
            task
        }
        every { fusedLocationProviderClient.locationAvailability } returns task
        val location = Location("provider")
        location.latitude = 10.0
        location.longitude = 20.0
        location.time = System.currentTimeMillis()
        location.accuracy = 10.5f
        val locationResult = LocationResult.create(listOf(location))
        every {
            fusedLocationProviderClient.requestLocationUpdates(
                any(), ofType(LocationCallback::class), ofType(Looper::class)
            )
        } answers {
            val callbackLambda = it.invocation.args[1] as LocationCallback

            callbackLambda.onLocationResult(locationResult)

            mockk<Task<Void>>()
        }


        runBlocking {
            locationClient.start(locationTrackingCallback)
            locationClient.stop()
            assertFalse(locationClient.isTrackingInForeground())
        }
    }


    @Test
    fun `start and stop location tracking on location availability`() {
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        val locationTrackingCallback = mockk<LocationTrackingCallback>(relaxed = true)
        val task: Task<LocationAvailability> = mockk()
        every { task.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<LocationAvailability>>(0)
            val locationAvailability = mockk<LocationAvailability> {
                every { isLocationAvailable } returns true
            }
            listener.onSuccess(locationAvailability)
            task
        }
        every { fusedLocationProviderClient.locationAvailability } returns task
        val location = Location("provider")
        location.latitude = 10.0
        location.longitude = 20.0
        location.time = System.currentTimeMillis()
        location.accuracy = 10.5f
        val locationResult = mockk<LocationAvailability>()
        every {
            locationResult.isLocationAvailable
        } returns true

        every {
            fusedLocationProviderClient.requestLocationUpdates(
                any(), ofType(LocationCallback::class), ofType(Looper::class)
            )
        } answers {
            val callbackLambda = it.invocation.args[1] as LocationCallback

            callbackLambda.onLocationAvailability(locationResult)

            mockk<Task<Void>>()
        }


        runBlocking {
            locationClient.start(locationTrackingCallback)
            locationClient.stop()
            assertFalse(locationClient.isTrackingInForeground())
        }
    }

    @Test
    fun `start and stop location background tracking`() {
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        val task: Task<LocationAvailability> = mockk()
        every { task.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<LocationAvailability>>(0)
            val locationAvailability = mockk<LocationAvailability> {
                every { isLocationAvailable } returns true
            }
            listener.onSuccess(locationAvailability)
            task
        }
        every { fusedLocationProviderClient.locationAvailability } returns task
        every {
            fusedLocationProviderClient.requestLocationUpdates(
                any(), ofType(LocationCallback::class), ofType(Looper::class)
            )
        } returns mockk()
        locationClient.startBackgroundLocationUpdates()
        locationClient.stopBackgroundLocationUpdates()

        verify {
            anyConstructed<EncryptedSharedPreferences>().put(
                StoreKey.BG_TRACKING_IN_PROGRESS, false.toString()
            )
        }
    }

    @Test
    fun `start and stop location background tracking without fused location`() {
        every { anyConstructed<Helper>().isGooglePlayServicesAvailable(any()) } returns false
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        val task: Task<LocationAvailability> = mockk()
        every { task.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<LocationAvailability>>(0)
            val locationAvailability = mockk<LocationAvailability> {
                every { isLocationAvailable } returns true
            }
            listener.onSuccess(locationAvailability)
            task
        }
        every { fusedLocationProviderClient.locationAvailability } returns task
        every {
            fusedLocationProviderClient.requestLocationUpdates(
                any(), ofType(LocationCallback::class), ofType(Looper::class)
            )
        } returns mockk()
        locationClient.startBackgroundLocationUpdates()
        locationClient.stopBackgroundLocationUpdates()

        verify {
            anyConstructed<EncryptedSharedPreferences>().put(
                StoreKey.BG_TRACKING_IN_PROGRESS, false.toString()
            )
        }
    }

    @Test
    fun `get Device Location`() {
        val locationTrackingCallback = mockk<LocationTrackingCallback>(relaxed = true)
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        runBlocking {
            val location = locationClient.getDeviceLocation(locationTrackingCallback)
            assertNotNull(location)
        }
    }

    @Test
    fun `evaluate geofence`() {
        val mockAmazonLocationClient = mockk<LocationClient>()
        coEvery { locationCredentialsProvider.isCredentialsValid() } returns true
        coEvery { locationCredentialsProvider.verifyAndRefreshCredentials() } just runs
        mockkConstructor(LocationTracker::class)
        every { anyConstructed<LocationTracker>().getLocationClient() } returns mockAmazonLocationClient

        val batchEvaluateGeofencesResponse = BatchEvaluateGeofencesResponse.invoke {
            errors = listOf()
        }
        coEvery {
            mockAmazonLocationClient.batchEvaluateGeofences(any())
        } returns batchEvaluateGeofencesResponse

        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        runBlocking {
            val location = locationClient.batchEvaluateGeofences(
                listOf(
                    LocationEntry(
                        id = 11,
                        latitude = 23.55555,
                        longitude = 27.3333,
                        time = 5444541584584,
                        accuracy = 12.0F
                    )
                ),
                "testDeviceId",
                TEST_IDENTITY_POOL_ID,
                "test-collection"
            )
            assertNotNull(location)
        }
    }

    @Test
    fun `get Tracker Device Location`() {
        val mockAmazonLocationClient = mockk<LocationClient>()
        coEvery { locationCredentialsProvider.isCredentialsValid() } returns true
        coEvery { locationCredentialsProvider.verifyAndRefreshCredentials() } just runs
        mockkConstructor(LocationTracker::class)
        every { anyConstructed<LocationTracker>().getLocationClient() } returns mockAmazonLocationClient

        val mockGetDevicePositionResult = GetDevicePositionResponse.invoke {
            position = listOf(27.51551, 23.5444)
            sampleTime = Instant.now()
            accuracy = PositionalAccuracy.invoke { horizontal = 10.0 }
            receivedTime = Instant.now()
        }
        coEvery {
            mockAmazonLocationClient.getDevicePosition(any())
        } returns mockGetDevicePositionResult

        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        runBlocking {
            val location = locationClient.getTrackerDeviceLocation()
            assertNotNull(location)
        }
    }

    @Test
    fun `uploadLocationUpdates uploads locations and handles callbacks`() {
        val mockAmazonLocationClient = mockk<LocationClient>()
        coEvery { locationCredentialsProvider.isCredentialsValid() } returns true
        coEvery { locationCredentialsProvider.verifyAndRefreshCredentials() } just runs
        mockkConstructor(LocationTracker::class)
        every { anyConstructed<LocationTracker>().getLocationClient() } returns mockAmazonLocationClient

        val result = BatchUpdateDevicePositionResponse.invoke {
            errors = listOf()
        }
        coEvery {
            mockAmazonLocationClient.batchUpdateDevicePosition(any())
        } returns result
        val locationTrackingCallback = mockk<LocationTrackingCallback>(relaxed = true)
        val awsKeyValueStore = mockk<EncryptedSharedPreferences>()

        every { awsKeyValueStore.get(any()) } returns "true"
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        locationClient.enableFilter(TimeLocationFilter())
        locationClient.enableFilter(DistanceLocationFilter())
        locationClient.enableFilter(AccuracyLocationFilter())
        every {
            anyConstructed<EncryptedSharedPreferences>().get(
                StoreKey.IS_ACCURACY_FILTER_ENABLE,
            )
        } returns true.toString()
        every { anyConstructed<EncryptedSharedPreferences>().get(StoreKey.LAST_LOCATION) } returns LAST_LOCATION
        every { anyConstructed<Location>().distanceTo(any()) } returns 20F
        runBlocking {
            locationClient.uploadLocationUpdates(locationTrackingCallback)

            verify { locationTrackingCallback.onUploadStarted(match { it.size == 1 }) }
            verify { locationTrackingCallback.onUploaded(match { it.size == 1 }) }
        }
    }

    @Test
    fun `uploadLocationUpdates uploads locations skipped`() {
        val locationTrackingCallback = mockk<LocationTrackingCallback>(relaxed = true)
        val awsKeyValueStore = mockk<EncryptedSharedPreferences>()

        every { awsKeyValueStore.get(any()) } returns "true"
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        locationClient.enableFilter(TimeLocationFilter())
        locationClient.enableFilter(DistanceLocationFilter())
        locationClient.enableFilter(AccuracyLocationFilter())
        every {
            anyConstructed<EncryptedSharedPreferences>().get(
                StoreKey.IS_ACCURACY_FILTER_ENABLE,
            )
        } returns true.toString()
        every { anyConstructed<EncryptedSharedPreferences>().get(StoreKey.LAST_LOCATION) } returns LAST_LOCATION
        every { anyConstructed<Location>().distanceTo(any()) } returns 5F
        runBlocking {
            locationClient.uploadLocationUpdates(locationTrackingCallback)

            verify { locationTrackingCallback.onUploadSkipped(match { true }) }
        }
    }

    @Test
    fun `enable Filter`() {
        coroutineScope.launch {
            val locationClient =
                LocationTracker(context, locationCredentialsProvider, locationClientConfig)
            locationClient.enableFilter(TimeLocationFilter())
            locationClient.enableFilter(DistanceLocationFilter())
            locationClient.enableFilter(AccuracyLocationFilter())
            verify {
                anyConstructed<EncryptedSharedPreferences>().put(
                    StoreKey.IS_ACCURACY_FILTER_ENABLE, true.toString()
                )
            }
        }
    }

    @Test
    fun `disable Filter`() {
        coroutineScope.launch {
            val locationClient =
                LocationTracker(context, locationCredentialsProvider, locationClientConfig)
            locationClient.disableFilter(TimeLocationFilter())
            locationClient.disableFilter(DistanceLocationFilter())
            locationClient.disableFilter(AccuracyLocationFilter())
            verify {
                anyConstructed<EncryptedSharedPreferences>().put(
                    StoreKey.IS_ACCURACY_FILTER_ENABLE, false.toString()
                )
            }
        }
    }

    @Test
    fun testStartBackground_ActiveTracking() {
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)

        locationClient.startBackground(BackgroundTrackingMode.ACTIVE_TRACKING, serviceCallback)
        assertEquals(serviceCallback, BackgroundLocationService.serviceCallback)
    }

    @Test
    fun testStartBackground_InactiveTracking() {
        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)
        mockkObject(BackgroundTrackingWorker)

        every { BackgroundTrackingWorker.enqueueWork(context) } just runs

        locationClient.startBackground(
            BackgroundTrackingMode.BATTERY_SAVER_TRACKING, serviceCallback
        )
        verify { BackgroundTrackingWorker.enqueueWork(context) }
    }

    @Test
    fun `stopBackgroundService stops service and cancels work if running`() {
        mockkObject(BackgroundTrackingWorker)
        every { BackgroundTrackingWorker.isWorkRunning(context) } returns true
        every { BackgroundTrackingWorker.cancelWork(context) } just runs

        val locationClient =
            LocationTracker(context, locationCredentialsProvider, locationClientConfig)

        locationClient.stopBackgroundService()
        verify { BackgroundTrackingWorker.cancelWork(context) }
    }
}
