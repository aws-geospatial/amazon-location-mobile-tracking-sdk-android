package software.amazon.location.tracking.providers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import software.amazon.location.tracking.LocationTracker
import software.amazon.location.tracking.R
import software.amazon.location.auth.LocationCredentialsProvider
import software.amazon.location.tracking.config.LocationTrackerConfig
import software.amazon.location.tracking.filters.LocationFilter
import software.amazon.location.tracking.filters.LocationFilterAdapter
import software.amazon.location.tracking.util.ServiceCallback
import software.amazon.location.tracking.util.StoreKey
import com.google.android.gms.location.Priority
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import software.amazon.location.auth.EncryptedSharedPreferences

private const val SERVICE_STOP_ACTION: String = "STOP_SERVICE_ACTION"
private const val REQUEST_CODE_NOTIFICATION = 0
private const val PREFS_NAME = "software.amazon.location.tracking.client"

class BackgroundLocationService : Service() {

    private var locationTracker: LocationTracker? = null
    private var locationTrackerConfig: LocationTrackerConfig? = null
    private val binder = BackgroundLocationServiceBinder()

    companion object {
        var serviceCallback: ServiceCallback? = null
        var isRunning = false
    }

    inner class BackgroundLocationServiceBinder : Binder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SERVICE_STOP_ACTION) {
            stopService()
            return START_NOT_STICKY
        }
        locationTrackerConfig = GsonBuilder()
            .registerTypeAdapter(LocationFilter::class.java, LocationFilterAdapter())
            .create().fromJson(
                EncryptedSharedPreferences(this, PREFS_NAME).apply {
                    initEncryptedSharedPreferences()
                }.get(
                    StoreKey.CLIENT_CONFIG
                ) ?: throw Exception("Client config not found"),
                LocationTrackerConfig::class.java
            )
        locationTrackerConfig?.persistentNotificationConfig?.notificationId?.let {
            startForeground(it, createNotification())
        }
        locationTracker = locationTrackerConfig?.let { clientConfig ->
            clientConfig.frequency = TimeUnit.SECONDS.toMillis(30)
            clientConfig.accuracy = Priority.PRIORITY_HIGH_ACCURACY
            clientConfig.minUpdateIntervalMillis = TimeUnit.SECONDS.toMillis(30)
            applicationContext?.let {
                val locationCredentialsProvider = LocationCredentialsProvider(it)
                LocationTracker(it, locationCredentialsProvider, clientConfig)
            }
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationTracker?.startBackgroundLocationUpdates()
        }

        return START_STICKY
    }

    /**
     * Creates a notification for the service.
     * @return The notification instance.
     */
    private fun createNotification(): Notification? {
        locationTrackerConfig?.let {
            val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel(it.persistentNotificationConfig.notificationChannelId, it.persistentNotificationConfig.notificationChannelName)
                } else {
                    ""
                }
            val stopIntent = Intent(this, BackgroundLocationService::class.java).apply {
                action = SERVICE_STOP_ACTION
            }
            val notificationIntent = Intent(this, BackgroundLocationService::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE_NOTIFICATION,
                notificationIntent,
                flags
            )
            val stopPendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_NOTIFICATION,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val action = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.label_stop_service),
                stopPendingIntent
            ).build()
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle(it.persistentNotificationConfig.notificationTitle)
                .setContentText(it.persistentNotificationConfig.notificationDescription)
                .setSmallIcon(it.persistentNotificationConfig.notificationImageId)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(action)
                .build()
        }
        return null
    }

    /**
     * Creates a notification channel for the service.
     * @param channelId The notification channel ID.
     * @param channelName The notification channel name.
     * @return The created notification channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(
        channelId: String,
        channelName: String
    ): String {
        val channel =
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        return channelId
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }

    /**
     * Stops the background location service.
     * This method is called when the service needs to be stopped.
     * It stops location updates, removes the service from the foreground, and stops the service itself.
     * Additionally, it broadcasts an intent to notify listeners that the service has been stopped.
     */
    private fun stopService() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            serviceCallback?.serviceStopped()
            locationTracker?.stopBackgroundLocationUpdates()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
            isRunning = false
        }
    }
}

