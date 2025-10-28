package org.smartregister.fhircore.engine.data.local.updateDocStatus
data class ExtensionValue(
    val url: String,
    val valueString: String
)

data class DocStatusRequest(
    val op: String,
    val path: String,
    val value: String
)

data class JsonPatchOperation(
    val op: String,
    val path: String,
    val value: Any
)