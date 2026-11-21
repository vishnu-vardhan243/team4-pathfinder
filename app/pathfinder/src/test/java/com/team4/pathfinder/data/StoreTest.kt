package com.team4.pathfinder.data

import com.team4.pathfinder.engine.Answer
import com.team4.pathfinder.engine.Engine
import com.team4.pathfinder.engine.Profile
import com.team4.pathfinder.engine.SessionResult
import com.team4.pathfinder.engine.SkillGraph
import com.team4.pathfinder.llm.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Saved progress must come back exactly as it was written, including after a real check-in. */
class StoreTest {
    private val graph = SkillGraph.parse(File("../../content/domains/data-analyst.json").readText())

    private fun midJourney(): Saved {
        val profile = Profile(52, 10.0, listOf("sql-basics", "python-basics"))
        val answers: Map<String, Answer> = mapOf("sql-basics" to Answer.Choice(1), "spreadsheets" to Answer.Choice(0), "pandas" to Answer.Level("some"))
        val items = Engine.buildQuiz(graph, profile)
        val state = Engine.startState(graph, profile, Engine.scoreQuiz(graph, items, answers, profile))
        val week = Engine.currentSchedule(graph, state).weeks[0]
        val (next, summary) = Engine.applyCheckin(graph, state, week.sessions.mapIndexed { i, s -> SessionResult(s, i > 0, if (i == 1) "hard" else "ok") })
        return Saved(
            screen = Screen.CHECKIN, goalText = "Data analyst within a year, 10 hours a week", profile = profile, goalSource = Source.LLAMA,
            answers = answers, state = next, results = mapOf("w2-0" to SessionMark(true, "easy")),
            explain = SavedText("Start with spreadsheets.", Source.RULES),
            checkin = SavedCheckin(summary, prevFinish = 14, reflection = SavedText("Good week.", Source.LLAMA)),
        )
    }

    @Test fun roundTripsThroughJson() {
        val s = midJourney()
        assertEquals(s, Store.decode(Store.encode(s)))
    }

    @Test fun savesAndLoadsAFile() {
        val f = File.createTempFile("pathfinder", ".json").also { it.delete() }
        val store = Store(f)
        assertNull(store.load())
        val s = midJourney()
        store.save(s); store.save(s) // second save replaces the first
        assertEquals(s, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test fun unreadableFileStartsFresh() {
        val f = File.createTempFile("pathfinder", ".json")
        f.writeText("{not json")
        assertNull(Store(f).load())
        f.delete()
    }
}
