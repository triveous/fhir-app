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

package org.smartregister.fhircore.engine.domain.networkUtils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** Hostname stood in for the FHIR server before a real base URL has been configured. */
const val PLACEHOLDER_HOST = "placeholder.invalid"

/** How far up a `cause` chain to walk before giving up. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * Whether the device can reach the network, and how sure we are.
 *
 * [NetworkCapabilities.hasTransport] only says the radio is associated with an access point — it
 * says nothing about whether packets get past it. That gap matters here because most users sync
 * through a phone hotspot: the WiFi link to the hotspot stays up while the host phone's cellular
 * uplink comes and goes, so a transport-only check reports "online" throughout an outage.
 */
enum class ConnectivityState {
  /** No network attached, or one that does not claim to offer internet. Requests cannot succeed. */
  OFFLINE,

  /**
   * A network is attached but Android's connectivity probe has not confirmed internet access.
   * Usually a hotspot whose uplink has dropped — but also any network that blocks the probe, so
   * requests are still worth attempting when the user explicitly asked for them.
   */
  UNVALIDATED,

  /** A network is attached and Android confirmed it reaches the internet. */
  ONLINE,
}

object NetworkConnectivity {

  fun currentState(context: Context): ConnectivityState {
    val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        ?: return ConnectivityState.OFFLINE
    val capabilities =
      connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        ?: return ConnectivityState.OFFLINE

    return when {
      !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
        ConnectivityState.OFFLINE
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
        ConnectivityState.ONLINE
      else -> ConnectivityState.UNVALIDATED
    }
  }
}

/**
 * Transport failures that are expected in the field: the device lost its uplink mid-request, or a
 * coroutine was cancelled. These are conditions, not defects, so they must not be reported as
 * exceptions — doing so drowns real crashes in retry noise.
 *
 * [PLACEHOLDER_HOST] is deliberately excluded. A request against it means the app issued a call
 * before a real FHIR base URL was configured, which is a genuine bug and has to stay visible.
 */
fun Throwable.isExpectedNetworkError(): Boolean {
  var expected = false
  var current: Throwable? = this
  var depth = 0
  while (current != null && depth < MAX_CAUSE_DEPTH) {
    if (current.message?.contains(PLACEHOLDER_HOST, ignoreCase = true) == true) return false
    if (current.isTransportFailure()) expected = true
    val cause = current.cause
    current = if (cause === current) null else cause
    depth++
  }
  return expected
}

private fun Throwable.isTransportFailure() =
  when (this) {
    is UnknownHostException, // DNS did not resolve: no uplink, or the hotspot's uplink is down
    is SocketException, // ConnectException, NoRouteToHostException, resets, "Socket closed"
    is InterruptedIOException, // SocketTimeoutException
    is EOFException, // connection truncated mid-response
    is CancellationException, // coroutine cancelled, including JobCancellationException
    -> true
    else -> false
  }
