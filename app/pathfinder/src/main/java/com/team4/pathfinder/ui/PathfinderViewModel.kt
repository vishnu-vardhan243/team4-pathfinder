package com.team4.pathfinder.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.team4.pathfinder.data.Saved
import com.team4.pathfinder.data.SavedCheckin
import com.team4.pathfinder.data.SavedText
import com.team4.pathfinder.data.Screen
import com.team4.pathfinder.data.SessionMark
import com.team4.pathfinder.data.Store
import com.team4.pathfinder.engine.Answer
import com.team4.pathfinder.engine.Engine
import com.team4.pathfinder.engine.Profile
import com.team4.pathfinder.engine.QuizItem
import com.team4.pathfinder.engine.Schedule
import com.team4.pathfinder.engine.SessionResult
import com.team4.pathfinder.engine.SkillGraph
import com.team4.pathfinder.engine.Step
import com.team4.pathfinder.llm.Coach
import com.team4.pathfinder.llm.LlmEngine
import com.team4.pathfinder.llm.Reply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

sealed class ModelState {
    data object Missing : ModelState()
    data object NotLoaded : ModelState()
    data object Loading : ModelState()
    data class Ready(val loadMs: Long) : ModelState()
    data class Failed(val message: String) : ModelState()
}

/** All app state and actions. Numbers come from Engine, words from Coach (Llama or rules), progress lives in Store. */
class PathfinderViewModel(app: Application) : AndroidViewModel(app) {
    val graph: SkillGraph = SkillGraph.parse(app.assets.open("domains/data-analyst.json").bufferedReader().use { it.readText() })
    val llm = LlmEngine("${LlmEngine.DEFAULT_DIR}/llama3_2_1b_8da4w.pte", "${LlmEngine.DEFAULT_DIR}/tokenizer.model")
    private val store = Store(File(app.filesDir, "pathfinder.json"))

    var saved by mutableStateOf(store.load() ?: Saved()); private set
    var model by mutableStateOf(if (llm.filesPresent()) ModelState.NotLoaded else ModelState.Missing); private set
    /** What the coach is doing right now (shown with a spinner), or null when idle. */
    var working by mutableStateOf<String?>(null); private set
    /** Llama's reply so far while it streams. */
    var streaming by mutableStateOf(""); private set

    val llamaReady: Boolean get() = model is ModelState.Ready

    // Llama handles one request at a time. A request that arrives while it is busy waits here and runs next.
    private var pendingExplain = false
    private var pendingReflect = false

    private fun done() {
        working = null
        when {
            pendingReflect -> { pendingReflect = false; reflectIfNeeded() }
            pendingExplain -> { pendingExplain = false; explainIfNeeded() }
        }
    }

    private fun update(change: (Saved) -> Saved) {
        val before = saved.screen
        val next = change(saved)
        saved = next
        if (next.screen != before) Log.i(TAG, "SCREEN ${next.screen}")
        viewModelScope.launch(Dispatchers.IO) { store.save(next) }
    }

    fun go(screen: Screen) = update { it.copy(screen = screen) }

    // ---------- 1. Model setup ----------
    fun loadModel() {
        if (model !is ModelState.NotLoaded && model !is ModelState.Failed) return
        model = ModelState.Loading
        viewModelScope.launch {
            model = try {
                val ms = llm.load()
                Log.i(TAG, "LOAD ok ${ms}ms")
                ModelState.Ready(ms)
            } catch (e: Exception) {
                Log.e(TAG, "LOAD failed", e)
                ModelState.Failed(e.message ?: "unknown error")
            }
        }
    }

    // ---------- 2. Goal chat ----------
    fun sendGoal(text: String) {
        val t = text.trim()
        if (t.length < 8 || working != null) return
        update { it.copy(goalText = t, profile = null, goalSource = null) }
        working = if (llamaReady) "Llama is reading your goal" else "Reading your goal"
        viewModelScope.launch {
            val r = Coach.readGoal(llm, t, graph)
            Log.i(TAG, "GOAL ${r.source} weeks=${r.profile.weeks} hours=${r.profile.hoursPerWeek} known=${r.profile.knownSkillIds} ${stats(r.reply)}")
            update { it.copy(profile = r.profile, goalSource = r.source) }
            done()
        }
    }

    fun changeGoal() = update { it.copy(profile = null, goalSource = null) }

    fun confirmProfile(weeks: Int, hours: Double, known: List<String>) {
        val p = Profile(weeks.coerceIn(1, 520), hours.coerceIn(1.0, 80.0), known)
        val presets = Engine.buildQuiz(graph, p).filterIsInstance<QuizItem.Self>().associate { it.skillId to (Answer.Level(it.preset) as Answer) }
        update { it.copy(profile = p, answers = presets, state = null, explain = null, results = emptyMap(), checkin = null, screen = Screen.QUIZ) }
    }

    // ---------- 3. Quiz ----------
    val quiz: List<QuizItem> get() = saved.profile?.let { Engine.buildQuiz(graph, it) } ?: emptyList()

    fun answer(skillId: String, a: Answer) = update { it.copy(answers = it.answers + (skillId to a)) }

    fun questionsLeft(): Int = quiz.count { it is QuizItem.Mcq && saved.answers[it.skillId] !is Answer.Choice }

    fun finishQuiz() {
        val p = saved.profile ?: return
        val mastery = Engine.scoreQuiz(graph, quiz, saved.answers, p)
        update { it.copy(state = Engine.startState(graph, p, mastery), explain = null, results = emptyMap(), checkin = null, screen = Screen.PATHWAY) }
    }

    // ---------- 4. Pathway ----------
    fun pathway(): List<Step> = saved.state?.let { Engine.buildPathway(graph, it.mastery) } ?: emptyList()

    fun explainIfNeeded() {
        val st = saved.state ?: return
        if (saved.explain != null) return
        if (working != null) { pendingExplain = true; return }
        val steps = Engine.buildPathway(graph, st.mastery)
        streaming = ""
        working = if (llamaReady) "Llama is explaining your pathway" else "Explaining your pathway"
        viewModelScope.launch {
            val r = Coach.explainPathway(llm, steps, st.profile, saved.goalText) { streaming = it }
            Log.i(TAG, "EXPLAIN ${r.source} ${stats(r.reply)}")
            // Only keep it if the pathway it describes is still the current one.
            if (saved.state == st) update { it.copy(explain = SavedText(r.text, r.source)) }
            done()
        }
    }

    // ---------- 5. This week ----------
    fun schedule(): Schedule? = saved.state?.let { Engine.currentSchedule(graph, it) }

    fun mark(sessionId: String, done: Boolean? = null, rating: String? = null) = update { s ->
        val cur = s.results[sessionId] ?: SessionMark()
        s.copy(results = s.results + (sessionId to cur.copy(done = done ?: cur.done, rating = rating ?: cur.rating)))
    }

    fun useHours(hours: Double) = update { s ->
        val st = s.state ?: return@update s
        val p = st.profile.copy(hoursPerWeek = hours)
        s.copy(profile = p, state = st.copy(profile = p))
    }

    fun checkIn() {
        val st = saved.state ?: return
        val sched = Engine.currentSchedule(graph, st)
        val week = sched.weeks.firstOrNull() ?: return
        val results = week.sessions.map { SessionResult(it, saved.results[it.id]?.done ?: false, saved.results[it.id]?.rating ?: "ok") }
        val (next, summary) = Engine.applyCheckin(graph, st, results)
        Log.i(TAG, "CHECKIN week=${summary.week} done=${summary.doneHours}/${summary.plannedHours} finished=${summary.completedSkills} finish=${sched.finishWeek}->${summary.finishWeek}")
        update { it.copy(state = next, results = emptyMap(), explain = null, checkin = SavedCheckin(summary, sched.finishWeek), screen = Screen.CHECKIN) }
        reflectIfNeeded()
    }

    // ---------- 6. Check-in ----------
    fun reflectIfNeeded() {
        val c = saved.checkin ?: return
        if (c.reflection != null) return
        if (working != null) { pendingReflect = true; return }
        streaming = ""
        working = if (llamaReady) "Llama is writing your check-in" else "Writing your check-in"
        viewModelScope.launch {
            val r = Coach.reflect(llm, c.summary) { streaming = it }
            Log.i(TAG, "REFLECT ${r.source} ${stats(r.reply)}")
            update { it.copy(checkin = it.checkin?.copy(reflection = SavedText(r.text, r.source))) }
            done()
        }
    }

    fun startOver() {
        store.clear()
        saved = Saved(screen = Screen.GOAL)
        Log.i(TAG, "RESET")
    }

    companion object { const val TAG = "Pathfinder" }

    /** Timing of one Llama call for the log: total time, time to first token, tokens and generation speed. */
    private fun stats(r: Reply?): String =
        if (r == null) "ms=0" else "ms=${r.totalMs} ttft=${r.firstTokenMs} tokens=${r.tokens} tps=${"%.1f".format(r.tokensPerSecond)}"
}
