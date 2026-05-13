package org.smartregister.fhircore.engine.data.remote.selectSite

import com.google.gson.annotations.SerializedName

data class SelectSite(
    var code: String? = null,
    var name: String? = null,
    @SerializedName(value = "base_url", alternate = ["fhirBaseUrl"])
    var fhirBaseUrl: String? = null,
    var authBaseUrl: String? = null,
    var authClientId: String? = null,
    var oauthClientId: String? = null,
    var authScope: String? = null,
    var mapSdkToken: String? = null,
    var openSrpAppId: String? = null,
    var sentryDsn: String? = null,
)
