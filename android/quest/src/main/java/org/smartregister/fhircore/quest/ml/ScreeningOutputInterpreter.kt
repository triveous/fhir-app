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

import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import org.smartregister.fhircore.quest.camerax.CameraxLauncherFragment
import org.smartregister.fhircore.quest.util.PostHogAnalytics

/** Thrown when a model's output breaks its descriptor (e.g. logits instead of probabilities). */
class OnnxContractException(message: String) : IllegalStateException(message)

/** One member model's answer for a photo. */
data class MemberPrediction(
  val member: ModelMember,
  val predictedClass: AiClass,
  /** Sigmoid models: probability of the positive class (as before). Softmax: of [predictedClass]. */
  val probability: Float,
  /** Percent; the probability the member gives its own answer. */
  val confidence: Float,
  val entropy: Double,
  val lowConfidence: Boolean,
  /** Probability per class id. */
  val classProbabilities: Map<String, Float>,
)

/** A photo's result: every member's answer and the class the combine rule picked. */
data class PhotoClassification(
  val model: ModelDescriptor,
  val rule: CombineRule,
  val ruleSource: RuleSource,
  val members: List<MemberPrediction>,
  val finalClass: AiClass,
  /** Percent, worked out per [ModelDescriptor.confidenceMode]. */
  val confidence: Float,
  val probability: Float,
  val entropy: Double,
  val lowConfidence: Boolean,
) {

  /**
   * The result map the camera fragment hands to its ViewModel. The prediction and confidence keys
   * are what the photo's FHIR fields are written from, so for the 2-class model they are byte for
   * byte what the app always wrote. Each member's result is also keyed by
   * [CameraxLauncherFragment.CAMERA_MEMBER_RESULT_KEY_PREFIX] + the URL it is saved under on the
   * QuestionnaireResponse answer. The extra `ai_*` keys only feed analytics.
   */
  fun toResultMap(): Map<String, Any> = buildMap {
    put(CameraxLauncherFragment.CAMERA_PREDICTION_KEY, finalClass.savedValue)
    put(CameraxLauncherFragment.CAMERA_CONFIDENCE_KEY, DecimalFormat("#.##").format(confidence))
    members.forEach {
      put("${it.member.resultKeyPrefix}_prediction", it.predictedClass.savedValue)
      put("${it.member.resultKeyPrefix}_confidence", it.confidence.toString())
      // The same result under the URLs it is saved with on the QuestionnaireResponse answer.
      val urls = model.resultUrlsOf(it.member)
      put(CameraxLauncherFragment.CAMERA_MEMBER_RESULT_KEY_PREFIX + urls.prediction, it.predictedClass.savedValue)
      put(CameraxLauncherFragment.CAMERA_MEMBER_RESULT_KEY_PREFIX + urls.confidence, it.confidence.toString())
    }
    putAll(analyticsProps())
  }

  /** Model, class and rule props shared by the photo's analytics events. */
  fun analyticsProps(): Map<String, Any> =
    mapOf(
      PostHogAnalytics.Props.AI_CLASS to finalClass.id,
      PostHogAnalytics.Props.MODEL_ID to model.id,
      PostHogAnalytics.Props.RULE_ID to rule.id,
      PostHogAnalytics.Props.RULE_VERSION to rule.version,
      PostHogAnalytics.Props.RULE_SOURCE to ruleSource.telemetryName,
      PostHogAnalytics.Props.AI_MEMBERS to members.joinToString(",") { it.member.id },
    )

  /**
   * `model_inference_completed` once per member and once for the ensemble, with the same props as
   * before plus the model/class/rule props.
   *
   * @param memberTimesMs per-member forward-pass times; null reports [combinedTimeMs] for each.
   */
  fun captureInferenceEvents(
    screeningId: String?,
    modelVersion: String,
    memberTimesMs: List<Long>?,
    combinedTimeMs: Long,
    extraProps: Map<String, Any> = emptyMap(),
  ) {
    val shared = analyticsProps() + extraProps
    members.forEachIndexed { index, it ->
      capture(
        screeningId,
        modelVersion,
        name = it.member.id,
        prediction = it.predictedClass.telemetryName,
        confidence = it.confidence,
        entropy = it.entropy,
        lowConfidence = it.lowConfidence,
        timeMs = memberTimesMs?.getOrNull(index) ?: combinedTimeMs,
        combinedTimeMs = null,
        props = shared,
      )
    }
    capture(
      screeningId,
      modelVersion,
      name = ScreeningOutputInterpreter.ENSEMBLE_MODEL_NAME,
      prediction = finalClass.telemetryName,
      confidence = confidence,
      entropy = entropy,
      lowConfidence = lowConfidence,
      timeMs = combinedTimeMs,
      combinedTimeMs = combinedTimeMs,
      props = shared,
    )
  }

  private fun capture(
    screeningId: String?,
    modelVersion: String,
    name: String,
    prediction: String,
    confidence: Float,
    entropy: Double,
    lowConfidence: Boolean,
    timeMs: Long,
    combinedTimeMs: Long?,
    props: Map<String, Any>,
  ) {
    PostHogAnalytics.capture(
      PostHogAnalytics.Events.MODEL_INFERENCE_COMPLETED,
      mapOf(
        PostHogAnalytics.Props.SCREENING_ID to screeningId,
        PostHogAnalytics.Props.MODEL_NAME to name,
        PostHogAnalytics.Props.MODEL_VERSION to modelVersion,
        PostHogAnalytics.Props.MODEL_PREDICTION to prediction,
        PostHogAnalytics.Props.MODEL_CONFIDENCE to confidence,
        PostHogAnalytics.Props.MODEL_ENTROPY to entropy,
        PostHogAnalytics.Props.LOW_CONFIDENCE to lowConfidence,
        PostHogAnalytics.Props.INFERENCE_TIME_MS to timeMs,
        PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS to combinedTimeMs,
      ) + props,
    )
  }
}

/**
 * Turns each member's raw output into its answer, then applies the combine rule's `photo` block.
 * How an output is read comes from the model's [OutputLayout]: one sigmoid probability (today's
 * 2-class models) or one value per class (softmax probabilities, or logits the app turns into
 * them). Arithmetic for the sigmoid layout is the
 * PyTorch pipeline's, so 2-class results are unchanged.
 */
object ScreeningOutputInterpreter {

  /**
   * @param outputs one array per member in [ModelDescriptor.activeMembers] order, already turned
   *   into probabilities (the PyTorch path applies its Kotlin sigmoid first).
   */
  fun interpret(
    model: ModelDescriptor,
    rule: CombineRule,
    ruleSource: RuleSource,
    outputs: List<FloatArray>,
  ): PhotoClassification {
    val members = model.activeMembers(rule)
    if (outputs.size != members.size) {
      throw OnnxContractException(
        "Expected ${members.size} member output(s) for ${model.id}, got ${outputs.size}",
      )
    }
    val predictions =
      members.mapIndexed { index, member -> predict(model, member, outputs[index]) }
    val finalClass =
      try {
        CombineRuleEvaluator.decidePhoto(
          model.classes,
          predictions.map { it.predictedClass.id },
          rule.photo,
        )
      } catch (e: IllegalStateException) {
        throw OnnxContractException("Rule ${rule.id} can't combine ${members.map { it.id }}: ${e.message}")
      }
    val confidence =
      when (model.confidenceMode) {
        ConfidenceMode.MEAN_MEMBER_CONFIDENCE -> predictions.map { it.confidence }.average().toFloat()
        ConfidenceMode.MEAN_FINAL_CLASS_PROBABILITY ->
          predictions.map { (it.classProbabilities[finalClass.id] ?: 0f) * 100 }.average().toFloat()
      }
    val probability =
      when (model.confidenceMode) {
        ConfidenceMode.MEAN_MEMBER_CONFIDENCE -> predictions.map { it.probability }.average().toFloat()
        ConfidenceMode.MEAN_FINAL_CLASS_PROBABILITY ->
          predictions.map { it.classProbabilities[finalClass.id] ?: 0f }.average().toFloat()
      }
    return PhotoClassification(
      model = model,
      rule = rule,
      ruleSource = ruleSource,
      members = predictions,
      finalClass = finalClass,
      confidence = confidence,
      probability = probability,
      entropy = predictions.map { it.entropy }.average(),
      lowConfidence = confidence < model.lowConfidencePercent,
    )
  }

  private fun predict(model: ModelDescriptor, member: ModelMember, raw: FloatArray): MemberPrediction =
    when (val layout = model.outputLayout) {
      is OutputLayout.SigmoidBinary -> predictSigmoid(model, member, layout, raw)
      is OutputLayout.Softmax -> predictSoftmax(model, member, layout, raw)
    }

  private fun predictSigmoid(
    model: ModelDescriptor,
    member: ModelMember,
    layout: OutputLayout.SigmoidBinary,
    raw: FloatArray,
  ): MemberPrediction {
    if (raw.size != 1) {
      throw OnnxContractException("Member ${member.id} emitted ${raw.size} values, expected 1")
    }
    checkProbabilities(member, raw, hint = "Is Sigmoid missing from the exported graph?")
    val probability = raw[0].coerceIn(0f, 1f)
    val positive = probability > layout.threshold
    val confidence = if (positive) probability * 100 else (1 - probability) * 100
    return MemberPrediction(
      member = member,
      predictedClass =
        model.classById(if (positive) layout.positiveClassId else layout.negativeClassId)!!,
      probability = probability,
      confidence = confidence,
      entropy = binaryEntropy(probability),
      lowConfidence = confidence < model.lowConfidencePercent,
      classProbabilities =
        mapOf(layout.positiveClassId to probability, layout.negativeClassId to 1 - probability),
    )
  }

  private fun predictSoftmax(
    model: ModelDescriptor,
    member: ModelMember,
    layout: OutputLayout.Softmax,
    raw: FloatArray,
  ): MemberPrediction {
    if (raw.size != layout.classOrder.size) {
      throw OnnxContractException(
        "Member ${member.id} emitted ${raw.size} values, expected ${layout.classOrder.size}",
      )
    }
    val values = if (layout.appliedInGraph) raw else softmax(member, raw)
    checkProbabilities(member, values, hint = "Is Softmax missing from the exported graph?")
    val probabilities = values.map { it.coerceIn(0f, 1f) }
    if (abs(probabilities.sum() - 1f) > SOFTMAX_SUM_TOLERANCE) {
      throw OnnxContractException(
        "Member ${member.id} outputs ${raw.contentToString()} do not sum to 1. " +
          "Is Softmax missing from the exported graph?",
      )
    }
    val byClass = layout.classOrder.zip(probabilities).toMap()
    // Highest probability wins; an exact tie goes to the more serious class.
    val predicted =
      model.classes.maxWith(compareBy({ byClass.getValue(it.id) }, { it.severity }))
    val probability = byClass.getValue(predicted.id)
    val confidence = probability * 100
    return MemberPrediction(
      member = member,
      predictedClass = predicted,
      probability = probability,
      confidence = confidence,
      entropy = entropy(probabilities.toFloatArray()),
      lowConfidence = confidence < model.lowConfidencePercent,
      classProbabilities = byClass,
    )
  }

  private fun checkProbabilities(member: ModelMember, raw: FloatArray, hint: String) {
    val outOfRange =
      raw.filter { !it.isFinite() || it < -PROBABILITY_TOLERANCE || it > 1f + PROBABILITY_TOLERANCE }
    if (raw.isEmpty() || outOfRange.isNotEmpty()) {
      throw OnnxContractException(
        "Member ${member.id} outputs ${raw.contentToString()} are not probabilities in [0, 1] " +
          "(offending: $outOfRange). $hint",
      )
    }
  }

  /** Softmax of raw scores, for models whose graph stops at the logits. */
  private fun softmax(member: ModelMember, logits: FloatArray): FloatArray {
    if (logits.any { !it.isFinite() }) {
      throw OnnxContractException(
        "Member ${member.id} outputs ${logits.contentToString()} are not finite scores",
      )
    }
    val highest = logits.max()
    val exponentials = logits.map { exp((it - highest).toDouble()) }
    val sum = exponentials.sum()
    return FloatArray(logits.size) { (exponentials[it] / sum).toFloat() }
  }

  /** The binary entropy the 2-class pipeline always reported. */
  private fun binaryEntropy(probability: Float): Double {
    val p = probability.coerceIn(0.000001f, 0.999999f).toDouble()
    return -p * ln(p) - (1 - p) * ln(1 - p)
  }

  /** Shannon entropy of a softmax output. */
  private fun entropy(probabilities: FloatArray): Double =
    probabilities.sumOf {
      val q = it.coerceIn(0.000001f, 0.999999f).toDouble()
      -q * ln(q)
    }

  const val ENSEMBLE_MODEL_NAME = "ensemble"

  /** Float slack allowed around [0, 1] before the output is treated as non-probabilities. */
  private const val PROBABILITY_TOLERANCE = OnnxModelContract.PROBABILITY_TOLERANCE

  /** How far softmax outputs may sum from 1 (float rounding across a few classes). */
  private const val SOFTMAX_SUM_TOLERANCE = 0.01f
}
