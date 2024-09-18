package org.smartregister.fhircore.quest.util

import androidx.compose.material.SnackbarDuration
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.SyncJobStatus
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.sync.AppSyncWorker.Companion.isUploading
import org.smartregister.fhircore.engine.sync.UploadingStatus
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel

/**
 * Created by Jeetesh Surana.
 */

fun <T> mutableLiveData(defaultValue: T? = null): MutableLiveData<T> {
    val data = MutableLiveData<T>()
    defaultValue?.let { data.value = it }
    return data
}

fun FragmentActivity.manageSyncMessage(registerViewModel: RegisterViewModel, syncJobStatus: CurrentSyncJobStatus ){
    lifecycleScope.launch {
        val imageCount = registerViewModel.getUnUploadedImageCount()
        println("imageCount --> $imageCount isUploading.value-->${isUploading.value}")
        when (syncJobStatus) {
            is CurrentSyncJobStatus.Running -> {
                if (syncJobStatus.inProgressSyncJob is SyncJobStatus.Started && isUploading.value == UploadingStatus.UPLOADING) {
                    registerViewModel.emitSnackBarState(
                        SnackBarMessageConfig(message = getString(R.string.syncing)),
                    )
                }
            }

            is CurrentSyncJobStatus.Succeeded -> {
                if (isUploading.value == UploadingStatus.UPLOADED) {
                    registerViewModel.emitSnackBarState(
                        SnackBarMessageConfig(
                            message = getString(R.string.sync_completed),
                            duration = SnackbarDuration.Short,
                        ),
                    )
                }
            }

            is CurrentSyncJobStatus.Failed -> {
                if (isUploading.value == UploadingStatus.FAILED) {
                    registerViewModel.emitSnackBarState(
                        SnackBarMessageConfig(
                            message = getString(R.string.sync_completed_with_errors),
                            duration = SnackbarDuration.Short,
                        ),
                    )
                }
            }

            else -> {

            }
        }
    }
}