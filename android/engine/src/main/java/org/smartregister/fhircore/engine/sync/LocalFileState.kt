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

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.FileNotFoundException

/**
 * Whether a captured image is still readable on this device.
 *
 * Three-way for the same reason as [ServerDocumentLookup]: "I could not read the file" is not "the
 * file is gone". Only a genuinely missing file proves absence. A lost URI permission after the
 * process was recreated, a transient I/O error, storage briefly unavailable — none of those prove
 * anything, and calling them absence throws away a photo sitting on disk.
 */
internal sealed interface LocalFileState {
    /** The file is present and non-empty. */
    data object Present : LocalFileState

    /** The file definitively does not exist, or exists but is empty. */
    data object Absent : LocalFileState

    /** The file could not be read. Its existence is unknown; assume nothing. */
    data class Unreadable(val error: Throwable) : LocalFileState
}

/** Reads the state of the image behind [uri], never throwing except to pass on cancellation. */
internal fun ContentResolver.localFileState(uri: Uri?): LocalFileState {
    if (uri == null) return LocalFileState.Absent
    return try {
        val stream = openInputStream(uri) ?: return LocalFileState.Absent
        stream.use { if (it.available() > 0) LocalFileState.Present else LocalFileState.Absent }
    } catch (e: FileNotFoundException) {
        LocalFileState.Absent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Debug: a device that has lost its URI permissions hits this for its whole backlog on every
        // sync. The caller logs the same throwable and counts it.
        Timber.d(e, "Could not determine whether the image file exists for uri: $uri")
        LocalFileState.Unreadable(e)
    }
}
