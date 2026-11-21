package com.team4.pathfinder.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Same cases as website/test/engine.test.mjs, plus parity checks: for the same learner the Kotlin engine
 * must produce exactly the pathway, week 1 and finish week the JavaScript engine prints.
 */
class EngineTest {
    private val graph = SkillGraph.parse(File("../../content/domains/data-analyst.json").readText())

    // Learner: knows Excel, 26 weeks at 8 h/week; gets the SQL question right and the other three wrong.
    private val profile = Profile(26, 8.0, listOf("spreadsheets"))
    private val items = Engine.buildQuiz(graph, profile)
    private val answers: Map<String, Answer> = items.associate { it ->
        it.skillId to when (it) {
            is QuizItem.Mcq -> Answer.Choice(if (it.skillId == "sql-basics") it.answer else (it.answer + 1) % it.options.size)
            is QuizItem.Self -> Answer.Level(it.preset)
        }
    }
    private val mastery = Engine.scoreQuiz(graph, items, answers, profile)
    private val pathway = Engine.buildPathway(graph, mastery)

    @Test fun matchesDomain() {
        assertEquals("data-analyst", Engine.matchDomain("I want a data analyst job", listOf(graph))?.id)
        assertNull(Engine.matchDomain("I want to bake bread", listOf(graph)))
    }

    @Test fun readsGoals() {
        assertEquals(Profile(26, 8.0, listOf("spreadsheets")),
            Engine.parseGoalRules("I know some Excel and want a data analyst job in 6 months, about 8 hours a week", graph))
        val q = Engine.parseGoalRules("I want to learn SQL in 3 months, 1 hour a day", graph)
        assertEquals(13, q.weeks); assertEquals(7.0, q.hoursPerWeek, 0.0); assertEquals(emptyList<String>(), q.knownSkillIds)
        assertEquals(Profile(52, 10.0, listOf("sql-basics", "python-basics")),
            Engine.parseGoalRules("I've used Python and a bit of SQL at uni. I want to become a data analyst within a year, around 10 hours a week.", graph))
        assertEquals(9, Engine.parseGoalRules("in two months, five hours a week", graph).weeks)
        assertEquals(26, Engine.parseGoalRules("within half a year", graph).weeks)
        assertEquals(3, Engine.parseGoalRules("ready in 3 weeks, 2 hours per day", graph).weeks)
        assertEquals(Profile(26, 6.0, emptyList()), Engine.parseGoalRules("Become an analyst", graph))
    }

    @Test fun reportsWhichValuesTheGoalStated() {
        val stated = Engine.readGoalRules("Data analyst in 6 months, 8 hours a week", graph)
        assertEquals(true, stated.foundWeeks); assertEquals(true, stated.foundHours)
        val defaults = Engine.readGoalRules("Become an analyst, no rush", graph)
        assertEquals(false, defaults.foundWeeks); assertEquals(false, defaults.foundHours)
        assertEquals(Profile(26, 6.0, emptyList()), defaults.profile)
    }

    @Test fun groundsMentionedSkills() {
        assertEquals(listOf("spreadsheets", "bi-dashboards"), Engine.mentionedSkillIds("I use Excel and some Power BI", graph))
        assertEquals(emptyList<String>(), Engine.mentionedSkillIds("I want a data analyst job", graph))
    }

    @Test fun topoOrderPutsPrerequisitesFirst() {
        val order = Engine.topoOrder(graph)
        assertEquals(graph.skills.size, order.size)
        for (s in graph.skills) for (p in s.prereqs) assertTrue("$p before ${s.id}", order.indexOf(p) < order.indexOf(s.id))
    }

    @Test fun quizScoresZeroToOne() {
        assertEquals(graph.skills.size, items.size)
        assertEquals(4, items.count { it is QuizItem.Mcq })
        assertTrue(mastery.values.all { it in 0.0..1.0 })
        assertEquals(0.8, mastery.getValue("sql-basics"), 1e-9)
        assertEquals(0.4, mastery.getValue("spreadsheets"), 1e-9) // wrong answer, but says they know Excel
    }

    @Test fun pathwayMatchesWebsiteEngine() {
        assertEquals(
            "Spreadsheets 6h > Descriptive statistics 6.7h > Advanced SQL 15h > Python basics 9.6h > pandas for data analysis 15h > " +
                "Data cleaning 8h > Data visualization 8h > BI dashboards 12h > Inferential statistics 12h > Intro to machine learning 10h > Portfolio project 20h",
            pathway.joinToString(" > ") { "${it.name} ${Engine.fmt(it.hours)}h" },
        )
        assertTrue(pathway[0].openPrereqs.isEmpty())
        val ids = pathway.map { it.skillId }
        for (id in ids) for (pre in graph.byId.getValue(id).prereqs) if (pre in ids) assertTrue(ids.indexOf(pre) < ids.indexOf(id))
    }

    @Test fun scheduleMatchesWebsiteAndRespectsHours() {
        val plan = Engine.currentSchedule(graph, Engine.startState(graph, profile, mastery))
        assertEquals(
            "Spreadsheets 1.5h, Spreadsheets 1.5h, Spreadsheets 1.5h, Spreadsheets 1.5h, Descriptive statistics 1.5h, Descriptive statistics 0.5h",
            plan.weeks[0].sessions.joinToString(", ") { "${it.name} ${Engine.fmt(it.hours)}h" },
        )
        assertEquals(17, plan.finishWeek); assertEquals(26, plan.deadlineWeek); assertTrue(plan.onTrack)
        for (w in plan.weeks) assertTrue("week ${w.week} has ${w.hours} h", w.hours <= profile.hoursPerWeek + 1e-9)
        val learned = plan.weeks.flatMap { it.sessions }.filter { it.kind == Kind.LEARN }
        assertEquals(122.3, Engine.round1(learned.sumOf { it.hours }), 1e-9)
        assertTrue(learned.all { it.hours <= 1.5 })
    }

    @Test fun checkinUpdatesStateAndAddsTimeForHard() {
        val state = Engine.startState(graph, profile, mastery)
        val week1 = Engine.currentSchedule(graph, state).weeks[0].sessions
        val (s2, sum) = Engine.applyCheckin(graph, state, week1.mapIndexed { i, s -> SessionResult(s, true, if (i == 0) "hard" else "ok") })
        assertEquals(2, s2.week); assertEquals(1, state.week)
        assertEquals(8.0, sum.doneHours, 1e-9)
        assertTrue(sum.hardSkills.contains("Spreadsheets"))
        // Spreadsheets: 6 h needed, 6 h done, +25% of the first 1.5 h session back for "hard" = 0.4 h left.
        assertEquals(0.4, s2.remaining.getValue("spreadsheets"), 0.1)
    }

    @Test fun finishedSkillsGetNamedReviewsAndReachTarget() {
        var s = Engine.startState(graph, profile, mastery)
        var guard = 0
        while (s.finished.isEmpty() && guard++ < 20) {
            val wk = Engine.currentSchedule(graph, s).weeks[0]
            s = Engine.applyCheckin(graph, s, wk.sessions.map { SessionResult(it, true) }).first
        }
        val (doneId, weekDone) = s.finished.entries.first()
        assertEquals(graph.byId.getValue(doneId).targetLevel, s.mastery.getValue(doneId), 1e-9)
        val review = Engine.currentSchedule(graph, s).weeks.firstOrNull { it.week == weekDone + 1 }?.sessions?.firstOrNull { it.kind == Kind.REVIEW && it.skillId == doneId }
        assertNotNull("review booked next week", review)
        assertEquals(graph.byId.getValue(doneId).name, review!!.name)
    }

    @Test fun deadlineCheckFlagsPlanThatCannotFit() {
        val tight = Engine.buildSchedule(pathway.map { StepHours(it.skillId, it.name, it.hours) }, 2.0, 4)
        assertFalse(tight.onTrack)
        assertTrue(tight.hoursPerWeekNeeded > 2)
        assertTrue(Engine.reflectionRules(CheckinSummary(1, 2.0, 2.0, emptyList(), emptyList(), emptyList(), 0, false, 30, 4, listOf("SQL basics"))).contains("week 30"))
    }

    @Test fun rulesExplanationMatchesWebsite() {
        assertEquals(
            "Start with Spreadsheets: it has no open prerequisites and everything after builds on it. pandas for data analysis comes later because it needs Python basics first. In total you need about 122.3 hours, which is 16 weeks at 8 hours a week.",
            Engine.explainPathwayRules(pathway, profile),
        )
    }
}
