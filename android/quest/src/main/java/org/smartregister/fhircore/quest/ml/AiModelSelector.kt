/*
 * Copyright 2026 Indian Institute of Science (IISc)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ml

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.smartregister.fhircore.engine.util.AiModelConfig
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import timber.log.Timber

/** The model a screening runs and the combine rule it uses. */
data class ActiveAiModel(
  val model: ModelDescriptor,
  val rule: CombineRule,
  val ruleSource: RuleSource,
)

/** Whether the site's chosen model can run on this APK. */
sealed interface AiModelSelection {
  data class Ready(val active: ActiveAiModel) : AiModelSelection

  /** AI is meant to run, but this APK can't run [modelId]: the app must be updated. */
  data class Unavailable(val modelId: String, val reason: Reason) : AiModelSelection

  enum class Reason(val telemetryName: String) {
    /** The site names a model this APK doesn't know. */
    UNKNOWN_MODEL("unknown_model"),

    /** The APK knows the model but its `.onnx` files aren't bundled. */
    NOT_BUNDLED("model_not_bundled"),
  }
}

/**
 * Resolves the site's AI settings (`ai-model.id` + optional `ai-model.rule` on the feature-flags
 * Basic) into the model and combine rule to run:
 * 1. No model set → [ModelCatalog.DEFAULT_MODEL_ID] (today's 2-class model, unchanged).
 * 2. A model this APK doesn't know, or whose files aren't bundled → [AiModelSelection.Unavailable].
 *    The app never quietly falls back to a different model.
 * 3. A remote rule that passes [CombineRuleParser]'s checks replaces the model's shipped rule;
 *    one that fails is reported as `ai_rule_rejected` and the shipped rule is used.
 *
 * A screening's selection is locked the first time it is asked for, so a sync during a screening
 * never mixes models or rules within one case.
 */
@Singleton
class AiModelSelector
@Inject
constructor(
  private val featureFlagUtil: FeatureFlagUtil,
  private val onnxInferenceEngine: OnnxInferenceEngine,
) {

  private val screeningLocks =
    object : LinkedHashMap<String, AiModelSelection>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiModelSelection>?) =
        size > MAX_LOCKED_SCREENINGS
    }

  /** Problems already reported this process, so a bad config isn't reported on every photo. */
  private val reported = ConcurrentHashMap.newKeySet<String>()

  /**
   * The selection for [screeningId], resolved and locked on first use. Without a screening id it
   * is resolved fresh each time.
   */
  suspend fun forScreening(screeningId: String?): AiModelSelection {
    if (screeningId == null) return select(featureFlagUtil.getAiModelConfig())
    synchronized(screeningLocks) { screeningLocks[screeningId] }?.let {
      return it
    }
    val selection = select(featureFlagUtil.getAiModelConfig())
    return synchronized(screeningLocks) { screeningLocks.getOrPut(screeningId) { selection } }
  }

  /**
   * The selection a new screening would get, from the settings stored on the device only (never
   * the network), for the add-case "AI enabled" check.
   */
  suspend fun currentLocally(): AiModelSelection = select(featureFlagUtil.getAiModelConfigLocally())

  /** True when [model] can run here: its ONNX files are bundled, or it has a PyTorch fallback. */
  fun isRunnable(model: ModelDescriptor): Boolean =
    model.pyTorchFallback || onnxInferenceEngine.isBundled(model)

  fun select(config: AiModelConfig): AiModelSelection {
    val modelId = config.modelId ?: ModelCatalog.DEFAULT_MODEL_ID
    val model =
      ModelCatalog.byId(modelId)
        ?: return unavailable(modelId, AiModelSelection.Reason.UNKNOWN_MODEL)
    if (!isRunnable(model)) return unavailable(modelId, AiModelSelection.Reason.NOT_BUNDLED)

    val ruleJson = config.rule ?: return ready(model, model.defaultRule, RuleSource.SHIPPED)
    return when (val parsed = CombineRuleParser.parse(ruleJson, model)) {
      is RuleParseResult.Accepted -> ready(model, parsed.rule, RuleSource.REMOTE)
      is RuleParseResult.Rejected -> {
        reportOnce("rule|$modelId|$ruleJson") {
          Timber.w("Remote AI rule rejected for %s: %s", modelId, parsed.reason)
          PostHogAnalytics.capture(
            PostHogAnalytics.Events.AI_RULE_REJECTED,
            mapOf(
              PostHogAnalytics.Props.MODEL_ID to modelId,
              PostHogAnalytics.Props.RULE_ID to parsed.ruleId,
              PostHogAnalytics.Props.RULE_REJECT_REASON to parsed.reason,
            ),
          )
        }
        ready(model, model.defaultRule, RuleSource.SHIPPED)
      }
    }
  }

  private fun ready(model: ModelDescriptor, rule: CombineRule, source: RuleSource) =
    AiModelSelection.Ready(ActiveAiModel(model, rule, source))

  private fun unavailable(modelId: String, reason: AiModelSelection.Reason): AiModelSelection {
    reportOnce("model|$modelId|$reason") {
      Timber.w("AI model %s can't run on this APK: %s", modelId, reason.telemetryName)
      PostHogAnalytics.captureError(
        "AiModelSelector",
        "AI model $modelId unavailable: ${reason.telemetryName}",
      )
    }
    return AiModelSelection.Unavailable(modelId, reason)
  }

  private inline fun reportOnce(key: String, report: () -> Unit) {
    if (reported.add(key)) report()
  }

  private companion object {
    /** Screenings whose selection is remembered; far more than can be open at once. */
    const val MAX_LOCKED_SCREENINGS = 32
  }
}
