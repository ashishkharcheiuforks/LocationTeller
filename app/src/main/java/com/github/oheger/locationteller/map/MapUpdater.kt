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
package com.github.oheger.locationteller.map

import com.github.oheger.locationteller.server.ServerConfig
import com.github.oheger.locationteller.server.TrackService
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An object providing functionality to update a map with the most recent
 * locations loaded from the server.
 *
 * This object defines a function that loads the current status of location
 * files from the server. If there is a change compared with the current state,
 * the map is updated by adding markers for the new locations.
 */
object MapUpdater {
    /**
     * A default function for creating a _TrackService_ from a server
     * configuration. This function just directly creates the service.
     */
    val defaultTrackServerFactory: (ServerConfig) -> TrackService =
        { config -> TrackService.create(config) }

    /**
     * Updates the given map with the new state fetched from the server if
     * necessary. The updated state is returned which becomes the current
     * state for the next update.
     * @param config the server configuration
     * @param map the map to be updated
     * @param currentState the current state of location data
     * @param trackServerFactory the factory for a _TrackService_ instance
     * @return the updated state of location data
     */
    suspend fun updateMap(
        config: ServerConfig, map: GoogleMap, currentState: LocationFileState,
        trackServerFactory: (ServerConfig) -> TrackService = defaultTrackServerFactory
    ):
            LocationFileState {
        val trackService = trackServerFactory(config)
        val filesOnServer = trackService.filesOnServer()
        return if (currentState.stateChanged(filesOnServer)) {
            val knownData = createMarkerDataMap(currentState, filesOnServer, trackService)
            val newState = createNewLocationState(filesOnServer, knownData)
            updateMarkers(map, newState)
            newState
        } else currentState
    }

    /**
     * Creates a map with information about the new markers to be placed on the
     * map. Based on the delta to the last state the track service is invoked
     * to load new location data. Then the existing and new information is
     * combined.
     * @param currentState the current state of location data
     * @param filesOnServer the current list of files on the server
     * @param trackService the track service
     * @return a map with data about all known markers
     */
    private suspend fun createMarkerDataMap(
        currentState: LocationFileState,
        filesOnServer: List<String>,
        trackService: TrackService
    ): MutableMap<String, MarkerData> {
        val newFiles = currentState.filterNewFiles(filesOnServer)
        val knownData = currentState.getKnownMarkers(filesOnServer)
        if (newFiles.isNotEmpty()) {
            val newMarkers = trackService.readLocations(newFiles).mapValues { e ->
                val position = LatLng(e.value.latitude, e.value.longitude)
                MarkerData(e.value, position)
            }
            knownData.putAll(newMarkers)
        }
        return knownData
    }

    /**
     * Creates a new state object from the resolved location files loaded from
     * the server. Note that this object contains only the files for which
     * location data could be loaded successfully.
     * @param filesOnServer list of location files found on the server
     * @param markerData map with resolved marker data
     * @return the resulting new location state
     */
    private fun createNewLocationState(
        filesOnServer: List<String>,
        markerData: MutableMap<String, MarkerData>
    ) = LocationFileState(filesOnServer.filter(markerData::containsKey), markerData)

    /**
     * Draws markers on the given map according to the given state.
     * @param map the map to be updated
     * @param state the current state of location data
     */
    private suspend fun updateMarkers(map: GoogleMap, state: LocationFileState) = withContext(Dispatchers.Main) {
        map.clear()
        state.markerData.values.forEach { marker ->
            val options = MarkerOptions().position(marker.position)
            map.addMarker(options)
        }
    }
}