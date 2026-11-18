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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CombineRuleParserTest {

  /** The shipped 3-class model has two members; the vote checks are exercised with three. */
  private val model =
    ModelCatalog.THREE_CLASS.copy(
      members = listOf("m1", "m2", "m3").map { ModelMember(id = it, resultKeyPrefix = it) },
      onnxAssets = emptyList(),
    )

  private val shipped = ModelCatalog.THREE_CLASS

  private val tableRule =
    """
    {
      "schema": 1,
      "id": "site/table",
      "version": 1,
      "modelId": "oral-3class-v1",
      "photo": {
        "method": "table",
        "table": [
          ["non-suspicious", "non-suspicious", "non-suspicious"],
          ["non-suspicious", "low-risk-suspicious", "non-suspicious"],
          ["non-suspicious", "high-risk-suspicious", "low-risk-suspicious"],
          ["low-risk-suspicious", "non-suspicious", "non-suspicious"],
          ["low-risk-suspicious", "low-risk-suspicious", "low-risk-suspicious"],
          ["low-risk-suspicious", "high-risk-suspicious", "high-risk-suspicious"],
          ["high-risk-suspicious", "non-suspicious", "low-risk-suspicious"],
          ["high-risk-suspicious", "low-risk-suspicious", "high-risk-suspicious"],
          ["high-risk-suspicious", "high-risk-suspicious", "high-risk-suspicious"]
        ]
      }
    }
    """

  private val sample =
    """
    {
      "schema": 1,
      "id": "oral-3class-v1/majority",
      "version": 1,
      "modelId": "oral-3class-v1",
      "members": ["m1", "m2", "m3"],
      "photo": {
        "method": "vote",
        "countMoreSevere": true,
        "votesNeeded": { "high-risk-suspicious": "all", "low-risk-suspicious": "majority" },
        "fallback": "non-suspicious"
      },
      "case": { "method": "vote", "countMoreSevere": true, "votesNeeded": "any", "fallback": "non-suspicious" }
    }
    """

  private fun accepted(json: String): CombineRule {
    val result = CombineRuleParser.parse(json, model)
    assertTrue("expected accepted, got $result", result is RuleParseResult.Accepted)
    return (result as RuleParseResult.Accepted).rule
  }

  private fun rejectedReason(json: String, forModel: ModelDescriptor = model): String {
    val result = CombineRuleParser.parse(json, forModel)
    assertTrue("expected rejected, got $result", result is RuleParseResult.Rejected)
    return (result as RuleParseResult.Rejected).reason
  }

  /** [sample] with one top-level or photo field changed. */
  private fun withTop(key: String, value: String) =
    sample.replaceFirst(Regex("\"$key\": [^,\\n]+"), "\"$key\": $value")

  private fun withPhoto(extraFields: String) =
    sample.replace("\"method\": \"vote\",\n        \"countMoreSevere\"", "$extraFields\n        \"countMoreSevere\"")

  @Test
  fun `the documented sample rule is accepted and read exactly`() {
    val rule = accepted(sample)

    assertEquals("oral-3class-v1/majority", rule.id)
    assertEquals(1, rule.version)
    assertEquals(listOf("m1", "m2", "m3"), rule.members)
    assertEquals(
      VotesNeeded.PerClass(
        mapOf(
          ModelCatalog.HIGH_RISK_SUSPICIOUS to Votes.All,
          ModelCatalog.LOW_RISK_SUSPICIOUS to Votes.Majority,
        ),
      ),
      (rule.photo as VoteBlock).votesNeeded,
    )
    assertEquals(ModelCatalog.NON_SUSPICIOUS, (rule.photo as VoteBlock).fallback)
    assertEquals(VotesNeeded.Same(Votes.AnyOne), rule.case.votesNeeded)
    // High-risk needs all 3 (has 1); at-least-low-risk needs a majority (has 2).
    assertEquals(
      ModelCatalog.LOW_RISK_SUSPICIOUS,
      CombineRuleEvaluator.decide(
        model.classes,
        listOf(ModelCatalog.HIGH_RISK_SUSPICIOUS, ModelCatalog.LOW_RISK_SUSPICIOUS, ModelCatalog.NON_SUSPICIOUS),
        rule.photo as VoteBlock,
      ).id,
    )
  }

  @Test
  fun `the repo's sample files parse`() {
    // docs/features/ai-model holds the samples the server team copies; keep them valid.
    val dir = listOf(File("../docs/features/ai-model"), File("docs/features/ai-model")).firstOrNull { it.isDirectory }
    assumeTrue("sample folder not found from ${File(".").absolutePath}", dir != null)
    val result = CombineRuleParser.parse(File(dir, "combine-rule.sample.json").readText(), shipped)
    assertTrue("expected accepted, got $result", result is RuleParseResult.Accepted)
    // The sample is the rule the app ships, so the docs and the app can't drift apart.
    assertEquals(shipped.defaultRule.photo, (result as RuleParseResult.Accepted).rule.photo)
  }

  @Test
  fun `a table rule is read as the table it lists`() {
    val result = CombineRuleParser.parse(tableRule, shipped)

    assertTrue("expected accepted, got $result", result is RuleParseResult.Accepted)
    assertEquals(shipped.defaultRule.photo, (result as RuleParseResult.Accepted).rule.photo)
  }

  @Test
  fun `a table that is incomplete, repeated, mis-sized or misnamed is rejected`() {
    val firstRow = """["non-suspicious", "non-suspicious", "non-suspicious"],"""
    val cases =
      mapOf(
        tableRule.replace(firstRow, "") to "8 row(s) but there are 9",
        tableRule.replace(firstRow, firstRow + firstRow) to "repeats",
        tableRule.replace(firstRow, """["non-suspicious", "non-suspicious"],""") to "2 member answer(s) and the result",
        tableRule.replace(firstRow, """["non-suspicious", "non-suspicious", "benign"],""") to "benign",
        tableRule.replace(firstRow, """["non-suspicious", 0, "non-suspicious"],""") to "class ids",
        tableRule.replace("\"table\": [", "\"fallback\": \"non-suspicious\", \"table\": [") to "unknown field fallback",
        tableRule.replace("\"photo\": {", "\"members\": [\"m1\"], \"photo\": {") to "1 member answer(s)",
        """{"schema":1,"id":"r","version":1,"modelId":"oral-3class-v1","photo":{"method":"table"}}""" to
          "photo.table is required",
        """{"schema":1,"id":"r","version":1,"modelId":"oral-3class-v1","photo":{"votesNeeded":"any"},"case":{"method":"table"}}""" to
          "only for photo",
      )

    cases.forEach { (json, expected) ->
      val reason = rejectedReason(json, shipped)
      assertTrue("for $json: '$reason' should mention '$expected'", reason.contains(expected))
    }
  }

  @Test
  fun `optional fields fall back to their defaults`() {
    val rule =
      accepted(
        """{"schema":1,"id":"r","version":2,"modelId":"oral-3class-v1","photo":{"votesNeeded":2}}""",
      )

    assertEquals(null, rule.members)
    val photo = rule.photo as VoteBlock
    assertEquals(VotesNeeded.Same(Votes.AtLeast(2)), photo.votesNeeded)
    assertTrue(photo.countMoreSevere)
    assertEquals(null, photo.fallback)
    assertEquals(CombineRule.MOST_SERIOUS_PHOTO, rule.case)
  }

  @Test
  fun `a member subset is accepted and numbers are checked against it`() {
    accepted(sample.replace("\"members\": [\"m1\", \"m2\", \"m3\"]", "\"members\": [\"m1\", \"m3\"]"))

    val reason =
      rejectedReason(
        """{"schema":1,"id":"r","version":1,"modelId":"oral-3class-v1","members":["m1","m3"],"photo":{"votesNeeded":3}}""",
      )
    assertTrue(reason, reason.contains("only 2 member"))
  }

  @Test
  fun `every check rejects with a reason`() {
    val cases =
      mapOf(
        "not json" to "rule is not a JSON object",
        "[1,2]" to "rule is not a JSON object",
        withTop("schema", "2") to "newer than this app",
        withTop("schema", "\"1\"") to "schema is required",
        withTop("version", "0") to "version",
        withTop("modelId", "\"oral-binary-v1\"") to "does not match",
        sample.replace("\"m3\"]", "\"m9\"]") to "m9",
        sample.replace("\"m3\"]", "\"m1\"]") to "repeats",
        sample.replace("\"low-risk-suspicious\": \"majority\"", "\"low-risk-suspicious\": \"most\"") to "most",
        sample.replace("\"low-risk-suspicious\": \"majority\"", "\"mid-risk\": \"majority\"") to "mid-risk",
        sample.replace("\"low-risk-suspicious\": \"majority\"", "\"low-risk-suspicious\": 0") to "at least 1",
        sample.replace("\"low-risk-suspicious\": \"majority\"", "\"low-risk-suspicious\": 1.5") to "whole number",
        sample.replace("\"countMoreSevere\": true,\n        \"votesNeeded\"", "\"countMoreSevere\": \"yes\",\n        \"votesNeeded\"") to "true or false",
        sample.replaceFirst("\"fallback\": \"non-suspicious\"", "\"fallback\": \"maybe\"") to "maybe",
        sample.replaceFirst("\"fallback\": \"non-suspicious\"", "\"fallback\": \"inconclusive\"") to "not supported yet",
        sample.replace("\"case\": { \"method\": \"vote\"", "\"case\": { \"method\": \"mean\"") to "mean is not supported",
        sample.replace("\"case\": {", "\"case\": { \"onFailedPhoto\": \"inconclusive\",") to "not supported yet",
        sample.replace("\"case\": {", "\"case\": { \"minPhotos\": 2,") to "minPhotos is not supported yet",
        sample.replace("\"schema\": 1,", "\"schema\": 1, \"lowConfidence\": {\"below\": 60},") to "lowConfidence",
        sample.replace("\"schema\": 1,", "\"schema\": 1, \"quorum\": 1,") to "unknown field quorum",
        withPhoto("\"method\": \"mean\", \"weights\": {\"m1\": 1},") to "weights is not supported yet",
        withPhoto("\"method\": \"teleport\",") to "vote, table or mean",
        """{"schema":1,"id":"r","version":1,"modelId":"oral-3class-v1"}""" to "photo is required",
        """{"schema":1,"id":"r","version":1,"modelId":"oral-3class-v1","photo":{"votesNeeded":{}}}""" to
          "at least one class",
      )

    cases.forEach { (json, expected) ->
      val reason = rejectedReason(json)
      assertTrue("for $json: '$reason' should mention '$expected'", reason.contains(expected))
    }
  }

  @Test
  fun `a rejected rule still reports its id when it has one`() {
    val result = CombineRuleParser.parse(withTop("schema", "2"), model) as RuleParseResult.Rejected

    assertEquals("oral-3class-v1/majority", result.ruleId)
  }

  @Test
  fun `a 2-class rule is checked against the 2-class model`() {
    val binaryMajority =
      """{"schema":1,"id":"b","version":1,"modelId":"oral-binary-v1","photo":{"votesNeeded":{"suspicious":"majority"},"fallback":"non-suspicious"}}"""

    val result = CombineRuleParser.parse(binaryMajority, ModelCatalog.BINARY)

    assertTrue(result is RuleParseResult.Accepted)
    assertTrue(rejectedReason(binaryMajority).contains("does not match"))
  }
}
