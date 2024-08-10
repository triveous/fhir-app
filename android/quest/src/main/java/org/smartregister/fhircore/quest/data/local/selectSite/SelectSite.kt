package org.smartregister.fhircore.quest.data.local.selectSite

data class SelectSite(
    val name: String,
    val district: String,
    val state: String,
    val backendUrl: String,
    var fhirBaseUrl: String?=null,
    var mapSdkToken: String?=null,
    var oAuthBaseUrl: String?=null,
    var oAuthClientId: String?=null,
    var oAuthScope: String?=null,
    var openSrpAppId: String?=null,
    var sentryDsn: String?=null
)