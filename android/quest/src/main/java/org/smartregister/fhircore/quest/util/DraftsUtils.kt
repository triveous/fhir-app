package org.smartregister.fhircore.quest.util

import ca.uhn.fhir.parser.IParser
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Location
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import timber.log.Timber
import java.util.UUID

object DraftsUtils {
  fun getAllDraftsJsonFromSharedPreferences(sharedPreferencesHelper: SharedPreferencesHelper): String {
    return try {
      sharedPreferencesHelper.read<String>(SharedPreferenceKey.DRAFTS.name, true).orEmpty()
    } catch (exception: Exception) {
      Timber.e(exception, "Error getAllDraftsJsonFromSharedPreferences")
      ""
    }
  }

  fun parseDraftResponses(parser: IParser, draftResponsesJson: String?): Bundle? {
    return if (draftResponsesJson.isNullOrEmpty()) {
      null
    } else {
      try {
        parser.parseResource(draftResponsesJson) as Bundle
      } catch (exception: Exception) {
        Timber.e(exception, "Error parsing draft responses")
        null
      }
    }
  }

  fun removeDraftFromBundle(bundle: Bundle, resourceId: String): Bundle {
    bundle.entry?.removeAll { it.resource?.id == resourceId }
    return bundle
  }

  fun saveBundleToSharedPreferences(sharedPreferencesHelper: SharedPreferencesHelper, parser: IParser, bundle: Bundle) {
    val bundleJson = parser.encodeResourceToString(bundle)
    sharedPreferencesHelper.write<String>(SharedPreferenceKey.DRAFTS.name, bundleJson)
  }
}