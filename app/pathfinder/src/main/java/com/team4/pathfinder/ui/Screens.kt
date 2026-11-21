package com.team4.pathfinder.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.team4.pathfinder.data.Screen
import com.team4.pathfinder.engine.Answer
import com.team4.pathfinder.engine.Engine
import com.team4.pathfinder.engine.Kind
import com.team4.pathfinder.engine.QuizItem
import com.team4.pathfinder.llm.Source

// ---------- Theme: same palette as the website ----------
private val Accent = Color(0xFF2450D6)
private val Good = Color(0xFF17734A)
private val Wait = Color(0xFF8A5509)

@Composable
fun PathfinderTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF86A6FF), secondary = Color(0xFF4CC38A), tertiary = Color(0xFFE0A84A))
    else lightColorScheme(primary = Accent, secondary = Good, tertiary = Wait, background = Color(0xFFF3F5F7), surface = Color(0xFFF3F5F7))
    MaterialTheme(colorScheme = scheme, content = content)
}

private val EXAMPLE_GOAL = "I've used Python and a bit of SQL at uni. I want to become a data analyst within a year, around 10 hours a week."
private val STEPS = listOf(Screen.GOAL to "Goal", Screen.QUIZ to "Quiz", Screen.PATHWAY to "Pathway", Screen.WEEK to "Plan", Screen.CHECKIN to "Check-in")

private fun fmt(x: Double) = Engine.fmt(x)

// ---------- Shell ----------
@Composable
fun PathfinderApp(vm: PathfinderViewModel) {
    val s = vm.saved
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Header(vm)
            if (s.screen != Screen.SETUP) StepBar(vm)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (s.screen) {
                    Screen.SETUP -> SetupScreen(vm)
                    Screen.GOAL -> GoalScreen(vm)
                    Screen.QUIZ -> if (s.profile != null) QuizScreen(vm) else GoalScreen(vm)
                    Screen.PATHWAY -> if (s.state != null) PathwayScreen(vm) else GoalScreen(vm)
                    Screen.WEEK -> if (s.state != null) WeekScreen(vm) else GoalScreen(vm)
                    Screen.CHECKIN -> if (s.checkin != null) CheckinScreen(vm) else GoalScreen(vm)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Header(vm: PathfinderViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Pathfinder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        val (label, color) = when (val m = vm.model) {
            is ModelState.Ready -> "Llama · on-device" to MaterialTheme.colorScheme.secondary
            ModelState.Loading -> "Loading Llama" to MaterialTheme.colorScheme.primary
            else -> "Rules mode" to MaterialTheme.colorScheme.tertiary
        }
        Pill(label, color, Modifier.selectable(selected = false, role = Role.Button) { vm.go(Screen.SETUP) })
    }
}

@Composable
private fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StepBar(vm: PathfinderViewModel) {
    val s = vm.saved
    val reached = mapOf(Screen.GOAL to true, Screen.QUIZ to (s.profile != null), Screen.PATHWAY to (s.state != null), Screen.WEEK to (s.state != null), Screen.CHECKIN to (s.checkin != null))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        STEPS.forEachIndexed { i, (screen, label) ->
            FilterChip(selected = s.screen == screen, enabled = reached[screen] == true, onClick = { vm.go(screen) }, label = { Text("${i + 1} $label") })
        }
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SourceLine(source: Source?) {
    if (source == null) return
    Text(
        if (source == Source.LLAMA) "Written by Llama 3.2 on this phone" else "Written by built-in rules",
        style = MaterialTheme.typography.labelSmall,
        color = if (source == Source.LLAMA) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- 1. Model setup ----------
@Composable
private fun SetupScreen(vm: PathfinderViewModel) {
    val ctx = LocalContext.current
    val ramGb = remember {
        val mi = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        mi.totalMem / 1_073_741_824.0
    }
    Text("Your study coach, on this phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Pathfinder runs Llama 3.2 1B with ExecuTorch right here. Your goals and progress never leave the device, and it works without internet.")
    Section("Llama model") {
        when (val m = vm.model) {
            ModelState.Missing -> Text("Model files not found in ${com.team4.pathfinder.llm.LlmEngine.DEFAULT_DIR}. Copy llama3_2_1b_8da4w.pte and tokenizer.model there (see the project README). You can still continue with built-in rules.")
            ModelState.NotLoaded -> {
                Text("Ready to load (about 860 MB, needs about 1.4 GB of memory).")
                Button(onClick = vm::loadModel) { Text("Load Llama") }
            }
            ModelState.Loading -> Working("Loading Llama… this takes a few seconds")
            is ModelState.Ready -> Text("Llama 3.2 1B loaded in ${fmt(m.loadMs / 1000.0)} s. Running on the CPU with XNNPACK.", color = MaterialTheme.colorScheme.secondary)
            is ModelState.Failed -> {
                Text("Could not load Llama: ${m.message}", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::loadModel) { Text("Try again") }
            }
        }
        Text("Device: ${Build.MANUFACTURER} ${Build.MODEL} · ${"%.1f".format(ramGb)} GB RAM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val next = if (vm.saved.profile == null) Screen.GOAL else vm.saved.let { if (it.state != null) Screen.WEEK else Screen.QUIZ }
    Button(onClick = { vm.go(next) }, enabled = vm.model !is ModelState.Loading, modifier = Modifier.fillMaxWidth()) {
        Text(if (vm.llamaReady || vm.model is ModelState.Missing) "Continue" else "Continue without Llama")
    }
}

// ---------- 2. Goal chat ----------
@Composable
private fun Bubble(text: String, mine: Boolean, content: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (text.isNotEmpty()) Text(text)
                content?.invoke()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalScreen(vm: PathfinderViewModel) {
    val s = vm.saved
    Text("What do you want to achieve?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Bubble("Hi, I'm your study coach. Tell me your goal, your deadline and how many hours a week you can study. Mention anything you already know.", mine = false)
    val p = s.profile
    if (p == null) {
        if (vm.working != null && s.goalText.isNotEmpty()) {
            Bubble(s.goalText, mine = true)
            Bubble("", mine = false) { Working(vm.working!!) }
            return
        }
        var text by rememberSaveable { mutableStateOf(s.goalText.ifEmpty { EXAMPLE_GOAL }) }
        OutlinedTextField(text, { text = it }, label = { Text("Your goal") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        if (s.goalText.isEmpty()) Text("Example text: replace it with your own goal.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.sendGoal(text) }, enabled = text.trim().length >= 8, modifier = Modifier.fillMaxWidth()) { Text("Send") }
        return
    }
    Bubble(s.goalText, mine = true)
    var weeks by rememberSaveable(p) { mutableStateOf(p.weeks.toString()) }
    var hours by rememberSaveable(p) { mutableStateOf(fmt(p.hoursPerWeek)) }
    var known by remember(p) { mutableStateOf(p.knownSkillIds.toSet()) }
    Bubble("Here's what I understood. Fix anything that's wrong.", mine = false) {
        SourceLine(s.goalSource)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(weeks, { weeks = it.filter(Char::isDigit).take(3) }, label = { Text("Weeks to deadline") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(hours, { hours = it.filter { c -> c.isDigit() || c == '.' }.take(4) }, label = { Text("Hours a week") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        }
        Text("Skills you already know", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.graph.skills.forEach { k ->
                FilterChip(selected = k.id in known, onClick = { known = if (k.id in known) known - k.id else known + k.id }, label = { Text(k.name) })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = vm::changeGoal) { Text("Change goal") }
        Button(
            onClick = { vm.confirmProfile(weeks.toIntOrNull() ?: p.weeks, hours.toDoubleOrNull() ?: p.hoursPerWeek, vm.graph.skills.map { it.id }.filter { it in known }) },
            modifier = Modifier.weight(1f),
        ) { Text("Start the skill quiz") }
    }
}

// ---------- 3. Quiz ----------
@Composable
private fun QuizScreen(vm: PathfinderViewModel) {
    Text("Quick skill check", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Four short questions and a self-rating for the rest. It sets your starting level for each skill.")
    for (item in vm.quiz) {
        val a = vm.saved.answers[item.skillId]
        Section {
            Text(item.skill.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (item) {
                is QuizItem.Mcq -> {
                    Text(item.q, fontWeight = FontWeight.SemiBold)
                    item.options.forEachIndexed { i, o ->
                        val chosen = (a as? Answer.Choice)?.index == i
                        Row(
                            Modifier.fillMaxWidth().selectable(chosen, role = Role.RadioButton) { vm.answer(item.skillId, Answer.Choice(i)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) { RadioButton(selected = chosen, onClick = null); Spacer(Modifier.width(8.dp)); Text(o) }
                    }
                }
                is QuizItem.Self -> {
                    Text("How well do you know ${item.skill.lowercase()}?", fontWeight = FontWeight.SemiBold)
                    val level = (a as? Answer.Level)?.level ?: item.preset
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none" to "Not yet", "some" to "A little", "confident" to "Confident").forEach { (v, label) ->
                            FilterChip(selected = level == v, onClick = { vm.answer(item.skillId, Answer.Level(v)) }, label = { Text(label) })
                        }
                    }
                }
            }
        }
    }
    val left = vm.questionsLeft()
    Text(if (left > 0) "$left question${if (left > 1) "s" else ""} left" else "All answered", style = MaterialTheme.typography.bodySmall)
    Button(onClick = vm::finishQuiz, enabled = left == 0, modifier = Modifier.fillMaxWidth()) { Text("See my skill gaps") }
}

// ---------- 4. Pathway ----------
@Composable
private fun PathwayScreen(vm: PathfinderViewModel) {
    val st = vm.saved.state ?: return
    val steps = vm.pathway()
    LaunchedEffect(st) { vm.explainIfNeeded() }
    val uri = LocalUriHandler.current
    Text("Your skill gaps and pathway", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("${steps.size} skills to work on, about ${fmt(Engine.round1(steps.sumOf { it.hours }))} hours in total.")
    Section("Skill levels") {
        for (k in vm.graph.skills) {
            val m = st.mastery[k.id] ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${Math.round(m * 100)}% / ${Math.round(k.targetLevel * 100)}%", style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { m.toFloat() }, modifier = Modifier.fillMaxWidth(),
                color = if (m >= k.targetLevel - 0.05) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
        }
        Text("Bar = your level now; the second number is the level a data analyst role needs.", style = MaterialTheme.typography.bodySmall)
    }
    Section("Why this order") {
        val e = vm.saved.explain
        when {
            e != null -> { Text(e.text); SourceLine(e.source) }
            vm.working != null -> { if (vm.streaming.isNotEmpty()) Text(vm.streaming); Working(vm.working!!) }
        }
    }
    steps.forEachIndexed { i, step ->
        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${step.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${fmt(step.hours)} h", style = MaterialTheme.typography.labelLarge)
            }
            if (step.summary.isNotEmpty()) Text(step.summary, style = MaterialTheme.typography.bodySmall)
            if (step.openPrereqs.isNotEmpty()) Text("After: ${step.openPrereqs.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            step.resources.forEach { r -> TextButton(onClick = { runCatching { uri.openUri(r.url) } }) { Text("${r.title} (online)") } }
        }
    }
    Button(onClick = { vm.go(Screen.WEEK) }, modifier = Modifier.fillMaxWidth()) { Text("Build my weekly plan") }
}

// ---------- 5. This week ----------
@Composable
private fun WeekScreen(vm: PathfinderViewModel) {
    val st = vm.saved.state ?: return
    val sched = vm.schedule() ?: return
    val week = sched.weeks.firstOrNull()
    if (week == null || week.sessions.isEmpty()) {
        Text("Pathway complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("You reached the target level in every skill by week ${st.week - 1}. Your portfolio project is ready for your CV.")
        OutlinedButton(onClick = { vm.go(Screen.PATHWAY) }) { Text("See my skill levels") }
        OutlinedButton(onClick = vm::startOver) { Text("Start a new goal") }
        return
    }
    Text("This week: week ${st.week}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Week ${st.week}", "of ${st.profile.weeks}", Modifier.weight(1f))
        Stat("Week ${sched.finishWeek}", "Expected finish", Modifier.weight(1f))
        Stat("${fmt(st.profile.hoursPerWeek)} h", "Each week", Modifier.weight(1f))
    }
    if (!sched.onTrack) {
        Section {
            Text("At ${fmt(st.profile.hoursPerWeek)} hours a week you finish in week ${sched.finishWeek}, after your week ${sched.deadlineWeek} deadline.", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { vm.useHours(sched.hoursPerWeekNeeded) }) { Text("Use ${fmt(sched.hoursPerWeekNeeded)} h a week") }
        }
    }
    val parts = week.sessions.groupingBy { it.kind.name + it.skillId }.eachCount()
    val seen = mutableMapOf<String, Int>()
    Section("${fmt(week.hours)} hours planned") {
        Text("Tick what you finish and say how it felt.", style = MaterialTheme.typography.bodySmall)
        for (x in week.sessions) {
            val key = x.kind.name + x.skillId
            val n = (seen[key] ?: 0) + 1; seen[key] = n
            val mark = vm.saved.results[x.id]
            val done = mark?.done == true
            val label = (if (x.kind == Kind.REVIEW) "Review: ${x.name}" else x.name) + if ((parts[key] ?: 1) > 1) " (part $n of ${parts[key]})" else ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = done, onCheckedChange = { vm.mark(x.id, done = it) })
                Column(Modifier.weight(1f)) {
                    Text(label, textDecoration = if (done) TextDecoration.LineThrough else null)
                    Text("${fmt(x.hours)} h", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("easy" to "Easy", "ok" to "OK", "hard" to "Hard").forEach { (v, l) ->
                    FilterChip(selected = (mark?.rating ?: "ok") == v, onClick = { vm.mark(x.id, rating = v) }, label = { Text(l) })
                }
            }
        }
    }
    val upcoming = sched.weeks.drop(1).take(3)
    if (upcoming.isNotEmpty()) Section("Coming up") {
        upcoming.forEach { w ->
            Text("Week ${w.week} · ${fmt(w.hours)} h · " + w.sessions.map { if (it.kind == Kind.REVIEW) "Review ${it.name}" else it.name }.distinct().joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(onClick = vm::checkIn, modifier = Modifier.fillMaxWidth()) { Text("Finish week ${st.week} and check in") }
    OutlinedButton(onClick = { vm.go(Screen.PATHWAY) }, modifier = Modifier.fillMaxWidth()) { Text("Back to pathway") }
}

// ---------- 6. Check-in ----------
@Composable
private fun CheckinScreen(vm: PathfinderViewModel) {
    val c = vm.saved.checkin ?: return
    val m = c.summary
    LaunchedEffect(c.summary) { vm.reflectIfNeeded() }
    Text("Week ${m.week} check-in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    val moved = m.finishWeek - c.prevFinish
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("${fmt(m.doneHours)}/${fmt(m.plannedHours)} h", "Studied", Modifier.weight(1f))
        Stat("${m.completedSkills.size}", "Skills finished", Modifier.weight(1f))
        Stat("Week ${m.finishWeek}", when { moved == 0 -> "Finish unchanged"; moved > 0 -> "Finish ${moved} wk later"; else -> "Finish ${-moved} wk earlier" }, Modifier.weight(1f))
    }
    Section("Your coach") {
        val r = c.reflection
        when {
            r != null -> { Text(r.text); SourceLine(r.source) }
            vm.working != null -> { if (vm.streaming.isNotEmpty()) Text(vm.streaming); Working(vm.working!!) }
        }
    }
    Section("What changed in your plan") {
        if (m.hardSkills.isNotEmpty()) Text("• Extra time added for ${m.hardSkills.joinToString(", ")} because it felt hard.")
        if (m.easySkills.isNotEmpty()) Text("• Less time kept for ${m.easySkills.joinToString(", ")} because it felt easy.")
        if (m.skipped > 0) Text("• ${m.skipped} unfinished session${if (m.skipped > 1) "s" else ""} moved to next week.")
        if (m.completedSkills.isNotEmpty()) Text("• Reviews booked for ${m.completedSkills.joinToString(", ")} next week and in three weeks.")
        Text(if (m.onTrack) "• On track for your week ${m.deadlineWeek} deadline." else "• Behind your week ${m.deadlineWeek} deadline: the plan screen shows how many hours would fix it.")
    }
    Button(onClick = { vm.go(Screen.WEEK) }, modifier = Modifier.fillMaxWidth()) { Text("Start week ${vm.saved.state?.week ?: m.week + 1}") }
    OutlinedButton(onClick = { vm.go(Screen.PATHWAY) }, modifier = Modifier.fillMaxWidth()) { Text("See updated skill levels") }
}
