package com.team4.pathfinder.data

import com.team4.pathfinder.engine.Answer
import com.team4.pathfinder.engine.CheckinSummary
import com.team4.pathfinder.engine.LearnerState
import com.team4.pathfinder.engine.Profile
import com.team4.pathfinder.llm.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The six screens of the app, in order. */
enum class Screen { SETUP, GOAL, QUIZ, PATHWAY, WEEK, CHECKIN }

data class SavedText(val text: String, val source: Source)

data class SessionMark(val done: Boolean = false, val rating: String = "ok")

data class SavedCheckin(val summary: CheckinSummary, val prevFinish: Int, val reflection: SavedText? = null)

/** Everything the app needs to reopen exactly where the learner left off. */
data class Saved(
    val screen: Screen = Screen.SETUP,
    val goalText: String = "",
    val profile: Profile? = null,
    val goalSource: Source? = null,
    val answers: Map<String, Answer> = emptyMap(),
    val state: LearnerState? = null,
    val results: Map<String, SessionMark> = emptyMap(),
    val explain: SavedText? = null,
    val checkin: SavedCheckin? = null,
)

/**
 * On-device storage: one JSON file in the app's private storage. Nothing leaves the phone.
 * Written to a temp file first and then renamed, so a crash mid-write never corrupts the saved plan.
 */
class Store(private val file: File) {
    fun load(): Saved? = try {
        if (file.exists()) decode(JSONObject(file.readText())) else null
    } catch (e: Exception) {
        null // unreadable or from an older version: start fresh rather than crash
    }

    fun save(s: Saved) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(s).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun clear() { file.delete() }

    companion object {
        private const val VERSION = 1

        fun encode(s: Saved): JSONObject = JSONObject().apply {
            put("v", VERSION)
            put("screen", s.screen.name)
            put("goalText", s.goalText)
            s.profile?.let { put("profile", profile(it)) }
            s.goalSource?.let { put("goalSource", it.name) }
            put("answers", JSONObject().apply {
                for ((k, a) in s.answers) put(k, when (a) {
                    is Answer.Choice -> JSONObject().put("c", a.index)
                    is Answer.Level -> JSONObject().put("l", a.level)
                })
            })
            s.state?.let { st ->
                put("state", JSONObject().apply {
                    put("domainId", st.domainId); put("profile", profile(st.profile))
                    put("startMastery", doubles(st.startMastery)); put("mastery", doubles(st.mastery))
                    put("needed", doubles(st.needed)); put("remaining", doubles(st.remaining))
                    put("finished", JSONObject().apply { for ((k, w) in st.finished) put(k, w) }); put("week", st.week)
                })
            }
            put("results", JSONObject().apply { for ((k, m) in s.results) put(k, JSONObject().put("done", m.done).put("rating", m.rating)) })
            s.explain?.let { put("explain", text(it)) }
            s.checkin?.let { c ->
                put("checkin", JSONObject().apply {
                    val m = c.summary
                    put("summary", JSONObject().apply {
                        put("week", m.week); put("plannedHours", m.plannedHours); put("doneHours", m.doneHours)
                        put("completedSkills", JSONArray(m.completedSkills)); put("hardSkills", JSONArray(m.hardSkills))
                        put("easySkills", JSONArray(m.easySkills)); put("skipped", m.skipped); put("onTrack", m.onTrack)
                        put("finishWeek", m.finishWeek); put("deadlineWeek", m.deadlineWeek); put("nextFocus", JSONArray(m.nextFocus))
                    })
                    put("prevFinish", c.prevFinish)
                    c.reflection?.let { put("reflection", text(it)) }
                })
            }
        }

        fun decode(o: JSONObject): Saved {
            require(o.optInt("v") == VERSION) { "unsupported save version" }
            val answers = o.optJSONObject("answers")?.let { a ->
                a.keys().asSequence().toList().associateWith { k ->
                    val v = a.getJSONObject(k)
                    if (v.has("c")) Answer.Choice(v.getInt("c")) else Answer.Level(v.getString("l"))
                }
            } ?: emptyMap()
            val state = o.optJSONObject("state")?.let { st ->
                LearnerState(
                    domainId = st.getString("domainId"), profile = profile(st.getJSONObject("profile")),
                    startMastery = doubles(st.getJSONObject("startMastery")), mastery = doubles(st.getJSONObject("mastery")),
                    needed = doubles(st.getJSONObject("needed")), remaining = doubles(st.getJSONObject("remaining")),
                    finished = st.getJSONObject("finished").let { f -> f.keys().asSequence().toList().associateWith { f.getInt(it) } },
                    week = st.getInt("week"),
                )
            }
            val results = o.optJSONObject("results")?.let { r ->
                r.keys().asSequence().toList().associateWith { k -> r.getJSONObject(k).let { SessionMark(it.getBoolean("done"), it.getString("rating")) } }
            } ?: emptyMap()
            val checkin = o.optJSONObject("checkin")?.let { c ->
                val m = c.getJSONObject("summary")
                SavedCheckin(
                    summary = CheckinSummary(
                        week = m.getInt("week"), plannedHours = m.getDouble("plannedHours"), doneHours = m.getDouble("doneHours"),
                        completedSkills = strings(m.getJSONArray("completedSkills")), hardSkills = strings(m.getJSONArray("hardSkills")),
                        easySkills = strings(m.getJSONArray("easySkills")), skipped = m.getInt("skipped"), onTrack = m.getBoolean("onTrack"),
                        finishWeek = m.getInt("finishWeek"), deadlineWeek = m.getInt("deadlineWeek"), nextFocus = strings(m.getJSONArray("nextFocus")),
                    ),
                    prevFinish = c.getInt("prevFinish"),
                    reflection = c.optJSONObject("reflection")?.let { text(it) },
                )
            }
            return Saved(
                screen = Screen.valueOf(o.getString("screen")),
                goalText = o.optString("goalText", ""),
                profile = o.optJSONObject("profile")?.let { profile(it) },
                goalSource = o.optString("goalSource", "").takeIf { it.isNotEmpty() }?.let { Source.valueOf(it) },
                answers = answers, state = state, results = results,
                explain = o.optJSONObject("explain")?.let { text(it) },
                checkin = checkin,
            )
        }

        private fun profile(p: Profile) = JSONObject().put("weeks", p.weeks).put("hours", p.hoursPerWeek).put("known", JSONArray(p.knownSkillIds))
        private fun profile(o: JSONObject) = Profile(o.getInt("weeks"), o.getDouble("hours"), strings(o.getJSONArray("known")))
        private fun doubles(m: Map<String, Double>) = JSONObject().apply { for ((k, v) in m) put(k, v) }
        private fun doubles(o: JSONObject): Map<String, Double> = o.keys().asSequence().toList().associateWith { o.getDouble(it) }
        private fun text(t: SavedText) = JSONObject().put("text", t.text).put("source", t.source.name)
        private fun text(o: JSONObject) = SavedText(o.getString("text"), Source.valueOf(o.getString("source")))
        private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
    }
}
