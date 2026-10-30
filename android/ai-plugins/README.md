# AI plugins

The app ships **without** an AI model. To add AI screening, you write a small **plugin**: a Gradle
module that implements `ScreeningModel` from [`:ai-api`](../ai-api) and registers it with Hilt.
Every folder here that has a `build.gradle.kts` is part of the Gradle build, but **none is in the
app unless you name it**:

```bash
./gradlew :quest:assembleOpensrpDebug -PaiPlugins=<folder>[,<folder>…]
```

| Folder | What it is | In a build |
| :-- | :-- | :-- |
| `sample-code-plugin/` | A complete plugin with a colour heuristic standing in for a model. **Demo only — not a medical model.** Copy it to start your own. | Only with `-PaiPlugins=sample-code-plugin` |

How to write your own plugin, step by step:
[docs/ai-model-integration-guide.md](../docs/ai-model-integration-guide.md).
