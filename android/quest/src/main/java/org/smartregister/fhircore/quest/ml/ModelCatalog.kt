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
        OnnxModelContract.COMPONENTS.map {
          ModelMember(id = it.modelName, resultKeyPrefix = it.resultKeyPrefix, resultUrls = it.resultUrls)
        },
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
   * The 3-class model, as delivered on 2026-11-13: two MobileViTv2 multitask members (`m1`, `m2`),
   * each `input` float32 `[batch, 3, 256, 256]` → `cls_logits [batch, 3]` (raw scores; the app
   * applies softmax) plus a segmentation output the app does not use. The bundled files are the
   * classification part only, built by `scripts/prepare_onnx_models.py`.
   *
   * A site runs both members or one of them (`ai-model.members`). With both, the photo's class
   * comes from the ML team's table ([THREE_CLASS_TABLE]); with one, it is that member's answer.
   *
   * Not yet confirmed by the ML team, so check before a site is switched to this model:
   * [THREE_CLASS_PREPROCESSING] (nothing in the files states it) and [THREE_CLASS_OUTPUT_ORDER].
   *
   * Until every file in [THREE_CLASS_ASSETS] is in `assets/`, sites set to this model see "Failed
   * to enable AI" instead of a wrong result.
   *
   * Each member's result is saved on the photo's QuestionnaireResponse answer under URLs named
   * after the model and member (`.../ai-model-result/oral-3class-v1-m1-prediction` / `-confidence`).
   * The photo's DocumentReference, and the Media the StructureMap copies the answer to, still use
   * the 2-class `v38-model-v6/v8-*` slots (`resultKeyPrefix`). The combined class is saved as its
   * id in the same fields 2-class sites use.
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
        ),
      outputLayout = OutputLayout.Softmax(THREE_CLASS_OUTPUT_ORDER, appliedInGraph = false),
      onnxAssets = THREE_CLASS_ASSETS,
      pyTorchFallback = false,
      defaultRule =
        CombineRule(
          id = "$THREE_CLASS_MODEL_ID/m1-m2-table",
          version = 1,
          modelId = THREE_CLASS_MODEL_ID,
          photo = THREE_CLASS_TABLE,
        ),
      confidenceMode = ConfidenceMode.MEAN_FINAL_CLASS_PROBABILITY,
      inputPreprocessing = THREE_CLASS_PREPROCESSING,
      onnxOutputName = "cls_logits",
    )

  val ALL: List<ModelDescriptor> = listOf(BINARY, THREE_CLASS)

  fun byId(modelId: String): ModelDescriptor? = ALL.firstOrNull { it.id == modelId }

  /**
   * Every URL a member result can be saved under on a photo's answer, for every model, in model
   * and member order (prediction before confidence). Used to clear a retaken photo's old results
   * whichever model wrote them, and to keep the saved order stable.
   */
  val ALL_MEMBER_RESULT_URLS: List<String> by lazy {
    ALL.flatMap { model -> model.members.flatMap { model.resultUrlsOf(it).both } }.distinct()
  }

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

  /**
   * Assumed order of the three `cls_logits` values: the ML team's classes 0, 1, 2. Matches their
   * combine table, where a higher number is the more serious class.
   */
  private val THREE_CLASS_OUTPUT_ORDER
    get() = listOf(NON_SUSPICIOUS, LOW_RISK_SUSPICIOUS, HIGH_RISK_SUSPICIOUS)

  /**
   * Assumed input of the 3-class members: RGB, scaled to 0–1, no mean/std (the MobileViTv2
   * default). Of the candidates tried on docs/test-images it is the one where the two members
   * agree best, on both the class and the lesion mask; that is evidence, not confirmation.
   */
  private val THREE_CLASS_PREPROCESSING
    get() = InputPreprocessing(channelOrder = ChannelOrder.RGB, scale = 1f / 255f)

  /** One file per 3-class member, in member order. */
  private val THREE_CLASS_ASSETS
    get() = listOf("oral_3class_m1.onnx", "oral_3class_m2.onnx")

  /**
   * The ML team's table for combining the two members' answers on a photo
   * (docs/features/ai-model/combine-table-m1-m2.png). Keys are (m1, m2).
   */
  private val THREE_CLASS_TABLE: ClassTable
    get() {
      val non = NON_SUSPICIOUS
      val low = LOW_RISK_SUSPICIOUS
      val high = HIGH_RISK_SUSPICIOUS
      return ClassTable(
        mapOf(
          listOf(non, non) to non,
          listOf(non, low) to non,
          listOf(non, high) to low,
          listOf(low, non) to non,
          listOf(low, low) to low,
          listOf(low, high) to high,
          listOf(high, non) to low,
          listOf(high, low) to high,
          listOf(high, high) to high,
        ),
      )
    }
}
