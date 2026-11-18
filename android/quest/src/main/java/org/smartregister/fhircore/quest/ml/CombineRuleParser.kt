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

package org.smartregister.fhircore.quest.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The outcome of reading a remote rule: the rule, or why it can't be used. */
sealed interface RuleParseResult {
  data class Accepted(val rule: CombineRule) : RuleParseResult

  /** [ruleId] is the rule's `id` when it could be read, for telemetry. */
  data class Rejected(val reason: String, val ruleId: String? = null) : RuleParseResult
}

/**
 * Reads a combine rule from the JSON string stored in `ai-model.rule` and checks it against the
 * model it is for. The format, every field and its allowed values are documented in
 * `docs/features/ai-model/combine-rule.schema.json`.
 *
 * Strict on purpose: a rule changes referral decisions, so anything this app version does not
 * fully understand is rejected rather than half-applied. That includes unknown fields and the
 * options the schema marks "Later" (`mean`, `weights`, `decideBy`, `thresholds`, `lowConfidence`,
 * `minPhotos`, and `inconclusive` as a result, which has no result screen yet). The caller then
 * uses the rule shipped with the model.
 */
object CombineRuleParser {

  private val TOP_LEVEL = setOf("schema", "id", "version", "modelId", "members", "photo", "case")
  private val TOP_LEVEL_LATER = setOf("lowConfidence")
  private val PHOTO_FIELDS = setOf("method", "votesNeeded", "countMoreSevere", "fallback")
  private val PHOTO_TABLE_FIELDS = setOf("method", "table")
  private val PHOTO_LATER = setOf("weights", "decideBy", "thresholds")
  private val CASE_FIELDS = PHOTO_FIELDS + "onFailedPhoto"
  private val CASE_LATER = setOf("minPhotos")

  fun parse(json: String, model: ModelDescriptor): RuleParseResult {
    val root =
      try {
        Json.parseToJsonElement(json) as? JsonObject
      } catch (e: Exception) {
        null
      } ?: return RuleParseResult.Rejected("rule is not a JSON object")
    val ruleId = (root["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return try {
      RuleParseResult.Accepted(read(root, model))
    } catch (e: InvalidRule) {
      RuleParseResult.Rejected(e.message.orEmpty(), ruleId)
    }
  }

  private fun read(root: JsonObject, model: ModelDescriptor): CombineRule {
    checkFields(root, "rule", TOP_LEVEL, TOP_LEVEL_LATER)

    val schema = root.int("schema") ?: invalid("schema is required and must be a whole number")
    if (schema > CombineRule.SCHEMA) invalid("schema $schema is newer than this app supports")
    if (schema != CombineRule.SCHEMA) invalid("schema $schema is not supported")

    val id = root.string("id")?.takeIf { it.isNotBlank() } ?: invalid("id is required")
    val version = root.int("version")?.takeIf { it >= 1 } ?: invalid("version must be 1 or more")
    val modelId = root.string("modelId") ?: invalid("modelId is required")
    if (modelId != model.id) invalid("modelId $modelId does not match the active model ${model.id}")

    val members = root["members"]?.let { readMembers(it, model) }
    val activeMemberCount = members?.size ?: model.members.size

    val photo: PhotoRule =
      (root["photo"] as? JsonObject)?.let {
        if (it.string("method") == METHOD_TABLE) {
          checkFields(it, "photo", PHOTO_TABLE_FIELDS, PHOTO_LATER)
          readTable(it["table"], model, activeMemberCount)
        } else {
          checkFields(it, "photo", PHOTO_FIELDS, PHOTO_LATER)
          readVoteBlock(it, "photo", model, maxCount = activeMemberCount)
        }
      } ?: invalid("photo is required and must be an object")

    val case =
      root["case"]?.let {
        val block = it as? JsonObject ?: invalid("case must be an object")
        checkFields(block, "case", CASE_FIELDS, CASE_LATER)
        block["onFailedPhoto"]?.let { value ->
          when ((value as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) {
            "ignore" -> Unit
            "inconclusive" -> invalid("case.onFailedPhoto inconclusive is not supported yet")
            else -> invalid("case.onFailedPhoto must be ignore or inconclusive")
          }
        }
        readVoteBlock(block, "case", model, maxCount = null)
      } ?: CombineRule.MOST_SERIOUS_PHOTO

    return CombineRule(
      id = id,
      version = version,
      modelId = modelId,
      members = members,
      photo = photo,
      case = case,
    )
  }

  private fun readMembers(element: JsonElement, model: ModelDescriptor): List<String> {
    val array = element as? JsonArray ?: invalid("members must be a list")
    val ids =
      array.map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
          ?: invalid("members must be a list of member ids")
      }
    if (ids.isEmpty()) invalid("members must name at least one member")
    if (ids.toSet().size != ids.size) invalid("members repeats a member")
    ids.firstOrNull { model.memberById(it) == null }?.let {
      invalid("members names $it, which model ${model.id} does not have")
    }
    return ids
  }

  /**
   * `photo.table`: one row per combination of member answers, each row the running members'
   * class ids in model order followed by the photo's class.
   */
  private fun readTable(element: JsonElement?, model: ModelDescriptor, memberCount: Int): ClassTable {
    val array = element as? JsonArray ?: invalid("photo.table is required and must be a list of rows")
    val rows = mutableMapOf<List<String>, String>()
    array.forEach { row ->
      val cells =
        (row as? JsonArray)?.map {
          (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: invalid("photo.table rows must be lists of class ids")
        } ?: invalid("photo.table rows must be lists of class ids")
      if (cells.size != memberCount + 1) {
        invalid("photo.table row $cells must list $memberCount member answer(s) and the result")
      }
      if (rows.put(cells.dropLast(1), cells.last()) != null) {
        invalid("photo.table repeats the answers ${cells.dropLast(1)}")
      }
    }
    val table = ClassTable(rows)
    table.problem(model.classes, memberCount)?.let { invalid("photo.$it") }
    return table
  }

  private fun readVoteBlock(
    block: JsonObject,
    name: String,
    model: ModelDescriptor,
    maxCount: Int?,
  ): VoteBlock {
    block["method"]?.let {
      when ((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) {
        "vote" -> Unit
        "mean" -> invalid("$name.method mean is not supported yet")
        METHOD_TABLE -> invalid("$name.method table is only for photo")
        else -> invalid("$name.method must be vote, table or mean")
      }
    }
    val votesNeeded =
      block["votesNeeded"]?.let { readVotesNeeded(it, name, model, maxCount) }
        ?: VotesNeeded.Same(Votes.AnyOne)
    val countMoreSevere =
      block["countMoreSevere"]?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull
          ?: invalid("$name.countMoreSevere must be true or false")
      } ?: true
    val fallback =
      block["fallback"]?.let {
        val value =
          (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: invalid("$name.fallback must be a class id")
        if (value == INCONCLUSIVE) invalid("$name.fallback inconclusive is not supported yet")
        model.classById(value)?.id ?: invalid("$name.fallback $value is not a class of ${model.id}")
      }
    return VoteBlock(votesNeeded, countMoreSevere, fallback)
  }

  private fun readVotesNeeded(
    element: JsonElement,
    name: String,
    model: ModelDescriptor,
    maxCount: Int?,
  ): VotesNeeded =
    when (element) {
      is JsonObject -> {
        if (element.isEmpty()) invalid("$name.votesNeeded must name at least one class")
        VotesNeeded.PerClass(
          element.mapValues { (classId, value) ->
            if (model.classById(classId) == null) {
              invalid("$name.votesNeeded names $classId, which is not a class of ${model.id}")
            }
            readVotes(value, "$name.votesNeeded.$classId", maxCount)
          },
        )
      }
      else -> VotesNeeded.Same(readVotes(element, "$name.votesNeeded", maxCount))
    }

  private fun readVotes(element: JsonElement, path: String, maxCount: Int?): Votes {
    val primitive = element as? JsonPrimitive ?: invalid("$path must be all, majority, any or a number")
    if (primitive.isString) {
      return when (primitive.content) {
        "all" -> Votes.All
        "majority" -> Votes.Majority
        "any" -> Votes.AnyOne
        else -> invalid("$path must be all, majority, any or a number, not ${primitive.content}")
      }
    }
    val count = primitive.intOrNull ?: invalid("$path must be all, majority, any or a whole number")
    if (count < 1) invalid("$path must be at least 1")
    if (maxCount != null && count > maxCount) {
      invalid("$path is $count but only $maxCount member(s) run")
    }
    return Votes.AtLeast(count)
  }

  private fun checkFields(obj: JsonObject, name: String, allowed: Set<String>, later: Set<String>) {
    obj.keys.firstOrNull { it in later }?.let { invalid("$name.$it is not supported yet") }
    obj.keys.firstOrNull { it !in allowed }?.let { invalid("$name has unknown field $it") }
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

  private fun invalid(reason: String): Nothing = throw InvalidRule(reason)

  private class InvalidRule(message: String) : Exception(message)

  private const val INCONCLUSIVE = "inconclusive"
  private const val METHOD_TABLE = "table"
}
