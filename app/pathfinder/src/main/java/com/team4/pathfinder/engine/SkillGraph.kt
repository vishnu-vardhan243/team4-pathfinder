package com.team4.pathfinder.engine

import org.json.JSONArray
import org.json.JSONObject

data class Resource(val title: String, val url: String, val kind: String)

data class QuizQuestion(val q: String, val options: List<String>, val answer: Int)

data class Skill(
    val id: String,
    val name: String,
    val summary: String,
    val targetLevel: Double,
    val estHours: Double,
    val prereqs: List<String>,
    val resources: List<Resource>,
    val quiz: List<QuizQuestion>,
)

/** One goal domain, in the format of content/skill-graph.schema.json. */
data class SkillGraph(
    val id: String,
    val title: String,
    val goalKeywords: List<String>,
    val skills: List<Skill>,
) {
    val byId: Map<String, Skill> = skills.associateBy { it.id }
    val names: Map<String, String> = skills.associate { it.id to it.name }

    companion object {
        fun parse(json: String): SkillGraph {
            val o = JSONObject(json)
            val arr = o.getJSONArray("skills")
            val skills = List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                Skill(
                    id = s.getString("id"),
                    name = s.getString("name"),
                    summary = s.optString("summary", ""),
                    targetLevel = s.getDouble("targetLevel"),
                    estHours = s.getDouble("estHours"),
                    prereqs = strings(s.optJSONArray("prereqs")),
                    resources = s.optJSONArray("resources")?.let { r ->
                        List(r.length()) { j -> r.getJSONObject(j).let { Resource(it.getString("title"), it.getString("url"), it.getString("kind")) } }
                    } ?: emptyList(),
                    quiz = s.optJSONArray("quiz")?.let { q ->
                        List(q.length()) { j -> q.getJSONObject(j).let { QuizQuestion(it.getString("q"), strings(it.getJSONArray("options")), it.getInt("answer")) } }
                    } ?: emptyList(),
                )
            }
            return SkillGraph(o.getString("id"), o.getString("title"), strings(o.optJSONArray("goalKeywords")), skills)
        }

        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
    }
}
