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

package org.smartregister.fhircore.ai.sample

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton
import org.smartregister.fhircore.ai.ScreeningModel

/** Registers the model with the app. The key must equal the model's descriptor id. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SampleColourModelModule {

  @Binds
  @IntoMap
  @StringKey(SampleColourModel.MODEL_ID)
  @Singleton
  abstract fun bind(model: SampleColourModel): ScreeningModel
}
