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

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.oheger.locationteller.R
import com.github.oheger.locationteller.server.CurrentTimeService
import com.github.oheger.locationteller.server.TimeService
import com.github.oheger.locationteller.server.TrackService
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
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
     * @param preferencesHandler the preferences handler
     * @param trackConfig the track configuration
     * @param crScope the co-routine scope
     * @return the new actor
     */
    @ObsoleteCoroutinesApi
    fun createActor(
        preferencesHandler: PreferencesHandler, trackConfig: TrackConfig,
        crScope: CoroutineScope
    ): SendChannel<LocationUpdate>? {
        val serverConfig = preferencesHandler.createServerConfig()
        return if (serverConfig != null) {
            val trackService = TrackService.create(serverConfig)
            val uploadController = UploadController(
                preferencesHandler, trackService, trackConfig,
                OfflineLocationStorage(trackConfig.offlineStorageSize, trackConfig.minTrackInterval * 1000L),
                CurrentTimeService
            )
            locationUpdaterActor(uploadController, crScope)
        } else null
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
     * @param trackConfig the track configuration
     * @return the _LocationRetriever_ instance
     */
    fun createRetriever(context: Context, updater: SendChannel<LocationUpdate>, trackConfig: TrackConfig):
            LocationRetriever =
        LocationRetriever(
            LocationServices.getFusedLocationProviderClient(context),
            updater, CurrentTimeService,
            trackConfig
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
@ObsoleteCoroutinesApi
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

    /** The object providing access to shared preferences. */
    private lateinit var preferencesHandler: PreferencesHandler

    /** The object that retrieves the current location.*/
    private var locationRetriever: LocationRetriever? = null

    /**
     * Creates a new instance of _LocationTellerService_ that uses default
     * factories.
     */
    constructor() : this(UpdaterActorFactory(), LocationRetrieverFactory(), CurrentTimeService)

    @ObsoleteCoroutinesApi
    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "onCreate()")

        pendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LocationTellerService::class.java), 0
        )

        preferencesHandler = createPreferencesHandler()
        val trackConfig = preferencesHandler.createTrackConfig()
        val updaterActor = updaterFactory.createActor(preferencesHandler, trackConfig, this)
        if (updaterActor != null) {
            Log.i(tag, "Configuration complete. Updater actor could be created.")
            locationRetriever = retrieverFactory.createRetriever(this, updaterActor, trackConfig)
        }
        startForegroundService()
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
     * Obtains a builder for creating the notification required for the
     * foreground service.
     * @return the notification builder
     */
    internal fun notificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, trackChannelId)

    /**
     * Calculates the time for the next update based on the current time as
     * reported by the time service.
     * @param nextUpdate the next update time in seconds
     * @return the time when to schedule the next update
     */
    internal fun calculateNextUpdateTime(nextUpdate: Int) =
        timeService.currentTime().currentTime + 1000L * nextUpdate

    /**
     * Creates the object for accessing preferences used by this instance.
     * @return the _PreferencesHandler_
     */
    internal fun createPreferencesHandler(): PreferencesHandler =
        PreferencesHandler.create(this)

    /**
     * The main function of this service. Checks whether a location update is
     * now possible. If so, it is triggered.
     */
    private fun tellLocation() = launch {
        val retriever = locationRetriever
        if (retriever != null && preferencesHandler.isTrackingEnabled()) {
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
        val nextUpdateTime = calculateNextUpdateTime(nextUpdate)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmInfo = AlarmManager.AlarmClockInfo(nextUpdateTime, pendingIntent)
        alarmManager.setAlarmClock(alarmInfo, pendingIntent)
    }

    /**
     * Starts this service as a foreground service. This is needed to keep the
     * service active.
     */
    private fun startForegroundService() {
        val builder = notificationBuilder()
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.track_channel_title))
        startForeground(1, builder.build())
    }

    companion object {
        /** The ID of the notification channel used by this service.*/
        const val trackChannelId = "trackChannel"
    }
}
