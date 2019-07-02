/*
 * Copyright 2019 The Developers.
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

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import com.github.oheger.locationteller.server.CurrentTimeService
import com.github.oheger.locationteller.server.ServerConfig
import com.github.oheger.locationteller.server.TimeService
import com.github.oheger.locationteller.server.TrackService
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlin.coroutines.CoroutineContext

/**
 * A factory class for creating the actor to update location data.
 *
 * This factory is used internally by [LocationTellerService]. By providing a
 * mock implementation, a mock actor can be injected for testing purposes.
 */
class UpdaterActorFactory {
    /**
     * Creates the actor for updating location data. Result may be *null* if
     * mandatory configuration options are not set.
     * @param context the context
     * @param crScope the coroutine scope
     * @return the new actor
     */
    @ObsoleteCoroutinesApi
    fun createActor(context: Context, crScope: CoroutineScope): SendChannel<LocationUpdate>? {
        val pref = PreferenceManager.getDefaultSharedPreferences(context)
        val serverConfig = createServerConfig(pref)
        val trackConfig = createTrackConfig(pref)
        return if (serverConfig != null && trackConfig != null) {
            val trackService = TrackService.create(serverConfig)
            locationUpdaterActor(trackService, trackConfig, crScope)
        } else null
    }

    companion object {
        /** Shared preferences property for the track server URI.*/
        private const val propServerUri = "trackServerUri"

        /** Shared preferences property for the base path on the server.*/
        private const val propBasePath = "trackRelativePath"

        /** Shared preferences property for the user name.*/
        private const val propUser = "userName"

        /** Shared preferences property for the password.*/
        private const val propPassword = "password"

        /** Shared preferences property for the minimum track interval.*/
        private const val propMinTrackInterval = "minTrackInterval"

        /** Shared preferences property for the maximum track interval.*/
        private const val propMaxTrackInterval = "maxTrackInterval"

        /** Shared preferences property for the increment interval.*/
        private const val propIdleIncrement = "intervalIncrementOnIdle"

        /** Shared preferences property for the increment interval.*/
        private const val propLocationValidity = "locationValidity"

        /** Constant for an undefined numeric property.*/
        private const val undefinedNumber = -1

        /** String value of an undefined numeric property.*/
        private const val undefinedNumberStr = undefinedNumber.toString()

        /**
         * Creates a _ServerConfig_ object from the given preferences. If
         * mandatory properties are missing, result is *null*.
         * @param pref the preferences
         * @return the server configuration or *null*
         */
        private fun createServerConfig(pref: SharedPreferences): ServerConfig? {
            val serverUri = pref.getString(propServerUri, null)
            val basePath = pref.getString(propBasePath, null)
            val user = pref.getString(propUser, null)
            val password = pref.getString(propPassword, null)
            return if (serverUri == null || basePath == null || user == null || password == null) {
                return null
            } else ServerConfig(serverUri, basePath, user, password)
        }

        /**
         * Creates a _TrackConfig_ object from the given preferences. If
         * mandatory properties are missing, result is *null*.
         * @param pref the preferences
         * @return the track configuration or *null*
         */
        private fun createTrackConfig(pref: SharedPreferences): TrackConfig? {
            val minTrackInterval = pref.getNumeric(propMinTrackInterval)
            val maxTrackInterval = pref.getNumeric(propMaxTrackInterval)
            val intervalIncrementOnIdle = pref.getNumeric(propIdleIncrement)
            val locationValidity = pref.getNumeric(propLocationValidity)
            return if (minTrackInterval < 0 || maxTrackInterval < 0 || intervalIncrementOnIdle < 0 ||
                locationValidity < 0
            ) {
                return null
            } else TrackConfig(
                minTrackInterval, maxTrackInterval, intervalIncrementOnIdle,
                locationValidity
            )
        }

        /**
         * Extension function to query a numeric property from a preferences
         * object. From the settings screen, the properties are stored as
         * strings. Therefore, a conversion has to be done.
         * @param key the key to be queried
         * @return the numeric value of this key
         */
        private fun SharedPreferences.getNumeric(key: String): Int =
            getString(key, undefinedNumberStr)?.toInt() ?: undefinedNumber
    }
}

/**
 * A factory class for creating a [LocationRetriever].
 *
 * This factory is used internally by [LocationTellerService]. By providing a
 * mock implementation, a mock actor can be injected for testing purposes.
 */
class LocationRetrieverFactory {
    /**
     * Creates a new _LocationRetriever_ based on the given parameters.
     * @param context the context
     * @param updater the actor for publishing updates
     * @return the _LocationRetriever_ instance
     */
    fun createRetriever(context: Context, updater: SendChannel<LocationUpdate>): LocationRetriever =
        LocationRetriever(
            LocationServices.getFusedLocationProviderClient(context),
            updater, CurrentTimeService
        )
}

/**
 * A service class that handles location updates in background.
 *
 * An instance of this service is running when the user has enabled tracking.
 * Every time it is invoked, it checks whether a location update is now
 * possible: whether the user has enabled tracking and the configuration is
 * complete. If this is the case, a location update is triggered, and another
 * service invocation (based on the update result) is scheduled. Otherwise, the
 * service stops itself.
 *
 * @param updaterFactory the factory for creating an updater actor
 * @param retrieverFactory the factory for creating a _LocationRetriever_
 * @param timeService the time service
 */
class LocationTellerService(
    val updaterFactory: UpdaterActorFactory,
    val retrieverFactory: LocationRetrieverFactory,
    val timeService: TimeService
) : Service(), CoroutineScope {
    private val tag = "LocationTellerService"

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    /**
     * The pending intent to trigger a future service execution via the alarm
     * manager.
     */
    private lateinit var pendingIntent: PendingIntent

    /** The object that retrieves the current location.*/
    private var locationRetriever: LocationRetriever? = null

    /**
     * Creates a new instance of _LocationTellerService_ that uses default
     * factories.
     */
    constructor() : this(UpdaterActorFactory(), LocationRetrieverFactory(), ElapsedTimeService)

    @ObsoleteCoroutinesApi
    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "onCreate()")
        pendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LocationTellerService::class.java), 0
        )

        val updaterActor = updaterFactory.createActor(this, this)
        if (updaterActor != null) {
            Log.i(tag, "Configuration complete. Updater actor could be created.")
            locationRetriever = retrieverFactory.createRetriever(this, updaterActor)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand($intent, $flags, $startId)")
        tellLocation()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(tag, "onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The main function of this service. Checks whether a location update is
     * now possible. If so, it is triggered.
     */
    private fun tellLocation() = launch {
        val pref = PreferenceManager.getDefaultSharedPreferences(this@LocationTellerService)
        val retriever = locationRetriever
        if (retriever != null && pref.getBoolean("trackEnabled", false)) {
            Log.i(tag, "Triggering location update.")
            val nextUpdate = retriever.retrieveAndUpdateLocation()
            scheduleNextExecution(nextUpdate)
        } else {
            Log.i(tag, "No location update possible. Stopping service.")
            stopSelf()
        }
    }

    /**
     * Prepares the alarm manager to schedule another execution of the service
     * after the given delay.
     * @param nextUpdate the delay until the next update (in sec)
     */
    private fun scheduleNextExecution(nextUpdate: Int) {
        Log.i(tag, "Scheduling next service invocation after $nextUpdate seconds.")
        val nextUpdateTime = timeService.currentTime().currentTime + 1000L * nextUpdate
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, nextUpdateTime,
                pendingIntent
            )
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextUpdateTime, pendingIntent)
        }
    }
}
