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

package org.smartregister.fhircore.quest.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import org.smartregister.fhircore.ai.ScreeningModel

/**
 * Declares the map AI code plugins add themselves to with `@Binds @IntoMap @StringKey("<id>")`.
 * Declaring it here keeps the default build — no plugins — compiling with an empty map.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScreeningModelModule {

  @Multibinds abstract fun screeningModels(): Map<String, ScreeningModel>
}
