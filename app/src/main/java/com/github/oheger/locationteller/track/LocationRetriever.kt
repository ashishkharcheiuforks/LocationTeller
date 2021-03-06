/*
 * Copyright 2019-2020 The Developers.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oheger.locationteller.track

import android.location.Location
import android.util.Log
import com.github.oheger.locationteller.server.LocationData
import com.github.oheger.locationteller.server.TimeService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.ticker
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/**
 * A helper class for retrieving an update of the current location and passing
 * this update to the server via a location updater actor.
 *
 * This class is invoked from the service responsible for tracking the
 * location. It asks the given location client for the last known location.
 * This information is then passed to the given channel.
 *
 * @param locationClient the client to obtain the last known location
 * @param locationUpdateActor the actor to pass the location to
 * @param timeService the time service
 * @param trackConfig the track configuration
 */
class LocationRetriever(
    val locationClient: FusedLocationProviderClient,
    val locationUpdateActor: SendChannel<LocationUpdate>,
    val timeService: TimeService,
    val trackConfig: TrackConfig
) {
    /**
     * Sends the last known location to the actor for updating the server. The
     * result is the duration in seconds when the next location update should
     * be scheduled.
     * @return the time period to the next location update
     */
    @ObsoleteCoroutinesApi
    suspend fun retrieveAndUpdateLocation(): Int {
        Log.i(tag, "Triggering location update.")
        val lastLocation = fetchLocation()
        val locUpdate = locationUpdateFor(lastLocation)
        locationUpdateActor.send(locUpdate)
        return locUpdate.nextTrackDelay.await()
    }

    /**
     * Fetches the last known location from the location client assigned to
     * this object.
     * @return the last known location
     */
    @ObsoleteCoroutinesApi
    private suspend fun fetchLocation(): Location? = withContext(Dispatchers.Main) {
        val timeout = trackConfig.gpsTimeout * 1000L
        val tickerChannel = ticker(timeout, timeout)
        suspendCoroutine<Location?> { cont ->
            val callback = LocationCallbackImpl(locationClient, cont, tickerChannel)
            launch {
                tickerChannel.receive()
                callback.cancelLocationUpdate()
            }
            locationClient.requestLocationUpdates(locationRequest, callback, null)
            Log.d(tag, "Requested location update.")
        }
    }

    /**
     * Converts a _Location_ object to a _LocationData_.
     * @return the _LocationData_
     */
    private fun Location.toLocationData() =
        LocationData(latitude, longitude, timeService.currentTime())

    /**
     * Returns a _LocationUpdate_ object to report the given location. The
     * location can be *null*; in this case, the special unknown location
     * instance is used.
     * @param location the location
     * @return the corresponding _LocationUpdate_ object
     */
    private fun locationUpdateFor(location: Location?): LocationUpdate {
        val locData = location?.toLocationData() ?: unknownLocation()
        return LocationUpdate(locData, location, CompletableDeferred())
    }

    /**
     * Returns a _LocationData_ object representing an unknown location. This
     * object has no valid position data set, but the time is accurate. (During
     * a location update, an invalid location is detected by the original
     * location being *null*.)
     * @return an object representing an unknown _LocationData_
     */
    private fun unknownLocation(): LocationData =
        LocationData(0.0, 0.0, timeService.currentTime())

    /**
     * An implementation of _LocationCallback_ that continues the current
     * co-routine when a location update is retrieved. It is also possible to
     * cancel waiting for an update, e.g. when a timeout occurs.
     *
     * @param locationClient the location client
     * @param cont the object to continue the co-routine
     * @param tickerChannel the channel for timer events
     */
    private class LocationCallbackImpl(
        val locationClient: FusedLocationProviderClient,
        val cont: Continuation<Location?>,
        val tickerChannel: ReceiveChannel<Unit>
    ) : LocationCallback() {
        override fun onLocationResult(result: LocationResult?) {
            Log.d(tag, "Got location result $result.")
            removeLocationUpdateRegistration()
            cont.resumeWith(Result.success(result?.lastLocation))
        }

        /**
         * Cancels the location update. This method is called when the timeout
         * for the GPS signal is reached.
         */
        fun cancelLocationUpdate() {
            Log.i(tag, "Canceling update for location.")
            removeLocationUpdateRegistration()
            cont.resumeWith(Result.success(null))
        }

        /**
         * Removes the registration for location updates. This method must be
         * called to stop the GPS client.
         */
        private fun removeLocationUpdateRegistration() {
            tickerChannel.cancel()
            locationClient.removeLocationUpdates(this)
        }
    }

    companion object {
        /** Tag for logging.*/
        private const val tag = "LocationRetriever"

        /** The interval to request updates from the location provider.*/
        private const val updateInterval = 5000L

        /** The request for a location update.*/
        private val locationRequest = LocationRequest.create().apply {
            interval = updateInterval
            fastestInterval = updateInterval
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }
}
