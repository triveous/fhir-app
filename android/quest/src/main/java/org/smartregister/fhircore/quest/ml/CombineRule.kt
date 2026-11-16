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

/** How many votes a class needs, out of the votes cast. */
sealed interface Votes {
  /** The number of votes needed when [total] votes were cast. */
  fun required(total: Int): Int

  /** Every vote (AND). */
  object All : Votes {
    override fun required(total: Int) = total

    override fun toString() = "all"
  }

  /** More than half. */
  object Majority : Votes {
    override fun required(total: Int) = total / 2 + 1

    override fun toString() = "majority"
  }

  /** At least one (OR). */
  object AnyOne : Votes {
    override fun required(total: Int) = 1

    override fun toString() = "any"
  }

  /** At least [count]. In a case, a count above the number of photos is simply never reached. */
  data class AtLeast(val count: Int) : Votes {
    init {
      require(count >= 1) { "votesNeeded must be at least 1" }
    }

    override fun required(total: Int) = count

    override fun toString() = count.toString()
  }
}

/** `votesNeeded`: one value for every class, or a value per class. */
sealed interface VotesNeeded {
  /** What [classId] needs, or null when it cannot win by votes (only as the fallback). */
  fun forClass(classId: String): Votes?

  data class Same(val votes: Votes) : VotesNeeded {
    override fun forClass(classId: String) = votes
  }

  /** A class left out of [byClass] can only be the fallback. */
  data class PerClass(val byClass: Map<String, Votes>) : VotesNeeded {
    override fun forClass(classId: String) = byClass[classId]
  }
}

/**
 * One `photo` or `case` block of a rule (method `vote`, the only method this release supports).
 *
 * @property fallback the class when none gets enough votes; null means the least serious class.
 */
data class VoteBlock(
  val votesNeeded: VotesNeeded = VotesNeeded.Same(Votes.AnyOne),
  val countMoreSevere: Boolean = true,
  val fallback: String? = null,
)

/** Whether a rule came with the app or from the site's feature flags. */
enum class RuleSource(val telemetryName: String) {
  SHIPPED("shipped"),
  REMOTE("remote"),
}

/**
 * How a model's member answers become a photo's class ([photo]) and how photo classes become the
 * case class ([case]). Ships with each model as its default and may be overridden per site through
 * `ai-model.rule` (see [CombineRuleParser] for the JSON form and its checks).
 *
 * @property members the members to run; null runs every member the model ships.
 */
data class CombineRule(
  val id: String,
  val version: Int,
  val modelId: String,
  val members: List<String>? = null,
  val photo: VoteBlock,
  val case: VoteBlock = MOST_SERIOUS_PHOTO,
) {
  companion object {
    /** The only rule format this app understands. */
    const val SCHEMA = 1

    /** Default `case` block: the most serious photo decides the case (today's rule). */
    val MOST_SERIOUS_PHOTO = VoteBlock(VotesNeeded.Same(Votes.AnyOne), countMoreSevere = true)
  }
}

/**
 * Applies a [VoteBlock] to a set of votes. Classes are checked from most serious to least, and the
 * first class that gets the votes it needs wins. With `countMoreSevere`, a vote for a more serious
 * class also counts toward a less serious one ("at least low-risk"). If no class gets enough votes,
 * or no vote was cast, the result is the block's fallback.
 */
object CombineRuleEvaluator {

  fun decide(classes: List<AiClass>, votes: List<String>, block: VoteBlock): AiClass {
    val severityOf = classes.associate { it.id to it.severity }
    val total = votes.size
    if (total > 0) {
      for (candidate in classes.sortedByDescending { it.severity }) {
        val needed = block.votesNeeded.forClass(candidate.id)?.required(total) ?: continue
        val count =
          votes.count { vote ->
            if (block.countMoreSevere) {
              (severityOf[vote] ?: Int.MIN_VALUE) >= candidate.severity
            } else {
              vote == candidate.id
            }
          }
        if (count >= needed) return candidate
      }
    }
    return block.fallback?.let { id -> classes.firstOrNull { it.id == id } }
      ?: classes.minBy { it.severity }
  }
}
