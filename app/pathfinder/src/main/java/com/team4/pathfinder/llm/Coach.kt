package com.team4.pathfinder.llm

import com.team4.pathfinder.engine.CheckinSummary
import com.team4.pathfinder.engine.Engine
import com.team4.pathfinder.engine.Profile
import com.team4.pathfinder.engine.SkillGraph
import com.team4.pathfinder.engine.Step
import org.json.JSONObject

/** Where a piece of text came from, shown to the learner. */
enum class Source { LLAMA, RULES }

data class CoachText(val text: String, val source: Source, val reply: Reply? = null, val note: String? = null)

data class GoalReading(val profile: Profile, val source: Source, val reply: Reply? = null, val note: String? = null)

/**
 * The language half of the app (same prompts as website/js/llm.js). Every call validates Llama's
 * answer and falls back to the engine's rule-based text when the model is not loaded or misbehaves.
 */
object Coach {
    private const val SYSTEM = "You are Pathfinder, a friendly study coach inside a learning app. Talk to the learner as \"you\" and never about yourself. " +
        "No greeting, heading or preamble: start with the first sentence. Be specific and brief. Never invent links, courses or numbers that are not given to you."

    /** Small models sometimes open with "Here's your check-in:". Drop such a first line. */
    fun tidy(s: String): String = s.replace(Regex("^\\s*(?:here(?:'s| is)[^\\n]*:|[^\\n]{0,60}:)\\s*\\n+", RegexOption.IGNORE_CASE), "").trim()

    /**
     * Free-text goal to a profile. Deadline and hours stated in the text come from the rules; Llama fills in what the rules
     * did not find and adds known skills, each validated, and only skills the goal actually names.
     */
    suspend fun readGoal(llm: LlmEngine?, text: String, graph: SkillGraph): GoalReading {
        val ruleReading = Engine.readGoalRules(text, graph)
        val rules = ruleReading.profile
        if (llm == null || !llm.isLoaded) return GoalReading(rules, Source.RULES)
        return try {
            val reply = llm.chat(
                listOf(
                    Turn("system", "Extract facts from a learner's goal. Reply with JSON only: {\"weeks\": integer or null, \"hoursPerWeek\": number or null, \"knownSkills\": [skill names from the list that the learner says they already know]}. Use null when the text does not say."),
                    Turn("user", "Skills: Spreadsheets, SQL basics, Python basics\nGoal: I use Excel at work and want to switch careers within a year, maybe 5 hours a week"),
                    Turn("assistant", "{\"weeks\": 52, \"hoursPerWeek\": 5, \"knownSkills\": [\"Spreadsheets\"]}"),
                    Turn("user", "Skills: ${graph.skills.joinToString(", ") { it.name }}\nGoal: $text"),
                ),
                maxTokens = 80,
            )
            val json = Regex("\\{[\\s\\S]*?\\}").find(reply.text)?.value ?: error("no JSON in reply")
            val j = JSONObject(json)
            val weeks = if (ruleReading.foundWeeks) rules.weeks else (j.opt("weeks") as? Number)?.toDouble()?.takeIf { it == Math.floor(it) && it in 1.0..520.0 }?.toInt() ?: rules.weeks
            val hours = if (ruleReading.foundHours) rules.hoursPerWeek else (j.opt("hoursPerWeek") as? Number)?.toDouble()?.takeIf { it > 0 && it <= 80 }?.let { Engine.round1(it) } ?: rules.hoursPerWeek
            // A 1B model sometimes copies the whole skill list back. Only accept skills the goal actually mentions.
            val mentioned = Engine.mentionedSkillIds(text, graph).toSet()
            val claimed = j.optJSONArray("knownSkills")?.let { a -> List(a.length()) { a.optString(it).lowercase() } } ?: emptyList()
            val known = graph.skills.filter { it.id in mentioned && it.name.lowercase() in claimed }.map { it.id }
            GoalReading(Profile(weeks, hours, (known + rules.knownSkillIds).distinct()), Source.LLAMA, reply)
        } catch (e: Exception) {
            GoalReading(rules, Source.RULES, note = e.message)
        }
    }

    /** Why the pathway is in this order, streamed. */
    suspend fun explainPathway(llm: LlmEngine?, steps: List<Step>, profile: Profile, goalText: String, onText: (String) -> Unit = {}): CoachText {
        val fallback = Engine.explainPathwayRules(steps, profile)
        if (llm == null || !llm.isLoaded || steps.isEmpty()) return CoachText(fallback, Source.RULES)
        val facts = steps.mapIndexed { i, s ->
            "${i + 1}. ${s.name}: current level ${Math.round(s.mastery * 100)}%, target ${Math.round(s.target * 100)}%, ${Engine.fmt(s.hours)} h" +
                if (s.openPrereqs.isNotEmpty()) ", needs ${s.openPrereqs.joinToString(" and ")} first" else ""
        }.joinToString("\n")
        return try {
            val reply = llm.chat(
                listOf(
                    Turn("system", SYSTEM),
                    Turn("user", "Learner goal: $goalText\nThey can study ${Engine.fmt(profile.hoursPerWeek)} hours a week for ${profile.weeks} weeks.\n" +
                        "Their pathway, already in the right order:\n$facts\n\nIn 3 short sentences, explain why the first two steps come first and what the learner gains by the end. Address them as \"you\"."),
                ),
                maxTokens = 160,
                onText = { onText(tidy(it)) },
            )
            val text = tidy(reply.text)
            if (text.isBlank()) CoachText(fallback, Source.RULES, reply, "empty reply") else CoachText(text, Source.LLAMA, reply)
        } catch (e: Exception) {
            CoachText(fallback, Source.RULES, note = e.message)
        }
    }

    /** Weekly reflection from the check-in facts, streamed. */
    suspend fun reflect(llm: LlmEngine?, s: CheckinSummary, onText: (String) -> Unit = {}): CoachText {
        val fallback = Engine.reflectionRules(s)
        if (llm == null || !llm.isLoaded) return CoachText(fallback, Source.RULES)
        val facts = listOfNotNull(
            "Week ${s.week}: studied ${Engine.fmt(s.doneHours)} of ${Engine.fmt(s.plannedHours)} planned hours.",
            if (s.completedSkills.isNotEmpty()) "Finished: ${s.completedSkills.joinToString(", ")}." else "No skill finished this week.",
            s.hardSkills.takeIf { it.isNotEmpty() }?.let { "Rated hard: ${it.joinToString(", ")} (extra time was added)." },
            s.easySkills.takeIf { it.isNotEmpty() }?.let { "Rated easy: ${it.joinToString(", ")}." },
            "Next week: ${s.nextFocus.joinToString(", ").ifEmpty { "reviews only" }}.",
            if (s.onTrack) "On track for the deadline." else "Behind: finishing in week ${s.finishWeek}, deadline is week ${s.deadlineWeek}.",
        ).joinToString("\n")
        return try {
            val reply = llm.chat(
                listOf(
                    Turn("system", SYSTEM),
                    Turn("user", "The learner's week:\n$facts\n\nWrite their weekly check-in in exactly 3 short sentences, addressed to them as \"you\": how the week went, what changed in their plan, one concrete tip for next week. Use only these facts."),
                ),
                maxTokens = 150,
                onText = { onText(tidy(it)) },
            )
            val text = tidy(reply.text)
            if (text.isBlank()) CoachText(fallback, Source.RULES, reply, "empty reply") else CoachText(text, Source.LLAMA, reply)
        } catch (e: Exception) {
            CoachText(fallback, Source.RULES, note = e.message)
        }
    }
}
