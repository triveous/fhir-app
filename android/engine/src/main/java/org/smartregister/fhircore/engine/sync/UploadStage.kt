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

import org.smartregister.fhircore.engine.util.analytics.AnalyticsLogger

/**
 * How far an image upload got before it failed. Reported as [AnalyticsLogger.Props.UPLOAD_STAGE],
 * which is what makes a stuck document diagnosable in production without a log line per step per
 * photo.
 *
 * The [label]s are the values that reach PostHog, so renaming one breaks any saved query or alert
 * built on it.
 */
internal enum class UploadStage(val label: String) {
    STARTING("starting"),

    /** Step 1 — creating the resource with `PUT /DocumentReference/{id}`. */
    CREATING_METADATA("creating_metadata"),

    /** Step 2 — sending the bytes to `$binary-access-write`. */
    UPLOADING_BINARY("uploading_binary"),

    /** The PATCH after step 2 that records how long the upload took. */
    RECORDING_DURATION("recording_duration"),

    /** Step 3 — patching docStatus to final. */
    FINALIZING("finalizing"),
}
