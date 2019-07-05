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

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.github.oheger.locationteller.server.ServerConfig

/**
 * A class managing access to the preferences for the location teller
 * application.
 *
 * This class defines constants for all the properties supported and offers
 * some helper functions for accessing specific settings.
 *
 * @param preferences the managed _SharedPreferences_ instance
 */
class PreferencesHandler(val preferences: SharedPreferences) {
    /**
     * Creates a _ServerConfig_ object from the managed preferences. If
     * mandatory properties are missing, result is *null*.
     * @return the server configuration or *null*
     */
    fun createServerConfig(): ServerConfig? {
        val serverUri = preferences.getString(propServerUri, null)
        val basePath = preferences.getString(propBasePath, null)
        val user = preferences.getString(propUser, null)
        val password = preferences.getString(propPassword, null)
        return if (serverUri == null || basePath == null || user == null || password == null) {
            return null
        } else ServerConfig(serverUri, basePath, user, password)
    }

    /**
     * Creates a _TrackConfig_ object from the managed preferences. If
     * mandatory properties are missing, result is *null*.
     * @return the track configuration or *null*
     */
    fun createTrackConfig(): TrackConfig? {
        val minTrackInterval = preferences.getNumeric(propMinTrackInterval)
        val maxTrackInterval = preferences.getNumeric(propMaxTrackInterval)
        val intervalIncrementOnIdle = preferences.getNumeric(propIdleIncrement)
        val locationValidity = preferences.getNumeric(propLocationValidity)
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
     * Updates a _SharedPreferences_ object. This function obtains an editor
     * from the preferences, invokes the block on it and finally applies the
     * changes.
     * @param block the lambda to update the preferences
     */
    fun update(block: SharedPreferences.(SharedPreferences.Editor) -> Unit) {
        val editor = preferences.edit()
        block.invoke(preferences, editor)
        editor.apply()
    }

    /**
     * Checks whether tracking is currently active. This is determined by a
     * special property.
     * @return a flag whether tracking is active
     */
    fun isTrackingEnabled(): Boolean =
        preferences.getBoolean(propTrackState, false)

    /**
     * Updates the tracking enabled state in the managed preferences.
     * @param flag the new tracking state
     */
    fun setTrackingEnabled(flag: Boolean) {
        update { editor -> editor.putBoolean(propTrackState, flag) }
    }

    /**
     * Sets the preferences property for the last error to the given timestamp.
     * @param at the time when the error happened
     */
    fun recordError(at: Long) {
        update { editor -> editor.putLong(propLastError, at) }
    }

    /**
     * Sets the preferences property for the last successful update to the
     * given timestamp. The last error property is cleared.
     * @param at the time when the update happened
     */
    fun recordUpdate(at: Long) {
        update { editor ->
            editor.putLong(propLastUpdate, at)
                .remove(propLastError)
        }
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

    companion object {
        /** Shared preferences property for the track server URI.*/
        const val propServerUri = "trackServerUri"

        /** Shared preferences property for the base path on the server.*/
        const val propBasePath = "trackRelativePath"

        /** Shared preferences property for the user name.*/
        const val propUser = "userName"

        /** Shared preferences property for the password.*/
        const val propPassword = "password"

        /** Shared preferences property for the minimum track interval.*/
        const val propMinTrackInterval = "minTrackInterval"

        /** Shared preferences property for the maximum track interval.*/
        const val propMaxTrackInterval = "maxTrackInterval"

        /** Shared preferences property for the increment interval.*/
        const val propIdleIncrement = "intervalIncrementOnIdle"

        /** Shared preferences property for the increment interval.*/
        const val propLocationValidity = "locationValidity"

        /** Shared preferences property for the tracking state.*/
        const val propTrackState = "trackEnabled"

        /** Shared preferences property for the latest update of location data. */
        const val propLastUpdate = "lastUpdate"

        /** Shared preferences property for the latest error that occurred.*/
        const val propLastError = "lastError"

        /** Constant for an undefined numeric property.*/
        private const val undefinedNumber = -1

        /** String value of an undefined numeric property.*/
        private const val undefinedNumberStr = undefinedNumber.toString()

        /** A set with all properties related to configuration.*/
        private val configProps = setOf(
            propServerUri, propBasePath, propUser, propPassword,
            propMinTrackInterval, propMaxTrackInterval, propIdleIncrement, propLocationValidity
        )

        /**
         * Creates a _PreferencesHandler_ object based on the given context.
         * @param context the current context
         * @return the _PreferencesHandler_
         */
        fun create(context: Context): PreferencesHandler {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            return PreferencesHandler(pref)
        }

        /**
         * Checks whether the given property is related to the configuration
         * of the application. (Other properties contain persistent application
         * state.)
         * @param prop the property in question
         * @return *true* for a configuration property; *false* otherwise
         */
        fun isConfigProperty(prop: String): Boolean = configProps.contains(prop)
    }
}