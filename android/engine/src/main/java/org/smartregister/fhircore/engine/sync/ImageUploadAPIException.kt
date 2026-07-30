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

package org.smartregister.fhircore.engine.sync

/**
 * Raised when `$binary-access-write` answers with a non-2xx status.
 *
 * Carries [responseCode] out to the sync worker, which reports it alongside the [UploadStage] the
 * upload reached — the two together say what the server rejected and at which step.
 */
data class ImageUploadAPIException(
    val documentId: String,
    val responseCode: Int,
    val responseMessage: String,
    val pendingDocuments: Int
) : Exception("Image upload failed for document $documentId: $responseCode $responseMessage ($pendingDocuments pending)")
