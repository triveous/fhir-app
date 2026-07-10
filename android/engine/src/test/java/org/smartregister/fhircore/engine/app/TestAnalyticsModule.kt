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

package org.smartregister.fhircore.engine.app

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.smartregister.fhircore.engine.util.analytics.AnalyticsLogger

@Module
@InstallIn(SingletonComponent::class)
object TestAnalyticsModule {
  @Provides
  fun provideAnalyticsLogger(): AnalyticsLogger =
    object : AnalyticsLogger {
      override fun capture(event: String, properties: Map<String, Any?>?) {
        // No-op for engine unit tests.
      }
    }
}
