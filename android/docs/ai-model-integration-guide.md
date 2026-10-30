# AI Model Integration Guide

**Add your own AI screening model to the app**

| | |
| :-- | :-- |
| **Applies to** | AI contract `AI_API_VERSION = 1` (module `:ai-api`) |
| **Audience** | Android developers adding an AI model to the app; server admins switching AI on |
| **Licence of this document** | Apache-2.0, the same as the code |
| **Related** | [ai-plugins/README.md](../ai-plugins/README.md) · [sample plugin](../ai-plugins/sample-code-plugin) · [design notes](ai_plug_and_play_plan.md) |

The app ships **without** an AI model. To add AI screening, you write a small **plugin**: a Gradle
module with one class that takes **one photo** and returns **which class it belongs to**, with a
score for each class. The app does everything else: it runs your model on each photo, works out a
result for the whole case, shows the result screen, and saves the result to FHIR so it is synced.

---

## How to read this guide

| You are… | Read | Time |
| :-- | :-- | :-- |
| A **developer** who will build the plugin | All sections, in order | 1–2 days for a first working plugin |
| A **server or configuration admin** switching AI on for a deployment | [§8 Switch AI on](#8-switch-ai-on-for-a-deployment) | 15 minutes |
| **Supporting a deployment** that has a problem | [§10 Troubleshooting](#10-troubleshooting) | — |

Words in **bold** the first time they appear are explained in the [Glossary](#glossary).

### Contents

1. [Prerequisites](#1-prerequisites)
2. [What you add, and where](#2-what-you-add-and-where)
3. [Create the plugin module](#3-create-the-plugin-module)
4. [Write the model class](#4-write-the-model-class)
5. [Register the model](#5-register-the-model)
6. [Test it on your computer](#6-test-it-on-your-computer)
7. [Build the app with your plugin](#7-build-the-app-with-your-plugin)
8. [Switch AI on for a deployment](#8-switch-ai-on-for-a-deployment)
9. [Check it works on a phone](#9-check-it-works-on-a-phone)
10. [Troubleshooting](#10-troubleshooting)

- [Reference](#reference)
  - [R1. Module overview](#r1-module-overview)
  - [R2. Contract versioning](#r2-contract-versioning)
  - [R3. What the app saves for every photo](#r3-what-the-app-saves-for-every-photo)
- [FAQ](#faq)
- [Glossary](#glossary)
- [Licensing, privacy and getting help](#licensing-privacy-and-getting-help)

---

## 1. Prerequisites

You need:

- The app building on your machine without AI (see the repository README). Android Studio, JDK 17
  and the Android SDK.
- Working knowledge of **Kotlin** and Gradle. Basic knowledge of **Hilt** (dependency injection)
  helps; you only copy one small file.
- Your model in a form that runs on an Android phone, with an AI engine (library) that can run it
  there. Which engine you use is up to you; the app doesn't depend on any.
- From the model owner:
  - the exact photo preparation used in training: colour order (RGB or BGR), input size, resize
    method, crop, and normalisation (mean and standard deviation);
  - what the model outputs (probabilities or **logits**; which output index is which class);
  - the decision cut-offs (for example "refer if score > 0.5");
  - **5–10 sample photos with the scores the training code gives for them**. You'll use these to
    prove the plugin matches training.

## 2. What you add, and where

```
ai-plugins/
└── acme-oral/                                ← your plugin (any folder name)
    ├── build.gradle.kts                      ← §3
    ├── consumer-rules.pro                    ← §3 (only if your AI engine needs keep rules)
    └── src/
        ├── main/
        │   ├── assets/acme-oral/             ← your model file(s)
        │   └── java/com/acme/oral/
        │       ├── AcmeOralModel.kt          ← §4: your model
        │       └── AcmeOralModule.kt         ← §5: registration
        └── test/
            ├── java/com/acme/oral/AcmeOralModelContractTest.kt   ← §6
            └── resources/sample1.jpg …                           ← sample photos
```

**You don't change anything in `quest/`, `engine/` or the app's Gradle files.**

> **Tip: start by copying [`ai-plugins/sample-code-plugin`](../ai-plugins/sample-code-plugin).**
> It is a complete, tested plugin with exactly these files. Its "model" is a simple colour rule
> (reddish → refer, pale → no referral, dark → retake), so it runs without any model file. It is a
> **demo, not a medical model**. Build it once with
> `-PaiPlugins=sample-code-plugin` to see the whole flow before you add your own model.
>
> ```bash
> cp -r ai-plugins/sample-code-plugin ai-plugins/acme-oral
> ```
>
> Then rename the package, the classes and the `namespace`.

## 3. Create the plugin module

Every folder under `ai-plugins/` that contains a `build.gradle.kts` is added to the Gradle build
automatically, as `:ai-plugins:<folder>`. It's only put into the app when you name it at build time
([§7](#7-build-the-app-with-your-plugin)).

`ai-plugins/acme-oral/build.gradle.kts` (the same as the sample plugin's, with your names):

```kotlin
plugins {
  id("com.android.library")
  id("kotlin-android")
  id("kotlin-kapt")
}

android {
  compileSdk = BuildConfigs.compileSdk
  namespace = "com.acme.oral"

  defaultConfig {
    minSdk = BuildConfigs.minSdk
    consumerProguardFiles("consumer-rules.pro") // only if your AI engine needs keep rules
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions { jvmTarget = JavaVersion.VERSION_17.toString() }

  testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
  // :ai-api is the only app module a plugin may depend on.
  implementation(project(":ai-api"))
  implementation(libs.dagger.hilt.android)
  kapt(libs.dagger.hilt.compiler)
  // Add your AI engine's library here.

  testImplementation(project(":ai-api-testing"))
  testImplementation(libs.robolectric)
}
```

Check your AI engine's documentation for two things:

- **Keep rules.** Many engines use native code, and release builds (R8) can remove classes that
  native code needs. Put the engine's recommended keep rules in
  `ai-plugins/acme-oral/consumer-rules.pro`.
- **Uncompressed model files.** Some engines need the model file to be stored uncompressed in the
  APK. If so, add `androidResources { noCompress += listOf("<your model file extension>") }` to the
  `android { }` block.

### Where the model file goes

Put model files in `src/main/assets/<a folder of your choice>/`, for example
`src/main/assets/acme-oral/`. They are packed into the APK.

> ⚠️ **Common model files are not committed to git by default.** Several model file types under
> `ai-plugins/*/src/main/assets/` are listed in `.gitignore`; add your own file type there if it
> isn't. This stops weights you have no right to
> publish from being committed by accident. Copy them onto your build machine (or CI) before
> building. If you do want them in your own fork, remove those lines from `.gitignore`.

## 4. Write the model class

This class is your model, as the app sees it. It implements the `ScreeningModel` interface, which has three parts:

| Part | When the app calls it | What you do |
| :-- | :-- | :-- |
| `descriptor` | Once, at start-up | Say who the model is and what each class means |
| `warmUp(context)` | Once, in the background, before the first photo | Load your model file |
| `classify(image)` | Once per photo, in the background | Prepare the photo, run the model, return scores |
| `close()` (optional) | When the app is done with the model | Free memory |

The outline below shows what goes where. The `TODO` lines are where your own AI engine code goes;
the rest you can copy as is. For a version that runs, see `SampleColourModel` in the
[sample plugin](../ai-plugins/sample-code-plugin).

```kotlin
package com.acme.oral

import android.content.Context
import javax.inject.Inject
import org.smartregister.fhircore.ai.ClassDescriptor
import org.smartregister.fhircore.ai.ModelDescriptor
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.ai.ScreeningImage
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.ScreeningOutput

class AcmeOralModel @Inject constructor() : ScreeningModel {

  // 1. Who the model is and what each class means to the app.
  override val descriptor =
    ModelDescriptor(
      id = MODEL_ID,
      version = "1.0.0",
      classes =
        listOf(
          ClassDescriptor(
            "no_refer",
            Outcome.NEGATIVE,
            displayName = mapOf("en" to "No referral needed", "hi" to "रेफ़रल की ज़रूरत नहीं"),
          ),
          ClassDescriptor(
            "refer",
            Outcome.POSITIVE,
            displayName = mapOf("en" to "Refer", "hi" to "रेफ़र करें"),
            resultTitle = mapOf("en" to "Refer to a specialist"),
          ),
        ),
    )

  // 2. Load the model once. Throw if it can't be loaded; the app will retry on the next photo.
  override suspend fun warmUp(context: Context) {
    // TODO: load your model, for example from context.assets.open("acme-oral/<your model file>").
  }

  // 3. One photo in, scores out. Throw if the photo can't be classified.
  override suspend fun classify(image: ScreeningImage): ScreeningOutput {
    // TODO: prepare the photo exactly as in training (see "Getting the photo" below), run your
    // model, and turn its output into a score from 0 to 1 for each class.
    val pRefer: Float = TODO("your model's probability of 'refer'")

    val scores = mapOf("refer" to pRefer, "no_refer" to 1f - pRefer)
    return ScreeningOutput(
      scores = scores,
      predictedClassId = if (pRefer > THRESHOLD) "refer" else "no_refer", // your own cut-off
    )
  }

  // 4. Optional: free what warmUp loaded.
  override fun close() {
    // TODO: release your model, if your AI engine needs it.
  }

  companion object {
    const val MODEL_ID = "acme-oral"
    private const val THRESHOLD = 0.5f
  }
}
```

If your model outputs **logits** (any real number) instead of probabilities, convert them first:
`sigmoid` for one output, `softmax` for several. The app rejects scores outside 0–1.

### Getting the photo: `ScreeningImage`

`ScreeningImage` gives you the full-resolution photo and some helpers:

| Member | What it gives you |
| :-- | :-- |
| `file` | The JPEG file as taken. Read it yourself if you need your own decoder |
| `decodeBitmap(maxSide)` | An `ARGB_8888` `Bitmap`, rotated upright using the photo's EXIF orientation, optionally scaled down so its longest side ≤ `maxSide` |
| `decodeRgbBytes(w, h)` | The photo resized to `w × h` (bilinear, aspect ratio not kept), as interleaved R,G,B bytes |
| `decodeBgrBytes(w, h)` | The same in B,G,R order (the layout of OpenCV's `cv2.resize(cv2.imread(path), (w, h))`) |
| `captureMetadata` | Device model, manufacturer and OS version (keys in `ScreeningImage.MetadataKeys`) |

It never contains patient data.

> ⚠️ **The most common mistake: photo preparation that differs from training.** If training used a
> different decoder, resize method, crop, colour order or normalisation, the
> model still returns plausible-looking scores, but they are wrong. If the helpers above don't match
> your training exactly, read `image.file` yourself and do the same as training. [§6b](#6b-the-same-as-training-test-strongly-recommended) shows how to
> prove they match.

### Rules for `classify`

| Rule | Why |
| :-- | :-- |
| Return a score in **[0, 1] for every class** in the descriptor, and no other classes | The app validates the output and treats anything else as a failure |
| `predictedClassId` must be one of your classes. **You** choose it, with your own cut-offs | The app uses your decision as is; it doesn't second-guess it |
| `confidence` is optional. It defaults to the score of the predicted class | Set it only if your confidence is defined differently |
| If anything goes wrong, **throw an exception** | The app records "AI failed" for that photo. It never treats a failure as NEGATIVE and never crashes |
| Finish within **15 seconds** | A slower call is abandoned and counted as a failure |
| Be **thread-safe** (it may be called for several photos at once), or guard it with a `Mutex` | Prevents mixed-up results |
| Give the same scores for the same photo | Results must be reproducible and auditable |
| **Don't send the photo off the phone**, unless your deployment has approved it | Patient privacy |
| For several models combined (an **ensemble**), put each one's scores in `components` | They are saved for audit |
| Put any other values you want saved (strings) in `extras` | They are saved with the result |

### The descriptor fields

| Field | Required | What it does |
| :-- | :-- | :-- |
| `id` | Yes | The model's unique name, for example `acme-oral`. Shown in Profile, saved with every result, and used to choose a model |
| `version` | Yes | Saved with every result, so each result can be traced to a model version. Change it whenever the weights or the logic change |
| `classes[].id` | Yes | The class name your code uses in `scores` and `predictedClassId` |
| `classes[].outcome` | Yes | `POSITIVE`, `NEGATIVE` or `INCONCLUSIVE` — needs referral, no referral needed, or the model can't tell (for example a poor photo). A case is POSITIVE if any photo is POSITIVE; otherwise INCONCLUSIVE if any photo is INCONCLUSIVE or failed; otherwise NEGATIVE |
| `classes[].displayName` | Recommended | The label health workers see, per language code (`en`, `hi`, …). Falls back to English, then to the class id |
| `classes[].severity` | No | Orders classes that share an outcome (higher = worse). Picks the case's class when photos disagree. Default 0 |
| `classes[].resultTitle`, `resultDescription` | No | Your own result-screen text, per language. Default: the app's own text |
| `lowConfidenceThreshold` | No | Results with a confidence below this (0–1) are flagged "low confidence". Default 0.65. For information only; it doesn't change the outcome |
| `minAppApiVersion` | No | The `AI_API_VERSION` your plugin needs. Leave the default |

## 5. Register the model

The app finds models through Hilt. Add this file next to your model class:

```kotlin
package com.acme.oral

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton
import org.smartregister.fhircore.ai.ScreeningModel

@Module
@InstallIn(SingletonComponent::class)
abstract class AcmeOralModule {

  @Binds
  @IntoMap
  @StringKey(AcmeOralModel.MODEL_ID) // must equal descriptor.id
  @Singleton
  abstract fun bind(model: AcmeOralModel): ScreeningModel
}
```

The key in `@StringKey` **must equal** `descriptor.id`. If it doesn't, the app refuses the model and
shows why in Profile.

## 6. Test it on your computer

### 6a. The contract test (required)

`:ai-api-testing` contains a ready-made test that checks every rule in [§4](#rules-for-classify).
Extend it and give it your sample photos:

```kotlin
package com.acme.oral

import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.testing.ScreeningModelContractTest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real image decoding, so the pixels are the photo's
class AcmeOralModelContractTest : ScreeningModelContractTest() {

  override fun createModel(): ScreeningModel = AcmeOralModel()

  override fun sampleImages(): List<File> = listOf("sample1.jpg", "sample2.jpg").map(::resource)

  private fun resource(name: String) = File(checkNotNull(javaClass.getResource("/$name")).toURI())
}
```

Put the photos in `src/test/resources/`. Then run:

```bash
./gradlew :ai-plugins:acme-oral:testDebugUnitTest
```

It checks that:

- the descriptor is valid and the plugin's API version is supported;
- every sample photo gets valid scores within 15 seconds;
- the same photo gives the same result twice;
- several photos classified at the same time give the same results as one at a time;
- an unreadable file makes the model throw, rather than return a result.

### 6b. The "same as training" test (strongly recommended)

The contract test proves the plugin is well-behaved, not that it's **correct**. To prove that, run
the sample photos through the model owner's training code (for example Python), write down the
scores, and assert that the plugin gives the same. Add a test like this to the class above:

```kotlin
@Test
fun `scores match the training pipeline`() = runBlocking {
  val model = AcmeOralModel().apply { warmUp(ApplicationProvider.getApplicationContext()) }
  val output = model.classify(ScreeningImage(resource("sample1.jpg")))
  assertEquals(0.912f, output.scores.getValue("refer"), 0.01f) // value from the Python pipeline
  model.close()
}
```

If the numbers differ, the photo preparation differs from training ([§4](#getting-the-photo-screeningimage)).

> If your AI engine has no desktop (JVM) build, run these tests as instrumented tests on a phone
> instead (`src/androidTest`, `./gradlew :ai-plugins:acme-oral:connectedDebugAndroidTest`).

## 7. Build the app with your plugin

```bash
./gradlew :quest:assembleOpensrpDebug -PaiPlugins=acme-oral
```

The build log shows `AI plugins: acme-oral`. Without `-PaiPlugins`, the app is built **without**
AI.

| Option | What it does |
| :-- | :-- |
| `-PaiPlugins=a,b` | Plugins to include. Each one is a folder name under `ai-plugins/`, a Gradle project path (`:my:module`), or Maven coordinates (`com.acme:oral-model:1.0.0`) for a plugin published to a private repository |
| `-PaiModelId=<id>` | Which model to use when the APK contains more than one |
| `-PaiRequireModel` | Makes release builds fail if no plugin is included. Use it in CI when your app must have AI |

Example release build for CI:

```bash
./gradlew :quest:assembleOpensrpRelease -PaiPlugins=acme-oral -PaiRequireModel
```

> **Keeping a plugin private.** A plugin doesn't have to live in this repository. Publish it to a
> private Maven repository and include it with `-PaiPlugins=com.acme:oral-model:1.0.0`. Your code
> and weights then never appear in the public repository.

## 8. Switch AI on for a deployment

A model inside the APK is only used when AI is switched on for the deployment (**tenant**) on the
server. This lets you install the app everywhere and turn AI on site by site. You need two settings.

### 1. The feature flag

In the tenant's feature-flags `Basic` resource (id `<tenant-slug>-feature-flags`), set this
extension to `true`:

```
https://midas.iisc.ac.in/fhir/StructureDefinition/ai-inference-enabled
```

If the APK contains more than one model, also add this string extension with your model's id:

```
https://midas.iisc.ac.in/fhir/StructureDefinition/ai-model-id
```

The app chooses the model in this order: the tenant's `ai-model-id`, then the build's
`-PaiModelId`, then the only model in the APK.

### 2. Saving the AI result into the Encounter

The registration `StructureMap` must have a rule that reads the hidden QuestionnaireResponse item
`case-level-ai-result`. Its value is:

| Case outcome | Value |
| :-- | :-- |
| POSITIVE | `suspicious` |
| NEGATIVE | `non-suspicious` |
| INCONCLUSIVE | `inconclusive` |

Models with more than two classes also fill `case-level-ai-class` with your class id for the case.

If this rule is missing, the app shows **"Failed to enable AI"** when someone adds a case, rather
than silently losing results.

## 9. Check it works on a phone

1. Install the APK and log in as a user of the tenant where AI is switched on.
2. Sync.
3. Open **Profile**. You should see **AI Enabled + Extraction Enabled** and
   **Model: acme-oral 1.0.0**.
4. Add a test case and take photos. Each photo should show an AI label, and the AI result screen
   should appear when you submit.
5. Repeat with photos that should give each outcome (POSITIVE, NEGATIVE and, if you have it,
   INCONCLUSIVE), on each phone model your health workers use.
6. After syncing, check on the server that the photo's `DocumentReference` carries the AI result
   ([§R3](#r3-what-the-app-saves-for-every-photo)).

## 10. Troubleshooting

See the logs with:

```bash
adb logcat | grep -iE "AI model|ScreeningEngine"
```

plus your plugin's own log tag.

| You see | Likely cause | Fix |
| :-- | :-- | :-- |
| Profile: **No AI model installed** | Plugin not passed with `-PaiPlugins`, or its Hilt module is missing `@IntoMap @StringKey` | Check [§5](#5-register-the-model), rebuild with `-PaiPlugins=<folder>` and look for `AI plugins: …` in the build log |
| Profile: **AI model failed to load: …** | `warmUp` threw (missing model file, native library, wrong path), the `@StringKey` doesn't equal `descriptor.id`, or the plugin needs a newer `AI_API_VERSION` | The message and logcat give the exact reason |
| Profile: **Several AI models installed; none selected** | More than one plugin in the APK and no choice made | Build with `-PaiModelId=<id>` or set the tenant's `ai-model-id` |
| Profile doesn't show AI at all, or AI is off | The tenant feature flag is off, or the phone hasn't synced | [§8](#8-switch-ai-on-for-a-deployment), then sync |
| Every photo gets the same answer, or results differ from Python | Photo preparation differs from training: colour order, resize method, crop or normalisation | Write the "same as training" test ([§6b](#6b-the-same-as-training-test-strongly-recommended)) |
| Every case comes out **"AI could not decide"** | The model fails or times out on every photo | Check logcat; run the contract test |
| Contract test: "outside [0, 1]. Are logits being returned…" | The model returns raw logits | Apply `sigmoid` / `softmax` in `classify` |
| **"Failed to enable AI"** when adding a case | The server StructureMap has no `case-level-ai-result` rule | [§8](#8-switch-ai-on-for-a-deployment), part 2 |
| Release build fails: "no AI plugin is included" | `-PaiRequireModel` without `-PaiPlugins` | Add `-PaiPlugins=<folder>` |
| Release build crashes inside the AI engine's native code | R8 removed classes the engine's native code needs | Add the engine's keep rules to your plugin's `consumer-rules.pro` ([§3](#3-create-the-plugin-module)) |
| Build can't find the model file | Model files are git-ignored and weren't copied onto this machine | Copy them into `src/main/assets/…` ([§3](#where-the-model-file-goes)) |

---

# Reference

## R1. Module overview

| Module | Licence | What it is |
| :-- | :-- | :-- |
| `:ai-api` | Apache-2.0 | The contract: `ScreeningModel`, `ModelDescriptor`, `ClassDescriptor`, `Outcome`, `ScreeningImage`, `ScreeningOutput`, `AI_API_VERSION`. The only module a plugin depends on |
| `:ai-api-testing` | Apache-2.0 | `ScreeningModelContractTest`, the test kit for plugins |
| `ai-plugins/sample-code-plugin` | Apache-2.0 | A runnable demo plugin to copy |
| `ai-plugins/<yours>` | Your choice | Your plugin |

## R2. Contract versioning

`AI_API_VERSION` (currently **1**) in `:ai-api` is the contract version. If the contract changes in
a way that breaks plugins, it goes up. A plugin built for a newer version than the app has is
refused, with a clear message in Profile, instead of misbehaving.

## R3. What the app saves for every photo

| Where | What |
| :-- | :-- |
| `DocumentReference` (the photo), the QuestionnaireResponse answer and the Questionnaire item | Extension `http://smartregister.org/fhir/StructureDefinition/ai-screening-result`, with sub-extensions `status` (completed / failed), `modelId`, `modelVersion`, `classId`, `outcome`, `displayName`, `severity`, `confidence`, `lowConfidence`, `inferenceTimeMs`, `score` (one per class), `component` (one per sub-model) and `extra` |
| The same elements (for compatibility) | `https://midas.iisc.ac.in/CodeSystem/ai-result-value` = Suspicious / Non-Suspicious / Inconclusive, and `…/ai-result-confidence` = percent |
| QuestionnaireResponse hidden items | `<photo-linkId>-ai-result` (for example `Suspicious\|94.2`), `case-level-ai-result`, `case-level-ai-class` |
| Analytics (if enabled for the deployment) | `model_inference_completed` per sub-model and overall (model id, version, class, outcome, confidence, time); `photo_captured`; `ai_inference_completed` per case; `ai_model_unavailable` (AI on but no usable model, with the reason) |

All results are stored as standard HL7 **FHIR** resources, so they can be read by any
FHIR-compatible system.

---

# FAQ

**Which AI engines can I use?**
Any that runs on an Android phone, or plain Kotlin code. The app doesn't depend on a
specific one.

**Can I combine several models?**
Yes. Combine them however you like inside `classify`, and report each one in `components`.

**Can I change the text on the result screen?**
Yes: `resultTitle` and `resultDescription` per class and per language. No app changes needed.

**Does the app re-check my model's decision?**
No. It uses your predicted class. It only adds a "low confidence" flag from
`lowConfidenceThreshold`, and refuses invalid output.

**Can I ship the app without any model?**
Yes. That is the default; the app works fully without AI.

**Can I update the model without changing the app?**
The model is part of the APK, so a new model means a new app build. Change `version` in the
descriptor so results from each version can be told apart.

**Does the model need internet?**
No. It runs on the phone, so it works offline.

**Who is responsible for the model's results?**
The organisation that deploys it. The app's authors provide the plumbing only; they don't validate
or approve any model.

---

# Glossary

| Term | Meaning |
| :-- | :-- |
| **AI engine** | The library that runs a model file on the phone; you choose it |
| **AI model** | A file produced by machine learning that turns a photo into scores |
| **APK** | The Android app file installed on phones |
| **Class** | One possible answer of the model, such as `refer` or `poor_photo` |
| **Confidence** | How sure the model is about its answer, from 0 to 1 |
| **Contract** | The fixed set of rules (the `ScreeningModel` interface) that every plugin follows |
| **Descriptor** | The part of a plugin that says who the model is and what each class means |
| **Ensemble** | Several models whose answers are combined into one |
| **FHIR** | An international standard for exchanging health records; the server stores results in this format |
| **Hilt** | The Android library the app uses to find plugins |
| **Logits** | Raw model outputs that aren't yet probabilities; converted with sigmoid or softmax |
| **Outcome** | What a class means to the app: POSITIVE (refer), NEGATIVE (no referral) or INCONCLUSIVE (can't tell) |
| **Plugin** | A small add-on module that contains your model and connects it to the app |
| **Pre-processing** | Preparing a photo for the model: resizing, cropping, colour order and normalisation |
| **Score** | A number from 0 to 1 that the model gives each class |
| **StructureMap** | A server-side FHIR rule that turns form answers into health records |
| **Tenant** | One deployment (for example one programme or region) on a shared server, with its own settings |

---

# Licensing, privacy and getting help

**Licensing.** The app, `:ai-api`, `:ai-api-testing` and the sample plugin are licensed under
Apache-2.0 (see [LICENSE](../../LICENSE)). Your model and your plugin keep their own licence; the
app doesn't require you to publish them.

**Privacy.** The app gives the model only the photo and basic phone details, never patient
identifiers. The model runs on the phone. If you change that (for example a plugin that calls a
cloud service), you are responsible for consent, data-protection approval and compliance with the
laws of your country.

**Clinical responsibility.** You are responsible for your model's clinical validation, regulatory
approval and ongoing monitoring in your country.

**Getting help and contributing.** Ask questions or report problems by opening an issue in this
repository. Please don't attach patient photos or any personal data. Improvements to this guide or
to the sample plugin are welcome as pull requests.
