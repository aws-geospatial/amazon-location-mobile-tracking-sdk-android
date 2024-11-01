package software.amazon.location.tracking

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.room.Room
import aws.sdk.kotlin.services.location.LocationClient
import aws.sdk.kotlin.services.location.model.BatchEvaluateGeofencesResponse
import aws.sdk.kotlin.services.location.model.ResourceNotFoundException
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import software.amazon.location.auth.EncryptedSharedPreferences
import software.amazon.location.auth.LocationCredentialsProvider
import software.amazon.location.tracking.aws.AmazonTrackingHttpClient
import software.amazon.location.tracking.aws.LocationTrackingCallback
import software.amazon.location.tracking.config.LocationTrackerConfig
import software.amazon.location.tracking.config.SdkConfig.DEFAULT_ACCURACY
import software.amazon.location.tracking.database.LocationDatabase
import software.amazon.location.tracking.database.LocationEntry
import software.amazon.location.tracking.filters.AccuracyLocationFilter
import software.amazon.location.tracking.filters.DistanceLocationFilter
import software.amazon.location.tracking.filters.LocationFilter
import software.amazon.location.tracking.filters.LocationFilterAdapter
import software.amazon.location.tracking.filters.TimeLocationFilter
import software.amazon.location.tracking.providers.BackgroundLocationService
import software.amazon.location.tracking.providers.BackgroundTrackingWorker
import software.amazon.location.tracking.providers.LocationProvider
import software.amazon.location.tracking.util.BackgroundTrackingMode
import software.amazon.location.tracking.util.Logger
import software.amazon.location.tracking.util.ServiceCallback
import software.amazon.location.tracking.util.StoreKey
import software.amazon.location.tracking.util.StoreKey.IS_ACCURACY_FILTER_ENABLE
import software.amazon.location.tracking.util.StoreKey.IS_DISTANCE_FILTER_ENABLE
import software.amazon.location.tracking.util.StoreKey.IS_TIME_FILTER_ENABLE

private const val DB_NAME = "location_database"

private const val PREFS_NAME = "software.amazon.location.tracking.client"

/**
 * Manages location-related functionality, including tracking and updating device location with AWS location services.
 */
class LocationTracker {
    private var clientConfig: LocationTrackerConfig
    private var locationClient: LocationClient

    constructor(
        context: Context,
        locationCredentialsProvider: LocationCredentialsProvider,
    ) : this(
        context,
        locationCredentialsProvider,
        clientConfig = GsonBuilder()
            .registerTypeAdapter(LocationFilter::class.java, LocationFilterAdapter())
            .create().fromJson(
                EncryptedSharedPreferences(
                    context,
                    PREFS_NAME
                ).apply {
                    initEncryptedSharedPreferences()
                }.get(StoreKey.CLIENT_CONFIG) ?: throw Exception("Client config not found"),
                LocationTrackerConfig::class.java
            )
    )

    constructor(
        context: Context,
        locationCredentialsProvider: LocationCredentialsProvider,
        trackerName: String,
    ) : this(
        context,
        locationCredentialsProvider,
        LocationTrackerConfig(trackerName = trackerName),
    )

    constructor(
        context: Context,
        locationCredentialsProvider: LocationCredentialsProvider,
        clientConfig: LocationTrackerConfig,
    ) {
        this.locationClient = LocationClient(locationCredentialsProvider.getLocationClientConfig())
        this.context = context
        this.clientConfig = clientConfig
        this.locationCredentialsProvider = locationCredentialsProvider
        this.coroutineScope = CoroutineScope(Dispatchers.Default)
        Logger.logLevel = clientConfig.logLevel
        httpClient =
            AmazonTrackingHttpClient(context, clientConfig.trackerName)
        locationProvider = LocationProvider(context, clientConfig)
        database = Room.databaseBuilder(
            context,
            LocationDatabase::class.java,
            DB_NAME,
        ).build()
        securePreferences = EncryptedSharedPreferences(context, PREFS_NAME)
        securePreferences?.initEncryptedSharedPreferences()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                Logger.log("Fetched location ${locationResult.locations}")
                coroutineScope.launch {
                    val saveLocations = async {
                        locationResult.locations.forEach {
                            val locationEntry = toLocationEntry(it)

                            locationTrackingCallback?.onLocationReceived(locationEntry)
                            saveLocationToDisk(locationEntry)
                        }
                    }
                    saveLocations.await()
                    uploadLocationUpdates(locationTrackingCallback)
                }
            }

            override fun onLocationAvailability(p0: LocationAvailability) {
                super.onLocationAvailability(p0)
                locationTrackingCallback?.onLocationAvailabilityChanged(p0.isLocationAvailable)
            }
        }
        cacheClientConfig()
    }

    private fun cacheClientConfig() {
        val gson = GsonBuilder()
            .registerTypeAdapter(LocationFilter::class.java, LocationFilterAdapter())
            .create()
        securePreferences?.put(StoreKey.CLIENT_CONFIG, gson.toJson(clientConfig))
    }

    private var locationTrackingCallback: LocationTrackingCallback? = null
    private var locationCredentialsProvider: LocationCredentialsProvider? = null
    private var locationProvider: LocationProvider
    private var httpClient: AmazonTrackingHttpClient
    private var database: LocationDatabase
    private val coroutineScope: CoroutineScope
    private var securePreferences: EncryptedSharedPreferences? = null
    private var locationCallback: LocationCallback
    private var context: Context? = null

    /**
     * Subscribes to location updates and processes them using the specified callback.
     * @param locationTrackingCallback Callback to handle location updates.
     */
    @RequiresPermission(anyOf = ["android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"])
    fun start(locationTrackingCallback: LocationTrackingCallback) {
        this.locationTrackingCallback = locationTrackingCallback
        securePreferences?.put(StoreKey.TRACKING_IN_PROGRESS, true.toString())
        locationProvider.subscribeToLocationUpdates(locationCallback)
    }

    /**
     * Subscribes to background location updates and processes them using the specified callback.
     */
    @RequiresPermission(anyOf = ["android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"])
    fun startBackgroundLocationUpdates() {
        securePreferences?.put(StoreKey.BG_TRACKING_IN_PROGRESS, true.toString())
        locationProvider.subscribeToLocationUpdates(locationCallback)
    }

    /**
     * Checks if location tracking is currently in progress.
     * @return True if tracking is in progress; false otherwise.
     */
    fun isTrackingInForeground(): Boolean {
        return securePreferences?.get(StoreKey.TRACKING_IN_PROGRESS)?.toBoolean() ?: false
    }

    /**
     * Unsubscribes from location updates, stopping the location tracking.
     */
    @RequiresPermission(anyOf = ["android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"])
    fun stop() {
        securePreferences?.put(StoreKey.TRACKING_IN_PROGRESS, false.toString())
        locationProvider.unsubscribeFromLocationUpdates(locationCallback)
    }

    /**
     * Unsubscribes from background location updates, stopping the location tracking.
     */
    @RequiresPermission(anyOf = ["android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"])
    fun stopBackgroundLocationUpdates() {
        securePreferences?.put(StoreKey.BG_TRACKING_IN_PROGRESS, false.toString())
        locationProvider.unsubscribeFromLocationUpdates(locationCallback)
    }

    /**
     * Uploads the device location to AWS location services after filtering based on the configured location filters.
     * @param locationTrackingCallback Callback to handle the completion or failure of the location update.
     */
    suspend fun uploadLocationUpdates(locationTrackingCallback: LocationTrackingCallback?) {
        val allEntries = database.locationEntryDao().getAllEntries().sortedBy { it.time }
        Logger.log("Locations remaining before filters: ${allEntries.size}")

        val locationsToUpload = allEntries.filter { location ->
            clientConfig.locationFilters.all { filter ->
                val isFilterEnabled = when (filter) {
                    is TimeLocationFilter -> securePreferences?.get(IS_TIME_FILTER_ENABLE)?.toBoolean() ?: false
                    is DistanceLocationFilter -> securePreferences?.get(IS_DISTANCE_FILTER_ENABLE)?.toBoolean() ?: false
                    is AccuracyLocationFilter -> securePreferences?.get(IS_ACCURACY_FILTER_ENABLE)?.toBoolean() ?: false
                    else -> true
                }
                if (isFilterEnabled) {
                    val shouldUpload = filter.shouldUpload(location, getLastKnownLocation())
                    if (!shouldUpload) {
                        Logger.log("Location filtered out: $location by filter: ${filter.javaClass.name}")
                        locationTrackingCallback?.onUploadSkipped(location)
                        database.locationEntryDao().deleteEntryById(location.id)
                    }
                    shouldUpload
                } else {
                    true
                }
            }
        }.also { filteredLocations ->
            if (filteredLocations.isEmpty()) {
                Logger.log("No entries to upload. All filtered out")
            } else {
                Logger.log("Locations to upload: ${filteredLocations.size}")
            }
        }

        locationsToUpload.chunked(10).forEach { chunk ->
            locationTrackingCallback?.onUploadStarted(chunk)
            uploadLocationUpdates(chunk.toTypedArray())
            locationTrackingCallback?.onUploaded(chunk)
        }

        val remainingLocations = database.locationEntryDao().getAllEntries()
        Logger.log("Locations remaining: $remainingLocations")
    }

    /**
     * Retrieves the current device location from the fused location provider client.
     * @return The current device Location or null if permission is not granted or location is not available.
     */
    @RequiresPermission(anyOf = ["android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"])
    suspend fun getDeviceLocation(locationTrackingCallback: LocationTrackingCallback?): Location? {
        val location = locationProvider.getDeviceLocation()
        location?.let {
            coroutineScope.launch {
                val saveLocation = async { saveLocationToDisk(toLocationEntry(it)) }
                saveLocation.await()
                uploadLocationUpdates(locationTrackingCallback)
            }
        }
        return location
    }

    fun getLocationClient():LocationClient {
        return locationClient
    }

    /**
     * Retrieves the device location from AWS location services.
     * @return The device Location from AWS or null if the device location is not found or an error occurs.
     */
    suspend fun getTrackerDeviceLocation(): Location? {
        validateAndRefreshLocationCredentials()
        return try {
            val deviceLocation = httpClient.getTrackerDeviceLocation(
                getLocationClient(),
            )
            deviceLocation.let {
                Location("").apply {
                    latitude = it.position[1]
                    longitude = it.position[0]
                    time = it.sampleTime.epochMilliseconds
                    accuracy = it.accuracy?.horizontal?.toFloat() ?: 0f
                }
            }
        } catch (e: ResourceNotFoundException) {
            Logger.log("records not found for given deviceId", e)
            null
        }
    }

    /**
     * Evaluates a batch of geofences for a given geofence collection name and location.
     *
     * This method refreshes the location credentials if necessary and then attempts to evaluate the geofences
     * using the provided HTTP client. If an exception occurs during the process, it logs the error and returns null.
     *
     * @param locationEntry the list of LocationEntry objects representing the device positions to evaluate
     * @param deviceId the ID of the device being evaluated
     * @param identityId the identity ID, formatted as "region:id", used for position properties
     * @param geofenceCollectionName the name of the geofence collection to evaluate against
     * @return A `BatchEvaluateGeofencesResponse` containing the results of the evaluation, or null if an error occurs
     * @throws Exception if there is an error during the evaluation process
     */
    suspend fun batchEvaluateGeofences(
        locationEntry: List<LocationEntry>,
        deviceId: String,
        identityId: String,
        geofenceCollectionName: String
    ): BatchEvaluateGeofencesResponse? {
        validateAndRefreshLocationCredentials()
        return try {
            httpClient.batchEvaluateGeofences(
                getLocationClient(),
                locationEntry,
                deviceId,
                identityId,
                geofenceCollectionName
            )
        } catch (e: Exception) {
            Logger.log("batchEvaluateGeofences failed", e)
            null
        }
    }

    /**
     * Validates and refreshes the credentials of the location client if necessary.
     *
     * This function checks whether the location credentials provider is available.
     * If the credentials are not valid, it triggers a verification and refresh process.
     *
     * @throws Exception if the location credentials provider is not available.
     */
    private suspend fun validateAndRefreshLocationCredentials() {
        if (locationCredentialsProvider == null) throw Exception("Location Credentials Provider not available")
        locationCredentialsProvider?.let {
            if (!it.isCredentialsValid()) {
                it.verifyAndRefreshCredentials()
            }
        }
    }

    /**
     * Uploads locations to AWS using the Amazon Location Service.
     */
    private suspend fun uploadLocationUpdates(locationChunk: Array<LocationEntry>) {
        validateAndRefreshLocationCredentials()
        val locationsToUpload = locationChunk.map {
            Location("LocationProvider").apply {
                latitude = it.latitude
                longitude = it.longitude
                time = it.time
            }
        }.toTypedArray()

        val result = httpClient.updateTrackerDeviceLocation(
            getLocationClient(),
            locationsToUpload,
        )

        if (result != null) {
            val entryIdsToDelete = locationChunk.map { it.id }
            database.locationEntryDao().deleteEntriesByIds(entryIdsToDelete)
            securePreferences?.put(StoreKey.LAST_LOCATION, Gson().toJson(locationChunk.last()))
        }
    }

    private fun getLastKnownLocation(): LocationEntry? =
        securePreferences?.get(StoreKey.LAST_LOCATION)?.let {
            Gson().fromJson(it, LocationEntry::class.java)
        }

    private suspend fun saveLocationToDisk(locationEntry: LocationEntry) {
        database.locationEntryDao().insert(locationEntry)
    }

    private fun toLocationEntry(it: Location): LocationEntry {
        val accuracy: Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            it.verticalAccuracyMeters
        } else {
            if (it.hasAccuracy()) {
                it.accuracy
            } else {
                DEFAULT_ACCURACY
            }
        }
        return LocationEntry(
            latitude = it.latitude,
            longitude = it.longitude,
            accuracy = accuracy,
            time = System.currentTimeMillis(),
        )
    }

    /**
     * Starts the background location service based on the provided mode.
     * @param mode The mode for background tracking.
     */
    fun startBackground(mode: BackgroundTrackingMode, serviceCallback: ServiceCallback) {
        if (mode == BackgroundTrackingMode.ACTIVE_TRACKING) {
            BackgroundLocationService.serviceCallback = serviceCallback
            val intent = Intent(context, BackgroundLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context?.startForegroundService(intent)
            } else {
                context?.startService(intent)
            }
        } else {
            context?.let { BackgroundTrackingWorker.enqueueWork(it) }
        }
    }

    /**
     * Stops the background location service.
     */
    fun stopBackgroundService() {
        context?.let {
            val intent = Intent(context, BackgroundLocationService::class.java)
            context?.stopService(intent)

            if (BackgroundTrackingWorker.isWorkRunning(it)) {
                BackgroundTrackingWorker.cancelWork(it)
            }
        }
    }

    fun enableFilter(filter: LocationFilter) {
        when (filter) {
            is TimeLocationFilter -> securePreferences?.put(IS_TIME_FILTER_ENABLE, true.toString())
            is DistanceLocationFilter -> securePreferences?.put(IS_DISTANCE_FILTER_ENABLE, true.toString())
            is AccuracyLocationFilter -> securePreferences?.put(IS_ACCURACY_FILTER_ENABLE, true.toString())
        }
        checkFilterIsExistsAndUpdateValue(filter)
    }

    fun checkFilterIsExistsAndUpdateValue(filter: LocationFilter) {
        val existingFilter = clientConfig.locationFilters.find { it::class == filter::class }
        if (existingFilter != null) {
            when (existingFilter) {
                is TimeLocationFilter -> {
                    if (filter is TimeLocationFilter) {
                        existingFilter.timeInterval = filter.timeInterval
                    }
                }
                is DistanceLocationFilter -> {
                    if (filter is DistanceLocationFilter) {
                        existingFilter.distanceThreshold = filter.distanceThreshold
                    }
                }
            }
        } else {
            clientConfig.locationFilters.add(filter)
        }
        cacheClientConfig()
    }

    fun disableFilter(filter: LocationFilter) {
        when (filter) {
            is TimeLocationFilter -> securePreferences?.put(IS_TIME_FILTER_ENABLE, false.toString())
            is DistanceLocationFilter -> securePreferences?.put(IS_DISTANCE_FILTER_ENABLE, false.toString())
            is AccuracyLocationFilter -> securePreferences?.put(IS_ACCURACY_FILTER_ENABLE, false.toString())
        }
    }
}
