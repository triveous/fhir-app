package org.smartregister.fhircore.engine.sync

class ImageUploadAPIException(
    val documentId: String,
    val responseCode: Int,
    val responseMessage: String,
    val pendingDocuments: Int
) : Exception("File upload failed for document with logicalId: $documentId - Response code: $responseCode - Message: $responseMessage - $pendingDocuments pending")