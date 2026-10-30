# AI Plug-and-Play: Design (AAR-585)

**Goal.** Anyone who deploys the app can bring their own screening model **and** their own
implementation of it (runtime, pre-processing, thresholds, ensembling) without forking the app. The
app gives a captured photo to a black box. The black box returns a classification. The app handles
the rest the same way for every model: per-photo result, case result, AI result screen, FHIR
persistence and analytics.

**One way to plug in: a code plugin.** A model is a Gradle module that implements `ScreeningModel`
from `:ai-api` and registers it with Hilt. There is no config-only path: the plugin owns everything
about the model.

**No model ships with the app.** A fresh clone builds and runs with no AI model. Photo capture,
case registration and sync all work; the AI step is skipped, the same as with the AI feature flag
off.

The step-by-step guide for adopters is [ai-model-integration-guide.md](ai-model-integration-guide.md).

---

## 1. Architecture

```
                 ┌──────────────── app (quest) ────────────────┐
 Camera capture ─▶ ScreeningEngine ──▶ ScreeningModel (black box) │ ◀── plugin module(s), -PaiPlugins
                 │   • picks the active model                   │
                 │   • warm-up, timeout, threading, errors      │
                 │   • validates the output, telemetry          │
                 │        ▼                                     │
                 │   ImageScreeningResult (class → outcome)     │
                 │        ▼                                     │
                 │   AiResultFhirMapper ──▶ FHIR extensions     │  generic extension + legacy ones
                 │        ▼                                     │
                 │   CaseAggregator ──▶ case-level-ai-result    │  → server StructureMap → Encounter
                 │        ▼                                     │
                 │   AIResultActivity (3 states)                │
                 └──────────────────────────────────────────────┘
```

**Key idea.** Everything downstream of the model depends on an **outcome**
(`POSITIVE` = refer, `NEGATIVE` = no referral, `INCONCLUSIVE` = can't tell), never on a label
string. Labels and result-screen text come from the model's descriptor. A 2-class, 3-class or
N-class model maps its classes onto these three outcomes; that is how the app handles any model's
output without knowing what its classes mean.

## 2. The contract (`:ai-api`)

A small Android library with no dependency on `quest`, FHIR, Hilt or OpenCV (Apache-2.0). It is the
only thing a plugin compiles against.

| Type | Role |
| :-- | :-- |
| `ScreeningModel` | The black box: `descriptor`, `suspend warmUp(context)`, `suspend classify(image)`, `close()` |
| `ScreeningImage` | The full-resolution photo file plus capture metadata (device, OS). Helpers: `decodeBitmap`, `decodeRgbBytes`, `decodeBgrBytes`. Never patient data |
| `ScreeningOutput` | Score in [0, 1] per class, `predictedClassId`, optional `confidence`, `components` (ensemble members), `extras` (opaque, saved for audit) |
| `ModelDescriptor` / `ClassDescriptor` | Model id and version; per class: outcome, display name and result-screen text per language, severity |
| `ScreeningOutputValidator` | Rejects missing, NaN or out-of-range scores and unknown classes |
| `AI_API_VERSION` | Contract version. A plugin needing a newer one is refused with a clear reason |

Design notes:
- **The photo goes in as a file, not a tensor.** Pre-processing belongs to the plugin, so it can
  match its training pipeline exactly.
- **Scores, not just a label.** Needed for confidence, entropy, the low-confidence flag and audits.
  A model with only a hard label returns 1 for that class and 0 for the others.
- **The plugin owns its decision.** The app never re-thresholds `predictedClassId`.

`:ai-api-testing` provides `ScreeningModelContractTest`, which a plugin extends to check every rule
of the contract on its own sample photos.

## 3. App side

- **`ScreeningEngine`** (`quest/ml/`, singleton). Holds the Hilt map `Map<String, ScreeningModel>`
  (empty by default, declared in `di/ScreeningModelModule`). Picks the active model: the tenant's
  `ai-model-id` feature flag, else `-PaiModelId` (`BuildConfig.DEFAULT_AI_MODEL_ID`), else the only
  installed model. Warms it up once per process (started when the camera opens). Runs `classify` with
  a 15 s timeout, validates the output, builds an `ImageScreeningResult` and sends analytics. A
  plugin registered under a key that differs from its descriptor id, or needing a newer API, is
  reported as unusable.
- **AI on/off.** AI is active only when the tenant flag is on **and** a model is selected. With no
  model, the app behaves as with the flag off. A selected model that fails to load still counts as
  on: its photos are recorded as "AI failed", so a broken model is visible instead of silently
  skipped.
- **Camera** (`CameraxLauncherFragment`/`ViewModel`). Image-quality analysis stays in the app; the
  photo is then handed to `ScreeningEngine.screen`. One fragment-result key carries the serialised
  result, plus a status key (completed/failed).
- **Per-photo FHIR** (`quest/ai/AiResultFhirMapper`). Writes the generic
  `ai-screening-result` extension (status, model id/version, class, outcome, confidence, scores,
  components, extras) and, for compatibility with existing servers and dashboards, the extensions
  earlier versions of the app wrote. Reads both, so drafts saved by an older app still summarise.
- **Case result** (`quest/ai/CaseAggregator`). Any POSITIVE photo → POSITIVE; otherwise any
  INCONCLUSIVE or failed photo, or no classified photo → INCONCLUSIVE; otherwise NEGATIVE.
  Written as `case-level-ai-result` (`suspicious` / `non-suspicious` / `inconclusive`) and
  `case-level-ai-class` (the case's class id).
- **Result screen** (`AIResultActivity`). Three states. Title and description can be overridden per
  class and language by the descriptor.
- **Profile.** Shows the active model and version, or why there is none.
- **Build** (`quest/build.gradle.kts`). `-PaiPlugins` adds each plugin as a dependency (folder under
  `ai-plugins/`, project path or Maven coordinates); `settings.gradle.kts` auto-includes every
  folder under `ai-plugins/` that has a build file. `-PaiRequireModel` fails release builds that
  include no plugin. Model file extensions are kept uncompressed in the APK. The app itself carries
  no ML runtime: each plugin brings its own.

## 4. Safety requirements (clinical context)

- **Traceability.** Every result stores the model id and version in FHIR.
- **Fail closed to "AI failed", never to "negative".** A plugin error, timeout or invalid output is
  recorded as a failure, and the case can be at best INCONCLUSIVE.
- **Output validation.** NaN, out-of-range or missing class scores are rejected.
- **Resource limits.** Classification runs off the main thread with a timeout; a slow call is
  abandoned. Models are loaded once per process.
- **No patient data to plugins.** `ScreeningImage` carries only the photo and capture metadata.
  Plugins must not send photos off the device unless the deployment accepts that.

## 5. Out of scope (later)

- Downloading models over the air from the server.
- Non-classification outputs (segmentation masks, bounding boxes). `extras` can carry them, but the
  app won't render them.
- Different models for different photo questions. Today one active model serves every photo.

## 6. Still open

- **Device QA.** Scenarios: a real model, the sample plugin, no model, a model that fails to load, a
  slow model, flag off, drafts saved by an older app.
- **Server StructureMap:** map `case-level-ai-result = inconclusive`, and `case-level-ai-class` for
  multi-class models.
- **Figma** for the INCONCLUSIVE result screen. The current one is a neutral take on the existing
  screens (alert icon, grey background, "Refer case" + "Complete").
- **Translations.** The new texts are in English and Hindi; other languages fall back to English.
