/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.engine.util

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerializationException
import org.smartregister.fhircore.engine.util.extension.decodeJson
import org.smartregister.fhircore.engine.util.extension.encodeJson
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedPreferencesHelper
@Inject
constructor(@ApplicationContext val context: Context, val gson: Gson) {

    val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** @see [SharedPreferences.getString] */
    fun read(key: String, defaultValue: String?) = prefs.getString(key, defaultValue)

    /** @see [SharedPreferences.Editor.putString] */
    fun write(key: String, value: String?) {
        with(prefs.edit()) {
            putString(key, value)
            commit()
        }
    }

    /** @see [SharedPreferences.getLong] */
    fun read(key: String, defaultValue: Long) = prefs.getLong(key, defaultValue)

    /** @see [SharedPreferences.Editor.putLong] */
    fun write(key: String, value: Long) {
        val prefsEditor: SharedPreferences.Editor = prefs.edit()
        with(prefsEditor) {
            putLong(key, value)
            commit()
        }
    }

    /** @see [SharedPreferences.getBoolean] */
    fun read(key: String, defaultValue: Boolean) = prefs.getBoolean(key, defaultValue)

    /** @see [SharedPreferences.Editor.putBoolean] */
    fun write(key: String, value: Boolean) {
        with(prefs.edit()) {
            putBoolean(key, value)
            commit()
        }
    }

    /** Read any JSON object with type T */
    inline fun <reified T> read(key: String, decodeWithGson: Boolean = true): T? =
        if (decodeWithGson) {
            try {
                gson.fromJson(this.read(key, null), T::class.java)
            } catch (jsonIoException: JsonIOException) {
                Timber.e(jsonIoException)
                null
            }
        } else {
            try {
                this.read(key, null)?.decodeJson<T>()
            } catch (serializationException: SerializationException) {
                Timber.e(serializationException)
                null
            }
        }

    /** Write any object by saving it as JSON */
    inline fun <reified T> write(key: String, value: T?, encodeWithGson: Boolean = true) {
        with(prefs.edit()) {
            putString(key, if (encodeWithGson) gson.toJson(value) else value.encodeJson())
            commit()
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** This method resets/clears all existing values in the shared preferences asynchronously */
    fun resetSharedPrefs() {
        prefs.edit()?.clear()?.apply()
    }

    fun registerSharedPreferencesListener(
        onSharedPreferenceChangeListener: OnSharedPreferenceChangeListener,
    ) {
        prefs.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
    }

    fun unregisterSharedPreferencesListener(
        onSharedPreferenceChangeListener: OnSharedPreferenceChangeListener,
    ) {
        prefs.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
    }

    fun retrieveApplicationId() = read(SharedPreferenceKey.APP_ID.name, null)

    companion object {
        const val PREFS_NAME = "params"
        const val PREFS_SYNC_PROGRESS_TOTAL = "sync_progress_total"
    }

    fun saveUrls(fhirBaseUrl: String?, oauthBaseUrl: String?) {
        prefs.edit {
            putString(SharedPreferenceKey.FHIR_BASE_URL.name, fhirBaseUrl)
            putString(SharedPreferenceKey.OAUTH_BASE_URL.name, oauthBaseUrl)
        }
    }
    fun saveSiteName(siteName: String?) {
        prefs.edit {
            putString(SharedPreferenceKey.SITE_NAME.name, siteName)
        }
    }
    fun getSiteName(): String? {
       return prefs.getString(SharedPreferenceKey.SITE_NAME.name, null)
    }

    fun getFhirBaseUrl(): String {
        return prefs.getString(SharedPreferenceKey.FHIR_BASE_URL.name, null).orEmpty()
    }

    fun getFhirBaseUrlWithoutDefaultValue(): String? {
        return prefs.getString(SharedPreferenceKey.FHIR_BASE_URL.name, null)
    }

    fun getLanguageCode(): String {
        return prefs.getString(SharedPreferenceKey.KEY_LANGUAGE_CODE.name, Locale.ENGLISH.toLanguageTag())?: Locale.ENGLISH.toLanguageTag()
    }

    fun getOauthBaseUrl(): String {
        return prefs.getString(SharedPreferenceKey.OAUTH_BASE_URL.name, null).orEmpty()
    }

    fun saveTenant(tenantCode: String?, multiTenant: Boolean) {
        prefs.edit {
            putString(SharedPreferenceKey.TENANT_CODE.name, tenantCode)
            putBoolean(SharedPreferenceKey.IS_MULTI_TENANT.name, multiTenant)
        }
    }

    fun getTenantCode(): String? =
        prefs.getString(SharedPreferenceKey.TENANT_CODE.name, null)

    fun isMultiTenant(): Boolean =
        prefs.getBoolean(SharedPreferenceKey.IS_MULTI_TENANT.name, false)

    /**
     * Multi-tenant deployments share a FHIR server across tenants, so resource ids must be
     * tenant-prefixed (`<slug>-feature-flags`). Single-tenant deployments keep the bare id.
     */
    fun getFeatureFlagsResourceId(): String {
        val slug = getTenantCode()
        return if (isMultiTenant() && !slug.isNullOrEmpty()) "$slug-feature-flags"
        else "feature-flags"
    }

    fun saveLastKnownFeatureFlags(resourceId: String, flags: Map<String, Boolean>) {
        prefs.edit {
            putString(featureFlagsPrefKey(resourceId), gson.toJson(flags))
        }
    }

    fun getLastKnownFeatureFlags(resourceId: String): Map<String, Boolean> {
        val json = prefs.getString(featureFlagsPrefKey(resourceId), null) ?: return emptyMap()
        return try {
            val type = TypeToken.getParameterized(
                Map::class.java, String::class.java, java.lang.Boolean::class.java
            ).type
            gson.fromJson<Map<String, Boolean>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse persisted feature flags for %s", resourceId)
            emptyMap()
        }
    }

    private fun featureFlagsPrefKey(resourceId: String) = "FEATURE_FLAGS_$resourceId"

}
