/*
 * Copyright 2021-2024 Ona Systems, Inc
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

package org.smartregister.fhircore.ai

/** A [ScreeningModel] returned something that breaks the contract. */
class InvalidScreeningOutputException(message: String) : IllegalStateException(message)

/**
 * Checks a [ScreeningOutput] against its model's [ModelDescriptor]. The app runs this on every
 * result, and the contract test kit runs it in adopters' unit tests, so a broken export (logits
 * instead of probabilities, a renamed class) is caught instead of silently mis-screening.
 */
object ScreeningOutputValidator {

  /** Float slack allowed around [0, 1]. */
  const val SCORE_TOLERANCE = 1e-4f

  fun validate(descriptor: ModelDescriptor, output: ScreeningOutput) {
    val where = "Model '${descriptor.id}'"
    checkScores(where, descriptor, output.scores)
    checkPrediction(where, descriptor, output.predictedClassId)
    output.confidence?.let { checkUnit("$where confidence", it) }
    output.components.forEach { component ->
      val componentWhere = "$where component '${component.name}'"
      checkScores(componentWhere, descriptor, component.scores)
      checkPrediction(componentWhere, descriptor, component.predictedClassId)
      component.confidence?.let { checkUnit("$componentWhere confidence", it) }
    }
  }

  private fun checkScores(where: String, descriptor: ModelDescriptor, scores: Map<String, Float>) {
    val expected = descriptor.classes.map { it.id }.toSet()
    val missing = expected - scores.keys
    if (missing.isNotEmpty()) fail("$where returned no score for classes $missing")
    val unknown = scores.keys - expected
    if (unknown.isNotEmpty()) fail("$where returned scores for unknown classes $unknown")
    scores.forEach { (classId, score) -> checkUnit("$where score for '$classId'", score) }
  }

  private fun checkPrediction(where: String, descriptor: ModelDescriptor, classId: String) {
    if (descriptor.classById(classId) == null) {
      fail("$where predicted unknown class '$classId'")
    }
  }

  private fun checkUnit(what: String, value: Float) {
    if (!value.isFinite() || value < -SCORE_TOLERANCE || value > 1f + SCORE_TOLERANCE) {
      fail("$what is $value, outside [0, 1]. Are logits being returned instead of probabilities?")
    }
  }

  private fun fail(message: String): Nothing = throw InvalidScreeningOutputException(message)
}
