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
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jetbrains.annotations.VisibleForTesting
import org.smartregister.fhircore.engine.auth.AuthCredentials
import org.smartregister.fhircore.engine.util.extension.decodeJson
import org.smartregister.fhircore.engine.util.extension.encodeJson
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSharedPreference @Inject constructor(@ApplicationContext val context: Context) {

  private val secureSharedPreferences =
    EncryptedSharedPreferences.create(
      context,
      SECURE_STORAGE_FILE_NAME,
      getMasterKey(),
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

  private fun getMasterKey() =
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

  fun saveCredentials(username: String, password: CharArray) {
    val randomSaltBytes = get256RandomBytes()

    secureSharedPreferences.edit {
      putString(
        SharedPreferenceKey.LOGIN_CREDENTIAL_KEY.name,
        AuthCredentials(
            username = username,
            salt = Base64.getEncoder().encodeToString(randomSaltBytes),
            passwordHash = password.toPasswordHash(randomSaltBytes),
          )
          .encodeJson(),
      )
    }
    clearPasswordInMemory(password)
  }

  fun deleteCredentials() =
    secureSharedPreferences.edit { remove(SharedPreferenceKey.LOGIN_CREDENTIAL_KEY.name) }

  fun retrieveSessionUsername() = retrieveCredentials()?.username

  fun retrieveCredentials(): AuthCredentials? =
    secureSharedPreferences
      .getString(SharedPreferenceKey.LOGIN_CREDENTIAL_KEY.name, null)
      ?.decodeJson<AuthCredentials>()


  fun getPractitionerUserId(): String =
    secureSharedPreferences
      .getString(SharedPreferenceKey.PRACTITIONER_USER_ID.name, null) ?: ""


  fun savePractitionerUserId(logicalId: String) {
    secureSharedPreferences.edit {
      putString(SharedPreferenceKey.PRACTITIONER_USER_ID.name, logicalId)
    }
  }

  fun updateLastSyncDataTime(timeInMillis: Long) {
    secureSharedPreferences.edit {
      putLong(SharedPreferenceKey.LAST_SYNC_DATE_TIME.name, timeInMillis)
    }
  }

  fun getLastSyncDataTime(): Long {
    return secureSharedPreferences
      .getLong(SharedPreferenceKey.LAST_SYNC_DATE_TIME.name, -1L)
  }


  fun saveSessionPin(pin: CharArray) {
    val randomSaltBytes = get256RandomBytes()
    secureSharedPreferences.edit {
      putString(
        SharedPreferenceKey.LOGIN_PIN_SALT.name,
        Base64.getEncoder().encodeToString(randomSaltBytes),
      )
      putString(SharedPreferenceKey.LOGIN_PIN_KEY.name, pin.toPasswordHash(randomSaltBytes))
    }
  }
  fun saveUrls(fhirBaseUrl: String?, oauthBaseUrl: String?) {
    secureSharedPreferences.edit {
      putString(SharedPreferenceKey.FHIR_BASE_URL.name, fhirBaseUrl)
      putString(SharedPreferenceKey.OAUTH_BASE_URL.name, oauthBaseUrl)
    }
  }

  fun saveSiteName(siteName: String?) {
    secureSharedPreferences.edit {
      putString(SharedPreferenceKey.SITE_NAME.name, siteName)
    }
  }

  fun setChangeLanguage(language: String,languageCode: String) {
    secureSharedPreferences.edit {
      putString(SharedPreferenceKey.KEY_LANGUAGE.name, language)
      putString(SharedPreferenceKey.KEY_LANGUAGE_CODE.name, languageCode)
    }
  }

  fun getFhirBaseUrl(): String {
    var fhirBaseUrl = secureSharedPreferences.getString(SharedPreferenceKey.FHIR_BASE_URL.name, null)
    if (fhirBaseUrl.isNullOrEmpty()) {
      fhirBaseUrl = STAGING_FHIR_BASE_URL
    }
    return fhirBaseUrl
  }

  fun getOauthBaseUrl(): String {
    var oAuthBaseurl = secureSharedPreferences.getString(SharedPreferenceKey.OAUTH_BASE_URL.name, null)
    if (oAuthBaseurl.isNullOrEmpty()) {
      oAuthBaseurl = STAGING_OAUTH_BASE_URL
    }
    return oAuthBaseurl
  }

  @VisibleForTesting fun get256RandomBytes() = 256.getRandomBytesOfSize()

  fun retrievePinSalt() =
    secureSharedPreferences.getString(SharedPreferenceKey.LOGIN_PIN_SALT.name, null)

  fun retrieveSessionPin() =
    secureSharedPreferences.getString(SharedPreferenceKey.LOGIN_PIN_KEY.name, null)

  fun deleteSessionPin() =
    secureSharedPreferences.edit { remove(SharedPreferenceKey.LOGIN_PIN_KEY.name) }

  /** This method resets/clears all existing values in the shared preferences synchronously */
  fun resetSharedPrefs() = secureSharedPreferences.edit { clear() }

  companion object {
    const val SECURE_STORAGE_FILE_NAME = "fhircore_secure_preferences"
  }
}
