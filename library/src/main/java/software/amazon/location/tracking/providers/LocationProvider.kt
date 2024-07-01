package software.amazon.location.tracking.providers

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import software.amazon.location.tracking.config.LocationTrackerConfig
import software.amazon.location.tracking.config.SdkConfig.MIN_DISTANCE
import software.amazon.location.tracking.util.Helper

/**
 * LocationProvider is a class that provides methods for retrieving the device's last known location,
 * checking location permission, and subscribing to location updates.
 * @param context The application context.
 * @param locationTrackerConfig The optional configuration for the location client.
 * @property fusedLocationClient The fused location provider client.
 * @property coroutineScope The coroutine scope.
 * @property locationTrackerConfig The optional configuration for the location client.
 */
class LocationProvider(
    context: Context,
    private var locationTrackerConfig: LocationTrackerConfig,
) {
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationManager: LocationManager? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var locationListener: LocationListener? = null
    private val helper = Helper()

    init {
        if (helper.isGooglePlayServicesAvailable(context)) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        } else {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }
    }

    /**
     * Subscribes to location updates using the provided location callback.
     *
     * The method first checks the availability of the device's location. If the location is available,
     * it requests location updates using the fused location provider client or the Android Location Manager.
     *
     * @param locationCallback the location callback to be notified of location updates
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun subscribeToLocationUpdates(locationCallback: LocationCallback) {
        coroutineScope.launch {
            if (fusedLocationClient != null) {
                subscribeToLocationUpdatesWithFusedLocationClient(locationCallback)
            } else {
                subscribeToLocationUpdatesWithLocationManager(locationCallback)
            }
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun subscribeToLocationUpdatesWithFusedLocationClient(locationCallback: LocationCallback) {
        coroutineScope.launch {
            val frequency = locationTrackerConfig.frequency
            val accuracy = locationTrackerConfig.accuracy
            val latency = locationTrackerConfig.latency
            val waitForAccurateLocation = locationTrackerConfig.waitForAccurateLocation
            val minUpdateIntervalMillis = locationTrackerConfig.minUpdateIntervalMillis
            fusedLocationClient?.locationAvailability?.addOnSuccessListener {
                if (!it.isLocationAvailable) {
                    return@addOnSuccessListener
                }

                fusedLocationClient?.requestLocationUpdates(
                    LocationRequest.Builder(accuracy, frequency)
                        .setWaitForAccurateLocation(waitForAccurateLocation)
                        .setMinUpdateIntervalMillis(minUpdateIntervalMillis)
                        .setMaxUpdateDelayMillis(latency)
                        .build(),
                    locationCallback,
                    Looper.getMainLooper(),
                )
            }
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun subscribeToLocationUpdatesWithLocationManager(locationCallback: LocationCallback) {
        val frequency = locationTrackerConfig.frequency

        locationListener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val locationResult = LocationResult.create(arrayListOf(location))
                    locationCallback.onLocationResult(locationResult)
                }

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {}
            }
        locationListener?.let {
            when (locationTrackerConfig.accuracy) {
                Priority.PRIORITY_HIGH_ACCURACY -> {
                    locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, frequency, MIN_DISTANCE, it, Looper.getMainLooper(),)
                }

                Priority.PRIORITY_BALANCED_POWER_ACCURACY, Priority.PRIORITY_LOW_POWER -> {
                    locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, frequency, MIN_DISTANCE, it, Looper.getMainLooper(),)
                }

                else -> {
                    locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, frequency, MIN_DISTANCE, it, Looper.getMainLooper(),)
                }
            }
        }
    }

    /**
     * Unsubscribes from location updates.
     *
     * @param locationCallback The [LocationCallback] used to receive location updates.
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun unsubscribeFromLocationUpdates(locationCallback: LocationCallback) {
        coroutineScope.launch {
            if (fusedLocationClient != null) {
                fusedLocationClient?.removeLocationUpdates(locationCallback)
            } else {
                locationListener?.let {
                    locationManager?.removeUpdates(it)
                }
            }
        }
    }

    /**
     * Retrieves the current location of the device.
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun getDeviceLocation(): Location? =
        if (fusedLocationClient != null) {
            getDeviceLocationWithFusedLocationClient()
        } else {
            getDeviceLocationWithLocationManager()
        }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun getDeviceLocationWithFusedLocationClient(): Location? {
        val currentLocationRequest: CurrentLocationRequest = CurrentLocationRequest.Builder()
            .setMaxUpdateAgeMillis(1000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()
        val locationResult =
            fusedLocationClient?.getCurrentLocation(
                currentLocationRequest,
                CancellationTokenSource().token,
            )?.await()
        return locationResult
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getDeviceLocationWithLocationManager(): Location? {
        return locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }
}
