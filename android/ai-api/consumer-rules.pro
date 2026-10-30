# Plugins are found through Hilt, which R8 already understands. Keep the contract's names readable
# in stack traces reported from plugin code.
-keepnames class org.smartregister.fhircore.ai.** { *; }
