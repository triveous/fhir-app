package org.smartregister.fhircore.engine.domain.networkUtils

object HttpConstants {
    const val SOMETHING_WANT_WRONG  ="Oops, something went wrong. Our best minds are at work to bring it back to normal."
    const val SELECT_YOUR_SITE_URL = "https://production.arogyam-midas.iisc.ac.in/api/sites"
}

object ErrorCodes {
    const val FAILED_TO_COMPLETE_REQUEST_ERROR_CODE = 900
    const val FAILED_TO_OVERWRITE_URL_ERROR_CODE = 901
    const val UNKNOWN_ERROR_CODE = 902
    const val NO_INTERNET_CONNECTION_ERROR_CODE = 903
}