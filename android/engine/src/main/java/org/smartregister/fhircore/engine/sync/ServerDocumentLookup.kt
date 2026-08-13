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

import org.hl7.fhir.r4.model.DocumentReference
import retrofit2.HttpException
import timber.log.Timber
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * What the server said when [AppSyncWorker] asked about a DocumentReference.
 *
 * [NotFound] and [Unavailable] have to stay separate. "The server says it does not have this"
 * licenses creating the resource, and — if the local file is also gone — giving up on the photo.
 * "I could not reach the server" licenses nothing at all: the only safe move is to leave the
 * document exactly as it is and ask again next run.
 */
internal sealed interface ServerDocumentLookup {
    /** The server returned the resource. */
    data class Found(val documentReference: DocumentReference) : ServerDocumentLookup

    /** The server answered, authoritatively, that no such resource exists (HTTP 404). */
    data object NotFound : ServerDocumentLookup

    /** The server could not be asked. Its state is unknown; assume nothing. */
    data class Unavailable(val error: Throwable) : ServerDocumentLookup
}

/**
 * Turns a failed metadata lookup into a [ServerDocumentLookup].
 *
 * Only an HTTP 404 proves the server does not have the resource. Everything else — 5xx, 401, and the
 * synthetic 900-903 codes this app's own OkHttp interceptors produce for offline, DNS and timeout
 * conditions — leaves the answer unknown, and callers must then do nothing rather than guess.
 */
internal fun classifyServerLookupFailure(
    documentId: String,
    error: Throwable,
): ServerDocumentLookup =
    if (error is HttpException && error.code() == HTTP_NOT_FOUND) {
        Timber.d("No DocumentReference on server for id $documentId: HTTP 404")
        ServerDocumentLookup.NotFound
    } else {
        // Debug on purpose: an offline device hits this for every pending photo on every sync. The
        // count travels to PostHog in the run summary instead.
        Timber.d("DocumentReference $documentId state unknown: ${error.localizedMessage}")
        ServerDocumentLookup.Unavailable(error)
    }
