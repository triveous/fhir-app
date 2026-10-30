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

import android.content.Context

/**
 * Version of this contract. A model whose [ModelDescriptor.minAppApiVersion] is higher than this
 * was built against a newer app and is refused rather than run against a contract it doesn't know.
 * Only additive changes are made within one version.
 */
const val AI_API_VERSION = 1

/**
 * The black box. The app hands it one captured photo; it returns which class the photo belongs to,
 * with a score per class. What happens in between (decoding, cropping, resizing, normalising, which
 * runtime, how many sub-models) is entirely up to the implementation.
 *
 * The app keeps one instance per process. [warmUp] and [classify] are called off the main thread,
 * and [classify] may be called from more than one thread, so implementations must be thread-safe.
 *
 * Register an implementation with Hilt:
 * ```
 * @Binds @IntoMap @StringKey("my-model-id") abstract fun bind(m: MyModel): ScreeningModel
 * ```
 */
interface ScreeningModel {

  /** Who this model is, which classes it outputs and what each class means to the app. */
  val descriptor: ModelDescriptor

  /**
   * Loads weights and prepares the runtime. Called once before the first [classify]; called again
   * if it threw, so a failed load is retried on the next use. Throw to report a load failure.
   */
  suspend fun warmUp(context: Context)

  /**
   * Classifies one photo. Must return a score in [0, 1] for every class in [descriptor] and a
   * [ScreeningOutput.predictedClassId] that is one of them.
   *
   * Throw when the photo cannot be classified. The app then records "no AI result" for this photo;
   * it never treats a failure as a negative result and never crashes.
   */
  suspend fun classify(image: ScreeningImage): ScreeningOutput

  /** Releases native resources. Optional. */
  fun close() {}
}
