package org.smartregister.fhircore.engine.data.remote.selectSite

/**
 * A single selectable tenant flattened out of its parent [ServerConfig]. Pairs the tenant
 * ([site]) with the [server] it belongs to so the server's `multiTenant` flag can still be
 * resolved when the selection is persisted.
 */
data class TenantOption(val server: ServerConfig, val site: SelectSite)
