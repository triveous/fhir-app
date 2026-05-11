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

package org.smartregister.fhircore.engine.util.analytics

interface AnalyticsLogger {
  fun capture(event: String, properties: Map<String, Any?>? = null)

  object Events {
    const val IMAGE_UPLOAD_COMPLETED = "image_upload_completed"
  }

  object Props {
    const val DOCUMENT_ID = "document_id"
    const val UPLOAD_DURATION_MS = "upload_duration_ms"
    const val RESPONSE_CODE = "response_code"
    const val PENDING_DOCUMENTS = "pending_documents"
    const val BYTES_UPLOADED = "bytes_uploaded"
    const val ERROR_MESSAGE = "error_message"
  }
}
