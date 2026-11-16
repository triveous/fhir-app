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

package org.smartregister.fhircore.engine.util

/**
 * The site's AI model settings, read from the nested `ai-model` extension on the feature-flags
 * Basic (see [FeatureFlagUtil.AI_MODEL_URL]).
 *
 * Released apps skip `ai-model` because it carries no value of its own, so adding it to a server
 * never changes what older phones do.
 *
 * @property modelId the model the site runs, e.g. `oral-3class-v1`. Null when the site sets none,
 *   in which case the app uses its default (today's 2-class) model.
 * @property rule the optional combine-rule override, as the JSON string the server stores. Null
 *   when the site uses the rule that ships with the model. The app validates it before use.
 */
data class AiModelConfig(val modelId: String? = null, val rule: String? = null) {
  companion object {
    val NONE = AiModelConfig()
  }
}
