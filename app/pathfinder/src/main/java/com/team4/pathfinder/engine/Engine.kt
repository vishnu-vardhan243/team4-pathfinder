package com.team4.pathfinder.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Pathfinder learning engine: the deterministic half of the app (port of website/js/engine.js).
// Llama writes words; this does every number: skill levels, gaps, order and schedule.

data class Profile(val weeks: Int, val hoursPerWeek: Double, val knownSkillIds: List<String>)

/** Rule-based goal reading plus which values the text actually stated (the rest are defaults: 26 weeks, 6 h a week). */
data class RuleReading(val profile: Profile, val foundWeeks: Boolean, val foundHours: Boolean)

sealed class QuizItem {
    abstract val skillId: String
    abstract val skill: String
    data class Mcq(override val skillId: String, override val skill: String, val q: String, val options: List<String>, val answer: Int) : QuizItem()
    data class Self(override val skillId: String, override val skill: String, val preset: String) : QuizItem()
}

sealed class Answer {
    data class Choice(val index: Int) : Answer()
    data class Level(val level: String) : Answer() // "none" | "some" | "confident"
}

data class Step(
    val skillId: String, val name: String, val summary: String, val mastery: Double, val target: Double,
    val gap: Double, val hours: Double, val resources: List<Resource>, val openPrereqs: List<String>,
)

data class StepHours(val skillId: String, val name: String, val hours: Double)

enum class Kind { LEARN, REVIEW }

data class Session(val id: String, val week: Int, val skillId: String, val name: String, val hours: Double, val kind: Kind)

data class Week(val week: Int, val sessions: List<Session>, val hours: Double)

data class Schedule(
    val weeks: List<Week>, val learnHours: Double, val finishWeek: Int, val deadlineWeek: Int,
    val onTrack: Boolean, val hoursPerWeekNeeded: Double,
)

data class LearnerState(
    val domainId: String,
    val profile: Profile,
    val startMastery: Map<String, Double>,
    val mastery: Map<String, Double>,
    val needed: Map<String, Double>,
    val remaining: Map<String, Double>,
    val finished: Map<String, Int>,
    val week: Int,
)

data class SessionResult(val session: Session, val done: Boolean, val rating: String = "ok")

data class CheckinSummary(
    val week: Int, val plannedHours: Double, val doneHours: Double, val completedSkills: List<String>,
    val hardSkills: List<String>, val easySkills: List<String>, val skipped: Int,
    val onTrack: Boolean, val finishWeek: Int, val deadlineWeek: Int, val nextFocus: List<String>,
)

object Engine {
    val SELF_LEVEL = mapOf("none" to 0.0, "some" to 0.4, "confident" to 0.75)
    private const val MCQ_RIGHT = 0.8
    private const val MCQ_WRONG = 0.25
    private const val SESSION_MAX_H = 1.5
    private const val REVIEW_H = 0.5
    private val RATING_ADJUST = mapOf("easy" to -0.15, "ok" to 0.0, "hard" to 0.25)

    fun round1(x: Double): Double = Math.round(x * 10) / 10.0
    private fun clamp01(x: Double) = max(0.0, min(1.0, x))

    private val SKILL_WORDS = mapOf(
        "spreadsheets" to listOf("excel", "spreadsheet", "spreadsheets", "google sheets"),
        "descriptive-stats" to listOf("statistics", "stats"),
        "sql-basics" to listOf("sql"),
        "python-basics" to listOf("python"),
        "pandas" to listOf("pandas"),
        "visualization" to listOf("visualization", "visualisation", "charts", "matplotlib"),
        "bi-dashboards" to listOf("power bi", "tableau", "dashboards", "dashboard"),
        "intro-ml" to listOf("machine learning"),
    )
    private val KNOW_CUES = Regex("\\b(know|knows|knew|familiar|experience|experienced|used|use|comfortable|good at|done|basic|basics|some)\\b")
    private val WORD_NUM = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "half a" to 0.5, "half" to 0.5,
    )
    private const val NUM = "(\\d+(?:\\.\\d+)?|half an?|half|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private fun num(s: String): Double = s.toDoubleOrNull() ?: WORD_NUM[s.replace(Regex("half an?"), "half a")] ?: WORD_NUM[s] ?: 1.0

    /** Best-matching domain for a free-text goal, or null. */
    fun matchDomain(goalText: String, graphs: List<SkillGraph>): SkillGraph? {
        val text = goalText.lowercase()
        var best: SkillGraph? = null
        var bestScore = 0
        for (g in graphs) {
            val score = g.goalKeywords.sumOf { k -> if (text.contains(k.lowercase())) k.split(" ").size else 0 }
            if (score > bestScore) { best = g; bestScore = score }
        }
        return best
    }

    /** Skills the goal text names at all (keyword or full name). Keeps Llama's answer grounded. */
    fun mentionedSkillIds(text: String, graph: SkillGraph): List<String> {
        val t = text.lowercase()
        return graph.skills.filter { s -> (SKILL_WORDS[s.id] ?: emptyList()).any { t.contains(it) } || t.contains(s.name.lowercase()) }.map { it.id }
    }

    /** Rule-based goal reading: deadline in weeks, hours per week, skills the learner says they know. */
    fun parseGoalRules(text: String, graph: SkillGraph): Profile = readGoalRules(text, graph).profile

    /** Llama only fills in values the rules did not find: the evaluation (eval/results.md) showed the 1B model often turns "6 months" into 6 weeks. */
    fun readGoalRules(text: String, graph: SkillGraph): RuleReading {
        val t = text.lowercase()
        // Rates like "10 hours a week" are not deadlines: drop them before looking for one.
        val d = t.replace(Regex("\\b(h|hrs?|hours?|mins?|minutes?|times?|sessions?)\\s+(?:a|an)\\s+(?:week|day|month|year)\\b"), "$1")
            .replace(Regex("\\b(?:per|each|every)\\s+(?:week|day|month|year)\\b"), " ")
        var weeks = 26
        val mo = Regex("\\b$NUM\\s*(months?|mos?)\\b").find(d)
        val wk = Regex("\\b$NUM\\s*(weeks?|wks?)\\b").find(d)
        val yr = Regex("\\b$NUM\\s*(years?|yrs?)\\b").find(d)
        when {
            mo != null -> weeks = Math.round(num(mo.groupValues[1]) * 4.345).toInt()
            wk != null -> weeks = Math.round(num(wk.groupValues[1])).toInt()
            yr != null -> weeks = Math.round(num(yr.groupValues[1]) * 52).toInt()
        }
        var hoursPerWeek = 6.0
        val hw = Regex("\\b$NUM\\s*(?:h|hrs?|hours?)\\s*(?:a|per|each|/)\\s*week").find(t)
        val hd = Regex("\\b$NUM\\s*(?:h|hrs?|hours?)\\s*(?:a|per|each|/)\\s*day").find(t)
        if (hw != null) hoursPerWeek = num(hw.groupValues[1]) else if (hd != null) hoursPerWeek = num(hd.groupValues[1]) * 7

        val clauses = t.split(Regex("[.,;!?]|\\bbut\\b|\\band (?:i )?want\\b"))
        val ids = graph.skills.map { it.id }.toSet()
        val known = SKILL_WORDS.filter { (id, words) -> id in ids && clauses.any { c -> KNOW_CUES.containsMatchIn(c) && words.any { c.contains(it) } } }.keys.toList()
        return RuleReading(Profile(max(1, weeks), max(1.0, round1(hoursPerWeek)), known), foundWeeks = mo != null || wk != null || yr != null, foundHours = hw != null || hd != null)
    }

    /** Diagnostic: a question for skills that have one, a self-rating for the rest. */
    fun buildQuiz(graph: SkillGraph, profile: Profile): List<QuizItem> {
        val known = profile.knownSkillIds.toSet()
        return graph.skills.map { s ->
            val q = s.quiz.firstOrNull()
            if (q != null) QuizItem.Mcq(s.id, s.name, q.q, q.options, q.answer)
            else QuizItem.Self(s.id, s.name, if (s.id in known) "some" else "none")
        }
    }

    /** Quiz answers to a 0-1 mastery level per skill. Unanswered self-ratings use their preset. */
    fun scoreQuiz(graph: SkillGraph, items: List<QuizItem>, answers: Map<String, Answer>, profile: Profile): Map<String, Double> {
        val known = profile.knownSkillIds.toSet()
        val mastery = mutableMapOf<String, Double>()
        for (it in items) {
            val a = answers[it.skillId]
            mastery[it.skillId] = when (it) {
                is QuizItem.Mcq -> {
                    val m = if (a is Answer.Choice && a.index == it.answer) MCQ_RIGHT else MCQ_WRONG
                    if (it.skillId in known) max(m, SELF_LEVEL.getValue("some")) else m
                }
                is QuizItem.Self -> SELF_LEVEL[(a as? Answer.Level)?.level ?: it.preset] ?: 0.0
            }
        }
        for (s in graph.skills) mastery.putIfAbsent(s.id, 0.0)
        return mastery
    }

    /** Prerequisite order of the whole graph (Kahn's algorithm, ties keep file order). */
    fun topoOrder(graph: SkillGraph): List<String> {
        val index = graph.skills.withIndex().associate { it.value.id to it.index }
        val indeg = graph.skills.associate { it.id to it.prereqs.size }.toMutableMap()
        val children = graph.skills.associate { it.id to mutableListOf<String>() }
        for (s in graph.skills) for (p in s.prereqs) children.getValue(p).add(s.id)
        val ready = graph.skills.filter { it.prereqs.isEmpty() }.map { it.id }.toMutableList()
        val out = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            ready.sortBy { index.getValue(it) }
            val id = ready.removeAt(0)
            out.add(id)
            for (c in children.getValue(id)) { indeg[c] = indeg.getValue(c) - 1; if (indeg[c] == 0) ready.add(c) }
        }
        check(out.size == graph.skills.size) { "Skill graph has a prerequisite cycle" }
        return out
    }

    /** Skills below their target, in prerequisite order, with the study hours each still needs. */
    fun buildPathway(graph: SkillGraph, mastery: Map<String, Double>): List<Step> = topoOrder(graph).mapNotNull { id ->
        val s = graph.byId.getValue(id)
        val m = clamp01(mastery[id] ?: 0.0)
        val gap = s.targetLevel - m
        if (gap <= 0.05) null else Step(
            skillId = id, name = s.name, summary = s.summary, mastery = m, target = s.targetLevel,
            gap = Math.round(gap * 100) / 100.0, hours = max(0.5, round1(s.estHours * gap / s.targetLevel)), resources = s.resources,
            openPrereqs = s.prereqs.filter { p -> graph.byId.getValue(p).targetLevel - (mastery[p] ?: 0.0) > 0.05 }.map { graph.byId.getValue(it).name },
        )
    }

    private class Left(val step: StepHours, var left: Double)

    /** Lay remaining study hours into weekly sessions, with spaced reviews 1 and 3 weeks after a skill finishes. */
    fun buildSchedule(
        steps: List<StepHours>, hoursPerWeek: Double, weeks: Int, startWeek: Int = 1,
        finished: Map<String, Int> = emptyMap(), names: Map<String, String> = emptyMap(),
    ): Schedule {
        val queue = ArrayDeque(steps.filter { it.hours > 0.04 }.map { Left(it, it.hours) })
        val reviewsDue = mutableMapOf<Int, MutableList<Pair<String, String>>>()
        fun due(w: Int, skillId: String, name: String) { reviewsDue.getOrPut(w) { mutableListOf() }.add(skillId to name) }
        for ((skillId, w) in finished) {
            val name = names[skillId] ?: steps.firstOrNull { it.skillId == skillId }?.name ?: skillId
            for (dw in listOf(1, 3)) if (w + dw >= startWeek) due(w + dw, skillId, name)
        }
        val plan = mutableListOf<Week>()
        var week = startWeek
        var guard = 0
        var id = 0
        while ((queue.isNotEmpty() || reviewsDue.keys.any { it >= week }) && guard++ < 520) {
            var cap = hoursPerWeek
            val sessions = mutableListOf<Session>()
            for ((skillId, name) in (reviewsDue[week] ?: emptyList<Pair<String, String>>()).take(2)) {
                if (cap < REVIEW_H) break
                sessions.add(Session("w$week-${id++}", week, skillId, name, REVIEW_H, Kind.REVIEW))
                cap = round1(cap - REVIEW_H)
            }
            while (queue.isNotEmpty() && cap >= 0.25) {
                val cur = queue.first()
                val h = round1(min(SESSION_MAX_H, min(cur.left, cap)))
                sessions.add(Session("w$week-${id++}", week, cur.step.skillId, cur.step.name, h, Kind.LEARN))
                cur.left = round1(cur.left - h)
                cap = round1(cap - h)
                if (cur.left <= 0.04) {
                    queue.removeFirst()
                    for (dw in listOf(1, 3)) due(week + dw, cur.step.skillId, cur.step.name)
                }
            }
            plan.add(Week(week, sessions, round1(sessions.sumOf { it.hours })))
            week++
            if (queue.isEmpty() && reviewsDue.keys.none { it >= week }) break
        }
        val learnHours = round1(steps.sumOf { if (it.hours > 0.04) it.hours else 0.0 })
        val lastWeek = startWeek + weeks - 1
        val learnWeeks = plan.filter { w -> w.sessions.any { it.kind == Kind.LEARN } }.map { it.week }
        val finishWeek = learnWeeks.maxOrNull() ?: (startWeek - 1)
        val weeksLeft = max(1, lastWeek - startWeek + 1)
        return Schedule(
            weeks = plan, learnHours = learnHours, finishWeek = finishWeek, deadlineWeek = lastWeek,
            onTrack = finishWeek <= lastWeek,
            hoursPerWeekNeeded = round1(ceil((learnHours / weeksLeft) * 1.15 * 10) / 10),
        )
    }

    fun startState(graph: SkillGraph, profile: Profile, mastery: Map<String, Double>): LearnerState {
        val remaining = buildPathway(graph, mastery).associate { it.skillId to it.hours }
        return LearnerState(graph.id, profile, mastery, mastery, remaining, remaining, emptyMap(), 1)
    }

    fun currentSteps(graph: SkillGraph, state: LearnerState): List<StepHours> = topoOrder(graph)
        .filter { (state.remaining[it] ?: 0.0) > 0.04 }
        .map { StepHours(it, graph.byId.getValue(it).name, state.remaining.getValue(it)) }

    fun currentSchedule(graph: SkillGraph, state: LearnerState): Schedule {
        val weeksLeft = state.profile.weeks - (state.week - 1)
        return buildSchedule(currentSteps(graph, state), state.profile.hoursPerWeek, max(1, weeksLeft), state.week, state.finished, graph.names)
    }

    /** Weekly check-in: returns the new state (input untouched) and the facts for the reflection. */
    fun applyCheckin(graph: SkillGraph, state: LearnerState, results: List<SessionResult>): Pair<LearnerState, CheckinSummary> {
        val remaining = state.remaining.toMutableMap()
        val mastery = state.mastery.toMutableMap()
        val finished = state.finished.toMutableMap()
        var planned = 0.0; var done = 0.0; var skipped = 0
        val completed = mutableListOf<String>(); val hard = linkedSetOf<String>(); val easy = linkedSetOf<String>()
        for (r in results) {
            planned += r.session.hours
            if (!r.done) { skipped++; continue }
            done += r.session.hours
            if (r.session.kind != Kind.LEARN) continue
            val adj = RATING_ADJUST[r.rating] ?: 0.0
            remaining[r.session.skillId] = round1(max(0.0, (remaining[r.session.skillId] ?: 0.0) - r.session.hours + adj * r.session.hours))
            if (r.rating == "hard") hard.add(r.session.name)
            if (r.rating == "easy") easy.add(r.session.name)
        }
        for ((id, left) in remaining.toMap()) {
            val k = graph.byId.getValue(id)
            val need = state.needed[id]?.takeIf { it > 0 } ?: 1.0
            val start = state.startMastery[id] ?: 0.0
            if (left <= 0.04) {
                if (id !in finished) { finished[id] = state.week; completed.add(k.name) }
                remaining[id] = 0.0; mastery[id] = k.targetLevel
            } else {
                mastery[id] = clamp01(start + (k.targetLevel - start) * max(0.0, 1 - left / need))
            }
        }
        val next = state.copy(mastery = mastery, remaining = remaining, finished = finished, week = state.week + 1)
        val sched = currentSchedule(graph, next)
        val focus = sched.weeks.firstOrNull()?.sessions?.filter { it.kind == Kind.LEARN }?.map { it.name }?.distinct() ?: emptyList()
        return next to CheckinSummary(
            week = state.week, plannedHours = round1(planned), doneHours = round1(done), completedSkills = completed,
            hardSkills = hard.toList(), easySkills = easy.toList(), skipped = skipped,
            onTrack = sched.onTrack, finishWeek = sched.finishWeek, deadlineWeek = sched.deadlineWeek, nextFocus = focus,
        )
    }

    // ---- Text shown when Llama is not loaded or does not answer in time ----

    fun explainPathwayRules(steps: List<Step>, profile: Profile): String {
        if (steps.isEmpty()) return "Your quiz shows you are already at the target level for every skill in this pathway."
        val hours = round1(steps.sumOf { it.hours })
        val parts = mutableListOf("Start with ${steps[0].name}: it has no open prerequisites and everything after builds on it.")
        steps.firstOrNull { it.openPrereqs.isNotEmpty() }?.let { parts.add("${it.name} comes later because it needs ${it.openPrereqs.joinToString(" and ")} first.") }
        parts.add("In total you need about $hours hours, which is ${ceil(hours / profile.hoursPerWeek).toInt()} weeks at ${fmt(profile.hoursPerWeek)} hours a week.")
        return parts.joinToString(" ")
    }

    fun reflectionRules(s: CheckinSummary): String {
        val out = mutableListOf<String>()
        out.add(
            if (s.doneHours >= s.plannedHours * 0.8) "Good week: you studied ${fmt(s.doneHours)} of ${fmt(s.plannedHours)} planned hours."
            else "You studied ${fmt(s.doneHours)} of ${fmt(s.plannedHours)} planned hours, so unfinished sessions move to next week."
        )
        if (s.completedSkills.isNotEmpty()) out.add("You finished ${s.completedSkills.joinToString(", ")}; short reviews are booked for next week and in three weeks.")
        if (s.hardSkills.isNotEmpty()) out.add("${s.hardSkills.joinToString(", ")} felt hard, so I added extra time for it.")
        if (s.nextFocus.isNotEmpty()) out.add("Next week focuses on ${s.nextFocus.joinToString(" and ")}.")
        out.add(if (s.onTrack) "You are on track for your deadline." else "At this pace you finish in week ${s.finishWeek}, after your week ${s.deadlineWeek} deadline. Add a few hours a week or extend the deadline.")
        return out.joinToString(" ")
    }

    /** 8.0 -> "8", 6.7 -> "6.7" (matches how the website prints numbers). */
    fun fmt(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else x.toString()
}
