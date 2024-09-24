package org.smartregister.fhircore.engine.data.remote.selectSite

data class SelectSite(
    var code: String?=null,
    var name: String?=null,
    var district: String?=null,
    var state: String?=null,
    var backendUrl: String?=null,
    var fhirBaseUrl: String?=null,
    var mapSdkToken: String?=null,
    var authBaseUrl: String?=null,
    var authClientId: String?=null,
    var authScope: String?=null,
    var openSrpAppId: String?=null,
    var sentryDsn: String?=null
)