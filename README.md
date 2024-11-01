# Amazon Location Service Mobile Tracking SDK for Android

These utilities help you when making [Amazon Location Service](https://aws.amazon.com/location/) API calls from your Android applications. This library uses the AWS SDK to call tracking APIs.

## Installation

This tracking SDK works with the overall AWS SDK and the Amazon Location Authentication SDK. All SDKs are published to Maven Central.
Check the latest version of [auth SDK](https://mvnrepository.com/artifact/software.amazon.location/auth) and [tracking SDK](https://mvnrepository.com/artifact/software.amazon.location/tracking) on Maven Central.

Add the following lines to the dependencies section of your build.gradle file in Android Studio:

``` gradle
implementation("software.amazon.location:tracking:1.0.0")
implementation("software.amazon.location:auth:1.1.0")
implementation("aws.sdk.kotlin:location:1.3.65")
```

## Functions

These are the functions available in this SDK:

<table>
<tr><th>Class</th><th>Function</th><th>Description</th></tr>

<tr><td>LocationTracker</td><td>
constructor(context: Context,locationCredentialsProvider: LocationCredentialsProvider,trackerName: String)
<br/>
or
<br/>
constructor(context: Context,locationCredentialsProvider: LocationCredentialsProvider,clientConfig: LocationTrackerConfig)
</td><td>This is an initializer function to create a LocationTracker object. It requires LocationCredentialsProvide and trackerName and an optional LocationTrackingConfig. If config is not provided it will be initialized with default values</td></tr>

<tr><td>LocationTracker</td><td>start(locationTrackingCallback: LocationTrackingCallback)</td><td>Starts the process of accessing the user's location and sending it to the AWS tracker</td></tr>

<tr><td>LocationTracker</td><td>isTrackingInForeground()</td><td>Checks if location tracking is currently in progress.</td></tr>

<tr><td>LocationTracker</td><td>stop()</td><td>Stops the process of tracking the user's location</td></tr>

<tr><td>LocationTracker</td><td>startBackground(mode: BackgroundTrackingMode, serviceCallback: ServiceCallback)</td><td>Starts the process of accessing the user's location and sending it to the AWS tracker while the application is in the background.
BackgroundTrackingMode has the following options:
<br/>
- <b>ACTIVE_TRACKING:</b> This option actively tracking user's location updates<br/>
- <b>BATTERY_SAVER_TRACKING:</b> This option tracking user's location updates every 15 minutes<br/>
</td></tr>

<tr><td>LocationTracker</td><td>stopBackgroundService()</td><td>Stops the process of accessing the user's location and sending it to the AWS tracker while the application is in the background.</td></tr>

<tr><td>LocationTracker</td><td>getTrackerDeviceLocation() </td><td>Retrieves the device location from AWS location services.</td></tr>

<tr><td>LocationTracker</td><td>getDeviceLocation(locationTrackingCallback: LocationTrackingCallback?) </td><td>Retrieves the current device location from the fused location provider client and uploads it to AWS location tracker.</td></tr>

<tr><td>LocationTracker</td><td>uploadLocationUpdates(locationTrackingCallback: LocationTrackingCallback?) </td><td>Uploads the device location to AWS location services after filtering based on the configured location filters.</td></tr>

<tr><td>LocationTracker</td><td>enableFilter(filter: LocationFilter) </td><td>Enables particular location filter.</td></tr>

<tr><td>LocationTracker</td><td>checkFilterIsExistsAndUpdateValue(filter: LocationFilter) </td><td>Checks filter exists in Location tracker config if not add one.</td></tr>

<tr><td>LocationTracker</td><td>disableFilter(filter: LocationFilter) </td><td>Disable particular location filter. </td></tr>

<tr><td>LocationTrackerConfig</td><td>
LocationTrackerConfig(
    // Required
    var trackerName: String,
    // Optional
    var locationFilters: MutableList<LocationFilter> = mutableListOf(
        TimeLocationFilter(),
        DistanceLocationFilter(),
    ),
    var logLevel: TrackingSdkLogLevel = TrackingSdkLogLevel.DEBUG,
    var accuracy: Int = Priority.PRIORITY_HIGH_ACCURACY,
    var latency: Long = 1000,
    var frequency: Long = 1500,
    var waitForAccurateLocation: Boolean = false,
    var minUpdateIntervalMillis: Long = 1000,
    var persistentNotificationConfig: NotificationConfig = NotificationConfig()
)
</td><td>This initializes the LocationTrackerConfig with user-defined parameter values. If a parameter value is not provided, it will be set to a default value</td></tr>

<tr><td>LocationFilter</td><td>shouldUpload(currentLocation: LocationEntry, previousLocation: LocationEntry?): Boolean</td><td>The LocationFilter is a protocol that users can implement for their custom filter implementation. A user would need to implement `shouldUpload` function to compare previous and current location and return if the current location should be uploaded</td></tr>

</table>

## Prerequisite for background location

To utilize background location, include the following permission and service entries within your `AndroidManifest.xml` file.

``` xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<application>
    <service
        android:name="software.amazon.location.tracking.providers.BackgroundLocationService"
        android:enabled="true"
        android:exported="true"
        android:foregroundServiceType="location" />
</application>
```

## Usage

Import the following classes in your code:

``` kotlin
import software.amazon.location.tracking.LocationTracker
import software.amazon.location.tracking.config.LocationTrackerConfig
import software.amazon.location.tracking.util.TrackingSdkLogLevel

import aws.sdk.kotlin.services.location.LocationClient

import software.amazon.location.auth.AuthHelper
import software.amazon.location.auth.LocationCredentialsProvider
```

Create an `AuthHelper` as we need `LocationCredentialsProvider` for creating `LocationTracker`:

``` kotlin
// Create an authentication helper using credentials from Cognito
private fun exampleCognitoLogin() {
    val authHelper = AuthHelper(applicationContext)
    val locationCredentialsProvider : LocationCredentialsProvider = authHelper.authenticateWithCognitoIdentityPool("My-Cognito-Identity-Pool-Id")
}

OR

// Create a credentail provider using custom credential provider with AuthHelper
private fun exampleCustomCredentialLogin() {
    var authHelper = AuthHelper(applicationContext)
    var locationCredentialsProvider : LocationCredentialsProvider = authHelper.authenticateWithCredentialsProvider("MY-AWS-REGION", "MY-CUSTOM-CREDENTIAL-PROVIDER")
}
```

Use the `LocationCredentialsProvider` and `LocationTrackerConfig` to create a `LocationTracker`:

``` kotlin
val config = LocationTrackerConfig(
    trackerName = "MY-TRACKER-NAME",
    logLevel = TrackingSdkLogLevel.DEBUG,
    accuracy = Priority.PRIORITY_HIGH_ACCURACY,
    latency = 1000,
    frequency = 5000,
    waitForAccurateLocation = false,
    minUpdateIntervalMillis = 5000,
)
locationTracker = LocationTracker(
    applicationContext,
    locationCredentialsProvider,
    config,
)
```

You can use the `LocationTracker` to get the device's location and upload it:

``` kotlin
locationTracker?.getDeviceLocation(object :LocationTrackingCallback{
      override fun onLocationReceived(location: LocationEntry) {
      }

      override fun onUploadStarted(entries: List<LocationEntry>) {
      }

      override fun onUploaded(entries: List<LocationEntry>) {
      }

      override fun onUploadSkipped(entries: LocationEntry) {
      }

      override fun onLocationAvailabilityChanged(locationAvailable: Boolean) {
      }
})
```

You can use the `LocationTracker` to start and stop tracking in the foreground. Here is an example:

``` kotlin
// Starting location tracking
locationTracker?.start(object :LocationTrackingCallback{
      override fun onLocationReceived(location: LocationEntry) {
      }

      override fun onUploadStarted(entries: List<LocationEntry>) {
      }

      override fun onUploaded(entries: List<LocationEntry>) {
      }

      override fun onUploadSkipped(entries: LocationEntry) {
      }

      override fun onLocationAvailabilityChanged(locationAvailable: Boolean) {
      }
})

// Stopping location tracking
locationTracker?.stop()
```

You can also use the `LocationTracker` to start and stop tracking in the background. Here is an example:

``` kotlin
// Starting location tracking
locationTracker?.startBackground(
    BackgroundTrackingMode.ACTIVE_TRACKING,
    object : ServiceCallback {
        override fun serviceStopped() {
            if (selectedTrackingMode == BackgroundTrackingMode.ACTIVE_TRACKING) {
                isLocationTrackingBackgroundActive = false
            } else {
                isLocationTrackingBatteryOptimizeActive = false
            }
        }
    },
)

// Stopping location tracking
locationTracker?.stopBackgroundService()
```

## How to use filters

The Amazon Location Service Mobile Tracking SDK includes three location filters:

* TimeLocationFilter: Filters location updates based on a defined time interval.
* DistanceLocationFilter: Filters location updates based on a specified distance threshold.
* AccuracyLocationFilter: Filters location updates by comparing the distance moved since the last update with the current location's accuracy.

Filters can be added when the tracker is initially configured. They can also be adjusted during runtime. Here is an example of using a filter in the initial configuration:

``` kotlin
val config = LocationTrackerConfig(
    trackerName = "MY-TRACKER-NAME",
    logLevel = TrackingSdkLogLevel.DEBUG,
    accuracy = Priority.PRIORITY_HIGH_ACCURACY,
    latency = 1000,
    frequency = 5000,
    waitForAccurateLocation = false,
    minUpdateIntervalMillis = 5000,
    locationFilters = mutableListOf(TimeLocationFilter(), DistanceLocationFilter(), AccuracyLocationFilter())
)
locationTracker = LocationTracker(
    applicationContext,
    locationCredentialsProvider,
    config,
)
```

Custom filters can be added when the tracker is initially configured. Here is an example of using a custom filter in the initial configuration:

``` kotlin
// Custom filter added to the location filters list. This filter checks if the current location's time corresponds to Monday.
val config = LocationTrackerConfig(
    trackerName = "MY-TRACKER-NAME",
    logLevel = TrackingSdkLogLevel.DEBUG,
    accuracy = Priority.PRIORITY_HIGH_ACCURACY,
    latency = 1000,
    frequency = 5000,
    waitForAccurateLocation = false,
    minUpdateIntervalMillis = 5000,
    persistentNotificationConfig = NotificationConfig(
        notificationImageId = R.drawable.ic_drive,
    ),
    locationFilters = mutableListOf(object : LocationFilter{
            override fun shouldUpload(
                currentLocation: LocationEntry,
                previousLocation: LocationEntry?
            ): Boolean {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = currentLocation.time

                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

                return dayOfWeek == Calendar.MONDAY
            }
        }
    )
)
locationTracker = LocationTracker(
    applicationContext,
    locationCredentialsProvider,
    config,
)
```

Filters can be enabled or disabled at runtime with `LocationTracker`. Here is an example:

``` kotlin
// To enable the filter
locationTracker?.enableFilter(TimeLocationFilter())

// To disable the filter
locationTracker?.disableFilter(TimeLocationFilter())
```

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## Getting Help

The best way to interact with our team is through GitHub.
You can [open an issue](https://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android/issues/new/choose) and choose from one of our templates for
[bug reports](https://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android/issues/new?assignees=&labels=bug%2C+needs-triage&template=---bug-report.md&title=),
[feature requests](https://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android/issues/new?assignees=&labels=feature-request&template=---feature-request.md&title=)
or [guidance](https://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android/issues/new?assignees=&labels=guidance%2C+needs-triage&template=---questions---help.md&title=).
If you have a support plan with [AWS Support](https://aws.amazon.com/premiumsupport/), you can also create a new support case.

## Contributing

We welcome community contributions and pull requests. See [CONTRIBUTING.md](https://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android/blob/master/CONTRIBUTING.md) for information on how to set up a development environment and submit code.

## License

The Amazon Location Service Mobile Tracking SDK for Android is distributed under the
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0),
see LICENSE.txt and NOTICE.txt for more information.
