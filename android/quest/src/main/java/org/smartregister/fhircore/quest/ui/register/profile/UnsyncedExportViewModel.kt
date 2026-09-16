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

package org.smartregister.fhircore.quest.ui.register.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.data.export.ExportProgress
import org.smartregister.fhircore.engine.data.export.UnsyncedDataExporter
import org.smartregister.fhircore.engine.data.export.UnsyncedExportResult
import org.smartregister.fhircore.engine.util.analytics.AnalyticsLogger
import org.smartregister.fhircore.quest.BuildConfig
import timber.log.Timber

/** UI state of the "Export unsynced data" action on the Profile screen. */
sealed class UnsyncedExportState {
  data object Idle : UnsyncedExportState()

  /** [progress] is null until the exporter reports its first phase. */
  data class Running(val progress: ExportProgress? = null) : UnsyncedExportState()

  data class Done(val result: UnsyncedExportResult) : UnsyncedExportState()

  data class Failed(val message: String) : UnsyncedExportState()
}

@HiltViewModel
class UnsyncedExportViewModel
@Inject
constructor(
  private val exporter: UnsyncedDataExporter,
  private val analyticsLogger: AnalyticsLogger,
) : ViewModel() {

  private val _state = MutableStateFlow<UnsyncedExportState>(UnsyncedExportState.Idle)
  val state: StateFlow<UnsyncedExportState> = _state.asStateFlow()

  /** Starts an export unless one is already running. */
  fun export() {
    if (_state.value is UnsyncedExportState.Running) return
    _state.value = UnsyncedExportState.Running()
    viewModelScope.launch {
      try {
        val result =
          exporter.export(appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") { progress ->
            // Called from the IO dispatcher; StateFlow.value is thread-safe and Compose collects it.
            _state.value = UnsyncedExportState.Running(progress)
          }
        exporter.pruneOldExports()
        runCatching {
          analyticsLogger.capture(
            AnalyticsLogger.Events.UNSYNCED_DATA_EXPORTED,
            mapOf(
              AnalyticsLogger.Props.EXPORTED_LOCAL_CHANGES to result.localChangeCount,
              AnalyticsLogger.Props.EXPORTED_RESOURCES to result.resourceCount,
              AnalyticsLogger.Props.EXPORTED_IMAGES to result.imageCount,
              AnalyticsLogger.Props.EXPORT_SIZE_BYTES to result.sizeBytes,
            ),
          )
        }
        _state.value = UnsyncedExportState.Done(result)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.e(e, "Unsynced data export failed")
        _state.value =
          UnsyncedExportState.Failed("${e::class.java.simpleName}: ${e.message ?: e.toString()}")
      }
    }
  }

  fun dismiss() {
    if (_state.value !is UnsyncedExportState.Running) _state.value = UnsyncedExportState.Idle
  }
}
