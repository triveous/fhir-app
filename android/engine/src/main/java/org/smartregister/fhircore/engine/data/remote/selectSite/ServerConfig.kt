package org.smartregister.fhircore.engine.data.remote.selectSite

import com.google.gson.annotations.SerializedName

data class ServerConfig(
    var code: String? = null,
    var name: String? = null,
    @SerializedName("multi_tenant")
    var multiTenant: Boolean = false,
    var tenants: List<SelectSite>? = null,
)
