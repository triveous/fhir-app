package org.smartregister.fhircore.quest.util

import androidx.lifecycle.MutableLiveData

/**
 * Created by Jeetesh Surana.
 */

fun <T> mutableLiveData(defaultValue: T? = null): MutableLiveData<T> {
    val data = MutableLiveData<T>()
    defaultValue?.let { data.value = it }
    return data
}
