package org.smartregister.fhircore.engine.data.local.updateDocStatus

data class DocStatusRequest(
    val op: String,
    val path: String,
    val value: String
)