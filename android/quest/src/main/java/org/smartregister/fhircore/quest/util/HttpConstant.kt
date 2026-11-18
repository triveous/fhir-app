package org.smartregister.fhircore.quest.util

/**
 * Created by Jeetesh Surana.
 */

const val SUSPICIOUS_NON_SUSPICIOUS_URL="https://midas.iisc.ac.in/CodeSystem/ai-result-value"
const val CONFIDENCE_PERCENTAGE_URL="https://midas.iisc.ac.in/CodeSystem/ai-result-confidence"
const val CASE_LEVEL_AI_RESULT_URL="https://midas.iisc.ac.in/CodeSystem/case-level-ai-result"
const val REFER_CASE_URL="https://midas.iisc.ac.in/CodeSystem/refer-case"

const val AI_RESULT_SUFFIX = "-ai-result"
const val CASE_LEVEL_AI_RESULT_LINK_ID = "case-level-ai-result"

/** Case answer naming the model that produced the result, e.g. `oral-3class-v1`. */
const val CASE_LEVEL_AI_MODEL_LINK_ID = "case-level-ai-model"

/** Case answer naming the combine rule that decided the photos, e.g. `oral-3class-v1/m1-m2-table` or `oral-3class-v1/m1-only`. */
const val CASE_LEVEL_AI_RULE_LINK_ID = "case-level-ai-rule"
