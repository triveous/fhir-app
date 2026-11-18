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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.smartregister.fhircore.quest.ml.ModelCatalog.HIGH_RISK_SUSPICIOUS as HIGH
import org.smartregister.fhircore.quest.ml.ModelCatalog.LOW_RISK_SUSPICIOUS as LOW
import org.smartregister.fhircore.quest.ml.ModelCatalog.NON_SUSPICIOUS as NON
import org.smartregister.fhircore.quest.ml.ModelCatalog.SUSPICIOUS

class CombineRuleEvaluatorTest {

  private val threeClasses = ModelCatalog.THREE_CLASS.classes
  private val binaryClasses = ModelCatalog.BINARY.classes

  /** The plan's worked example: the members say high-risk, low-risk, non-suspicious. */
  private val workedExample = listOf(HIGH, LOW, NON)

  private fun decide(votes: List<String>, block: VoteBlock, classes: List<AiClass> = threeClasses) =
    CombineRuleEvaluator.decide(classes, votes, block).id

  private fun perClass(vararg pairs: Pair<String, Votes>, fallback: String? = null) =
    VoteBlock(VotesNeeded.PerClass(mapOf(*pairs)), countMoreSevere = true, fallback = fallback)

  @Test
  fun `worked example - all for every class falls back to non-suspicious`() {
    assertEquals(NON, decide(workedExample, VoteBlock(VotesNeeded.Same(Votes.All))))
  }

  @Test
  fun `worked example - majority for every class is low-risk`() {
    assertEquals(LOW, decide(workedExample, VoteBlock(VotesNeeded.Same(Votes.Majority))))
  }

  @Test
  fun `worked example - high all and low majority is low-risk`() {
    assertEquals(LOW, decide(workedExample, perClass(HIGH to Votes.All, LOW to Votes.Majority, fallback = NON)))
  }

  /** The ML team's table (docs/features/ai-model/combine-table-m1-m2.png): M1, M2, Comb_result. */
  @Test
  fun `the shipped 3-class rule is the ML team's table for two members`() {
    val teamTable =
      listOf(
        Triple(0, 0, 0),
        Triple(0, 1, 0),
        Triple(0, 2, 1),
        Triple(1, 0, 0),
        Triple(1, 1, 1),
        Triple(1, 2, 2),
        Triple(2, 0, 1),
        Triple(2, 1, 2),
        Triple(2, 2, 2),
      )
    val classOf = listOf(NON, LOW, HIGH)
    val rule = ModelCatalog.THREE_CLASS.defaultRule.photo

    assertEquals(null, (rule as ClassTable).problem(threeClasses, memberCount = 2))
    teamTable.forEach { (m1, m2, expected) ->
      assertEquals(
        "M1=$m1 M2=$m2",
        classOf[expected],
        CombineRuleEvaluator.decidePhoto(threeClasses, listOf(classOf[m1], classOf[m2]), rule).id,
      )
    }
  }

  @Test
  fun `with one member running, a table rule gives that member's answer`() {
    val rule = ModelCatalog.THREE_CLASS.defaultRule.photo

    listOf(NON, LOW, HIGH).forEach {
      assertEquals(it, CombineRuleEvaluator.decidePhoto(threeClasses, listOf(it), rule).id)
    }
  }

  @Test
  fun `a table without a row for the answers fails instead of guessing`() {
    val rule = ModelCatalog.THREE_CLASS.defaultRule.photo

    assertThrows(IllegalStateException::class.java) {
      CombineRuleEvaluator.decidePhoto(threeClasses, listOf(NON, LOW, HIGH), rule)
    }
  }

  @Test
  fun `worked example - any for every class is high-risk`() {
    assertEquals(HIGH, decide(workedExample, VoteBlock(VotesNeeded.Same(Votes.AnyOne))))
  }

  @Test
  fun `without countMoreSevere a high-risk vote does not count toward low-risk`() {
    val block =
      VoteBlock(VotesNeeded.PerClass(mapOf(LOW to Votes.AtLeast(2))), countMoreSevere = false, fallback = NON)

    assertEquals(NON, decide(workedExample, block))
    assertEquals(LOW, decide(listOf(LOW, LOW, HIGH), block))
  }

  @Test
  fun `a number means at least that many votes`() {
    val twoOfThree = perClass(HIGH to Votes.AtLeast(2), fallback = NON)

    assertEquals(NON, decide(workedExample, twoOfThree))
    assertEquals(HIGH, decide(listOf(HIGH, HIGH, NON), twoOfThree))
  }

  @Test
  fun `majority is more than half for odd and even counts`() {
    assertEquals(2, Votes.Majority.required(3))
    assertEquals(3, Votes.Majority.required(4))
    assertEquals(3, Votes.Majority.required(5))
    assertEquals(1, Votes.Majority.required(1))
  }

  @Test
  fun `a class left out of a per-class map can only be the fallback`() {
    // Only high-risk can win by votes; three low-risk votes still fall back.
    assertEquals(NON, decide(listOf(LOW, LOW, LOW), perClass(HIGH to Votes.AnyOne, fallback = NON)))
  }

  @Test
  fun `the default fallback is the least serious class`() {
    assertEquals(NON, decide(workedExample, perClass(HIGH to Votes.All)))
  }

  @Test
  fun `no votes gives the fallback, never the most serious class`() {
    assertEquals(NON, decide(emptyList(), VoteBlock(VotesNeeded.Same(Votes.All))))
    assertEquals(LOW, decide(emptyList(), VoteBlock(VotesNeeded.Same(Votes.All), fallback = LOW)))
  }

  @Test
  fun `the shipped 2-class photo rule is all three must agree`() {
    val rule = ModelCatalog.BINARY.defaultRule.photo as VoteBlock

    assertEquals(SUSPICIOUS, decide(listOf(SUSPICIOUS, SUSPICIOUS, SUSPICIOUS), rule, binaryClasses))
    assertEquals(NON, decide(listOf(SUSPICIOUS, SUSPICIOUS, NON), rule, binaryClasses))
    assertEquals(NON, decide(listOf(NON, NON, NON), rule, binaryClasses))
  }

  @Test
  fun `the default case rule - the most serious photo decides`() {
    val case = CombineRule.MOST_SERIOUS_PHOTO

    assertEquals(LOW, decide(listOf(NON, LOW, NON), case))
    assertEquals(HIGH, decide(listOf(LOW, HIGH, NON), case))
    assertEquals(NON, decide(listOf(NON, NON), case))
    assertEquals(SUSPICIOUS, decide(listOf(NON, SUSPICIOUS), case, binaryClasses))
    assertEquals(NON, decide(emptyList(), case, binaryClasses))
  }

  @Test
  fun `a case rule can need low-risk on two photos`() {
    val case = perClass(HIGH to Votes.AnyOne, LOW to Votes.AtLeast(2), fallback = NON)

    assertEquals(NON, decide(listOf(NON, LOW, NON), case))
    assertEquals(LOW, decide(listOf(LOW, LOW, NON), case))
    // A number larger than the photos taken is simply never reached.
    assertEquals(NON, decide(listOf(LOW), case))
  }
}
