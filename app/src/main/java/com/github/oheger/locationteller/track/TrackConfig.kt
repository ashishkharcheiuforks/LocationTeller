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

/**
 * A data class representing the configuration for location tracking.
 *
 * @param minTrackInterval the minimum track interval (in sec)
 * @param maxTrackInterval the maximum track interval (in sec)
 * @param intervalIncrementOnIdle the value (in sec) to increase the track
 * interval if the location has not changed
 * @param locationValidity the time (in sec) how long a location should stay
 * on the server
 */
data class TrackConfig(
    val minTrackInterval: Int,
    val maxTrackInterval: Int,
    val intervalIncrementOnIdle: Int,
    val locationValidity: Int
)