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

import org.smartregister.fhircore.quest.R

/**
 * Every model this APK knows. A site picks one with `ai-model.id`; with none set the app runs
 * [DEFAULT_MODEL_ID], so sites that never set it see no change.
 *
 * Adding a model: add its descriptor here, put its `.onnx` files in `quest/src/main/assets/`, then
 * set the site's `ai-model.id`. No new flag, screen or FHIR field is needed as long as its classes
 * map onto the existing outcomes and result screens.
 */
object ModelCatalog {

  const val BINARY_MODEL_ID = "oral-binary-v1"
  const val THREE_CLASS_MODEL_ID = "oral-3class-v1"
  const val DEFAULT_MODEL_ID = BINARY_MODEL_ID

  /** Class ids of [BINARY]. */
  const val SUSPICIOUS = "suspicious"
  const val NON_SUSPICIOUS = "non-suspicious"

  /** Class ids of [THREE_CLASS] (plus [NON_SUSPICIOUS]). */
  const val LOW_RISK_SUSPICIOUS = "low-risk-suspicious"
  const val HIGH_RISK_SUSPICIOUS = "high-risk-suspicious"

  /**
   * Today's model: three members, each one sigmoid probability of "suspicious". Saved values and
   * arithmetic are exactly what the app wrote before multi-class support, and the default rule is
   * today's "all three must agree" rule, so results and FHIR data are unchanged.
   */
  val BINARY =
    ModelDescriptor(
      id = BINARY_MODEL_ID,
      telemetryVersion = OnnxModelContract.MODEL_VERSION,
      classes =
        listOf(
          AiClass(
            id = NON_SUSPICIOUS,
            savedValue = "Non-Suspicious",
            caseValue = "non-suspicious",
            outcome = AiOutcome.NO_REFERRAL,
            severity = 0,
            screen = ResultScreenStyle.LEGACY_NON_SUSPICIOUS,
          ),
          AiClass(
            id = SUSPICIOUS,
            savedValue = "Suspicious",
            caseValue = "suspicious",
            outcome = AiOutcome.REFER,
            severity = 2,
            screen = ResultScreenStyle.LEGACY_SUSPICIOUS,
          ),
        ),
      members =
        OnnxModelContract.COMPONENTS.map { ModelMember(id = it.modelName, resultKeyPrefix = it.resultKeyPrefix) },
      outputLayout =
        OutputLayout.SigmoidBinary(positiveClassId = SUSPICIOUS, negativeClassId = NON_SUSPICIOUS),
      onnxAssets = OnnxModelContract.ASSET_NAMES,
      pyTorchFallback = true,
      defaultRule =
        CombineRule(
          id = "$BINARY_MODEL_ID/all-agree",
          version = 1,
          modelId = BINARY_MODEL_ID,
          photo =
            VoteBlock(
              votesNeeded = VotesNeeded.PerClass(mapOf(SUSPICIOUS to Votes.All)),
              countMoreSevere = true,
              fallback = NON_SUSPICIOUS,
            ),
        ),
      confidenceMode = ConfidenceMode.MEAN_MEMBER_CONFIDENCE,
    )

  /**
   * The 3-class model. Assumed contract until the ML team delivers it: three members, each
   * emitting softmax probabilities `[1, 3]` in [THREE_CLASS_OUTPUT_ORDER]. Change the order or the
   * file names here if the delivery differs. Until every file in [THREE_CLASS_ASSETS] is in
   * `assets/`, sites set to this model see "Failed to enable AI" instead of a wrong result.
   *
   * Members reuse the three per-model FHIR field pairs (`v38-model-v6/v8/v82-*`); classes are saved
   * as their ids in the same fields 2-class sites use.
   */
  val THREE_CLASS =
    ModelDescriptor(
      id = THREE_CLASS_MODEL_ID,
      telemetryVersion = "$THREE_CLASS_MODEL_ID-onnx",
      classes =
        listOf(
          AiClass(
            id = NON_SUSPICIOUS,
            savedValue = NON_SUSPICIOUS,
            caseValue = NON_SUSPICIOUS,
            outcome = AiOutcome.NO_REFERRAL,
            severity = 0,
            screen = ResultScreenStyle.BLUE,
            labelRes = R.string.ai_class_non_suspicious,
          ),
          AiClass(
            id = LOW_RISK_SUSPICIOUS,
            savedValue = LOW_RISK_SUSPICIOUS,
            caseValue = LOW_RISK_SUSPICIOUS,
            outcome = AiOutcome.REFER,
            severity = 1,
            screen = ResultScreenStyle.ORANGE,
            labelRes = R.string.ai_class_low_risk_suspicious,
          ),
          AiClass(
            id = HIGH_RISK_SUSPICIOUS,
            savedValue = HIGH_RISK_SUSPICIOUS,
            caseValue = HIGH_RISK_SUSPICIOUS,
            outcome = AiOutcome.REFER,
            severity = 2,
            screen = ResultScreenStyle.RED,
            labelRes = R.string.ai_class_high_risk_suspicious,
          ),
        ),
      members =
        listOf(
          ModelMember(id = "m1", resultKeyPrefix = "model6"),
          ModelMember(id = "m2", resultKeyPrefix = "model8"),
          ModelMember(id = "m3", resultKeyPrefix = "model82"),
        ),
      outputLayout = OutputLayout.Softmax(THREE_CLASS_OUTPUT_ORDER),
      onnxAssets = THREE_CLASS_ASSETS,
      pyTorchFallback = false,
      defaultRule =
        CombineRule(
          id = "$THREE_CLASS_MODEL_ID/high-all-low-majority",
          version = 1,
          modelId = THREE_CLASS_MODEL_ID,
          photo =
            VoteBlock(
              votesNeeded =
                VotesNeeded.PerClass(
                  mapOf(HIGH_RISK_SUSPICIOUS to Votes.All, LOW_RISK_SUSPICIOUS to Votes.Majority),
                ),
              countMoreSevere = true,
              fallback = NON_SUSPICIOUS,
            ),
        ),
      confidenceMode = ConfidenceMode.MEAN_FINAL_CLASS_PROBABILITY,
    )

  val ALL: List<ModelDescriptor> = listOf(BINARY, THREE_CLASS)

  fun byId(modelId: String): ModelDescriptor? = ALL.firstOrNull { it.id == modelId }

  /**
   * The class a saved `ai-result-value` (or `case-level-ai-result`) value stands for, in any known
   * model, ignoring case. Lets saved results be read without knowing which model wrote them.
   * Values shared by two models (e.g. `non-suspicious`) mean the same outcome in both.
   */
  fun classForSavedValue(value: String?): AiClass? {
    val wanted = value?.substringBefore("|")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ALL.asSequence()
      .flatMap { it.classes.asSequence() }
      .firstOrNull {
        it.savedValue.equals(wanted, ignoreCase = true) || it.caseValue.equals(wanted, ignoreCase = true)
      }
  }

  /**
   * [classForSavedValue] mapped onto [model]'s own classes, for combining a case. A value from
   * another model (a draft started before the site switched models) maps to [model]'s most serious
   * class with the same outcome, so a referral is never downgraded. Null for unknown values.
   */
  fun classInModel(value: String?, model: ModelDescriptor): AiClass? {
    val found = classForSavedValue(value) ?: return null
    model.classes.firstOrNull { it.id == found.id }?.let {
      return it
    }
    return model.classes.filter { it.outcome == found.outcome }.maxByOrNull { it.severity }
  }

  /** Assumed 3-class output order; see [THREE_CLASS]. */
  private val THREE_CLASS_OUTPUT_ORDER
    get() = listOf(NON_SUSPICIOUS, LOW_RISK_SUSPICIOUS, HIGH_RISK_SUSPICIOUS)

  /** Placeholder file names for the 3-class members; rename to the delivered files. */
  private val THREE_CLASS_ASSETS
    get() = listOf("oral_3class_m1.onnx", "oral_3class_m2.onnx", "oral_3class_m3.onnx")
}
