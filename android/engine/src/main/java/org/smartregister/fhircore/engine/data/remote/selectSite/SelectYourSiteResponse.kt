package org.smartregister.fhircore.engine.data.remote.selectSite

data class SelectYourSiteResponse(
    var environments: List<Environment>?=null,
    var sites: List<SelectSite>?=null,
    var type: String?=null
)