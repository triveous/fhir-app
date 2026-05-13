package org.smartregister.fhircore.engine.domain.networkUtils

object HttpConstants {
    const val SOMETHING_WANT_WRONG  ="Oops, something went wrong. Our best minds are at work to bring it back to normal."
    // Multi-tenant config (per multi-tenancy migration doc, §5.1). The old endpoint
    // continues to serve the legacy `environments`/`sites` JSON to older app builds.
    const val SELECT_YOUR_SITE_URL = "https://storage.googleapis.com/arogyam-app-config/config.json"
    const val UPLOAD_IMAGE_URL = "http://hl7.org/fhir/StructureDefinition/file-location"
    const val HEADER_APPLICATION_JSON = "application/json"
}

object ErrorCodes {
    const val FAILED_TO_COMPLETE_REQUEST_ERROR_CODE = 900
    const val FAILED_TO_OVERWRITE_URL_ERROR_CODE = 901
    const val UNKNOWN_ERROR_CODE = 902
    const val NO_INTERNET_CONNECTION_ERROR_CODE = 903
}

object WorkerConstants {
    const val REPLACE = "replace"
    const val ADD_EXTENSION = "add"
    const val DOC_STATUS = "/docStatus"
    const val DOC_EXTENSION = "/extension"
    const val CONTENT_TYPE = "application/json-patch+json"
}

object DocumentReferenceCaseType {
    const val DRAFT = "DRAFT"
    const val SUBMITTED = "SUBMITTED"
}