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

enum class SharedPreferenceKey {
  APP_ID,
  LAST_SYNC_TIMESTAMP,
  LANG,
  PRACTITIONER_ID,
  PRACTITIONER_USER_ID,
  PRACTITIONER_USER_ID_ISSUE_FIXED,
  PRACTITIONER_DETAILS,
  PRACTITIONER_LOCATION_HIERARCHIES,
  PRACTITIONER_LOCATION,
  REMOTE_SYNC_RESOURCES,
  LOGIN_CREDENTIAL_KEY,
  LOGIN_PIN_KEY,
  LOGIN_PIN_SALT,
  LAST_OFFSET,
  USER_INFO,
  CARE_TEAM,
  ORGANIZATION,
  FHIR_BASE_URL,
  SITE_NAME,
  OAUTH_BASE_URL,
  DRAFTS,
  KEY_LANGUAGE,
  KEY_LANGUAGE_CODE,
  LAST_SYNC_DATE_TIME
}

const val STAGING_FHIR_BASE_URL = "https://staging.arogyam-midas.iisc.ac.in/fhir/"
const val STAGING_OAUTH_BASE_URL = "https://staging.arogyam-midas.iisc.ac.in/auth/realms/arogyam/"