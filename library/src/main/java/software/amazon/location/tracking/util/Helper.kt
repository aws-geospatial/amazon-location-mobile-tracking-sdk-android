package software.amazon.location.tracking.util

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability


class Helper {

    /**
     * Checks if Google Play Services are available on the device.
     *
     * @param context the context to use for checking the Google Play Services availability
     * @return true if Google Play Services are available and up-to-date, false otherwise
     */
    fun isGooglePlayServicesAvailable(context: Context): Boolean {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }
}