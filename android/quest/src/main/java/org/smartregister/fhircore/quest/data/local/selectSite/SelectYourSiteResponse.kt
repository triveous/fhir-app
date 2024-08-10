package org.smartregister.fhircore.quest.data.local.selectSite

data class SelectYourSiteResponse(
    val environments: List<Environment>,
    val sites: List<SelectSite>,
    val type: String
)