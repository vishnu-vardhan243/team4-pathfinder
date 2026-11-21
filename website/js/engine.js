// Pathfinder learning engine: the deterministic half of the app.
// Llama writes words; this module does every number: skill levels, gaps, order and schedule.
// Pure functions over a skill graph (content/domains/*.json format). No DOM, no network.

export const SELF_LEVEL = { none: 0, some: 0.4, confident: 0.75 };
const MCQ_RIGHT = 0.8;
const MCQ_WRONG = 0.25;
const SESSION_MAX_H = 1.5;
const REVIEW_H = 0.5;
const RATING_ADJUST = { easy: -0.15, ok: 0, hard: 0.25 }; // share of the session's hours added to / removed from what remains

const round1 = (x) => Math.round(x * 10) / 10;
const clamp01 = (x) => Math.max(0, Math.min(1, x));

// Words in a goal that mean "I already know this", per skill.
const SKILL_WORDS = {
  spreadsheets: ['excel', 'spreadsheet', 'spreadsheets', 'google sheets'],
  'descriptive-stats': ['statistics', 'stats'],
  'sql-basics': ['sql'],
  'python-basics': ['python'],
  pandas: ['pandas'],
  visualization: ['visualization', 'visualisation', 'charts', 'matplotlib'],
  'bi-dashboards': ['power bi', 'tableau', 'dashboards', 'dashboard'],
  'intro-ml': ['machine learning'],
};
const KNOW_CUES = /\b(know|knows|knew|familiar|experience|experienced|used|use|comfortable|good at|done|basic|basics|some)\b/;

/** Score how well a free-text goal matches each domain; return the best graph or null. */
export function matchDomain(goalText, graphs) {
  const text = goalText.toLowerCase();
  let best = null, bestScore = 0;
  for (const g of graphs) {
    const score = g.goalKeywords.reduce((s, k) => s + (text.includes(k.toLowerCase()) ? k.split(' ').length : 0), 0);
    if (score > bestScore) { best = g; bestScore = score; }
  }
  return best;
}

const WORD_NUM = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, 'half a': 0.5, half: 0.5 };
const NUM = '(\\d+(?:\\.\\d+)?|half an?|half|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)';
const num = (s) => (/^\d/.test(s) ? parseFloat(s) : WORD_NUM[s.replace(/half an?/, 'half a')] ?? WORD_NUM[s]);

/** Skills the goal text names at all (by keyword or full name). Used to keep Llama's answer grounded. */
export function mentionedSkillIds(text, graph) {
  const t = text.toLowerCase();
  return graph.skills.filter((s) => (SKILL_WORDS[s.id] || []).some((w) => t.includes(w)) || t.includes(s.name.toLowerCase())).map((s) => s.id);
}

/** Rule-based goal reading: deadline in weeks, hours per week, skills the learner says they know. */
export function parseGoalRules(text, graph) {
  return readGoalRules(text, graph).profile;
}

/**
 * Rule-based goal reading, plus which values were stated in the text (found) and which are defaults
 * (26 weeks, 6 h a week). Llama only fills in values the rules did not find: the evaluation
 * (eval/results.md) showed the 1B model often turns "6 months" into 6 weeks.
 */
export function readGoalRules(text, graph) {
  const t = text.toLowerCase();
  // Rates like "10 hours a week" are not deadlines: drop them before looking for one.
  const d = t
    .replace(/\b(h|hrs?|hours?|mins?|minutes?|times?|sessions?)\s+(?:a|an)\s+(?:week|day|month|year)\b/g, '$1')
    .replace(/\b(?:per|each|every)\s+(?:week|day|month|year)\b/g, ' ');
  let weeks = 26;
  const mo = d.match(new RegExp(`\\b${NUM}\\s*(months?|mos?)\\b`));
  const wk = d.match(new RegExp(`\\b${NUM}\\s*(weeks?|wks?)\\b`));
  const yr = d.match(new RegExp(`\\b${NUM}\\s*(years?|yrs?)\\b`));
  if (mo) weeks = Math.round(num(mo[1]) * 4.345);
  else if (wk) weeks = Math.round(num(wk[1]));
  else if (yr) weeks = Math.round(num(yr[1]) * 52);

  let hoursPerWeek = 6;
  const hw = t.match(new RegExp(`\\b${NUM}\\s*(?:h|hrs?|hours?)\\s*(?:a|per|each|\\/)\\s*week`));
  const hd = t.match(new RegExp(`\\b${NUM}\\s*(?:h|hrs?|hours?)\\s*(?:a|per|each|\\/)\\s*day`));
  if (hw) hoursPerWeek = num(hw[1]);
  else if (hd) hoursPerWeek = num(hd[1]) * 7;

  const clauses = t.split(/[.,;!?]|\bbut\b|\band (?:i )?want\b/);
  const ids = new Set(graph.skills.map((s) => s.id));
  const known = [];
  for (const [id, words] of Object.entries(SKILL_WORDS)) {
    if (!ids.has(id)) continue;
    if (clauses.some((c) => KNOW_CUES.test(c) && words.some((w) => c.includes(w)))) known.push(id);
  }
  return {
    profile: { weeks: Math.max(1, weeks), hoursPerWeek: Math.max(1, round1(hoursPerWeek)), knownSkillIds: known },
    found: { weeks: !!(mo || wk || yr), hours: !!(hw || hd) },
  };
}

/** Diagnostic: a question for skills that have one, a self-rating for the rest. */
export function buildQuiz(graph, profile) {
  const known = new Set(profile.knownSkillIds || []);
  const items = [];
  for (const s of graph.skills) {
    if (s.quiz && s.quiz.length) {
      const q = s.quiz[0];
      items.push({ type: 'mcq', skillId: s.id, skill: s.name, q: q.q, options: q.options, answer: q.answer });
    } else {
      items.push({ type: 'self', skillId: s.id, skill: s.name, preset: known.has(s.id) ? 'some' : 'none' });
    }
  }
  return items;
}

/** Turn quiz answers into a 0-1 mastery level per skill. answers: { [skillId]: optionIndex | 'none'|'some'|'confident' } */
export function scoreQuiz(graph, items, answers, profile = {}) {
  const known = new Set(profile.knownSkillIds || []);
  const mastery = {};
  for (const it of items) {
    const a = answers[it.skillId];
    if (it.type === 'mcq') {
      let m = a === it.answer ? MCQ_RIGHT : MCQ_WRONG;
      if (known.has(it.skillId)) m = Math.max(m, SELF_LEVEL.some);
      mastery[it.skillId] = m;
    } else {
      mastery[it.skillId] = SELF_LEVEL[a ?? it.preset] ?? 0;
    }
  }
  for (const s of graph.skills) if (!(s.id in mastery)) mastery[s.id] = 0;
  return mastery;
}

/** Prerequisite order of the whole graph (Kahn's algorithm, ties keep the file's order). */
export function topoOrder(graph) {
  const index = new Map(graph.skills.map((s, i) => [s.id, i]));
  const indeg = new Map(graph.skills.map((s) => [s.id, s.prereqs.length]));
  const children = new Map(graph.skills.map((s) => [s.id, []]));
  for (const s of graph.skills) for (const p of s.prereqs) children.get(p).push(s.id);
  const ready = graph.skills.filter((s) => s.prereqs.length === 0).map((s) => s.id);
  const out = [];
  while (ready.length) {
    ready.sort((a, b) => index.get(a) - index.get(b));
    const id = ready.shift();
    out.push(id);
    for (const c of children.get(id)) { indeg.set(c, indeg.get(c) - 1); if (indeg.get(c) === 0) ready.push(c); }
  }
  if (out.length !== graph.skills.length) throw new Error('Skill graph has a prerequisite cycle');
  return out;
}

/** Skills below their target, in prerequisite order, with the study hours each still needs. */
export function buildPathway(graph, mastery) {
  const byId = new Map(graph.skills.map((s) => [s.id, s]));
  const steps = [];
  for (const id of topoOrder(graph)) {
    const s = byId.get(id);
    const m = clamp01(mastery[id] ?? 0);
    const gap = s.targetLevel - m;
    if (gap <= 0.05) continue;
    steps.push({
      skillId: id, name: s.name, summary: s.summary || '', mastery: m, target: s.targetLevel,
      gap: round1(gap * 100) / 100, hours: Math.max(0.5, round1(s.estHours * gap / s.targetLevel)),
      resources: s.resources, openPrereqs: s.prereqs.filter((p) => (byId.get(p).targetLevel - (mastery[p] ?? 0)) > 0.05).map((p) => byId.get(p).name),
    });
  }
  return steps;
}

/**
 * Lay remaining study hours into weekly sessions.
 * steps: [{skillId, name, hours}] in learning order; finished: { [skillId]: weekFinished } for spaced reviews.
 */
export function buildSchedule(steps, { hoursPerWeek, weeks, startWeek = 1, finished = {}, names = {} }) {
  const queue = steps.filter((s) => s.hours > 0.04).map((s) => ({ ...s, left: s.hours }));
  const reviewsDue = new Map(); // week -> [{skillId,name}]
  const due = (w, item) => { if (!reviewsDue.has(w)) reviewsDue.set(w, []); reviewsDue.get(w).push(item); };
  for (const [skillId, w] of Object.entries(finished)) {
    // Finished skills are no longer in `steps`, so look their names up in the graph's name map.
    const step = steps.find((s) => s.skillId === skillId);
    const name = names[skillId] || (step ? step.name : skillId);
    for (const d of [1, 3]) if (w + d >= startWeek) due(w + d, { skillId, name });
  }
  const plan = [];
  let week = startWeek, guard = 0, id = 0;
  while ((queue.length || [...reviewsDue.keys()].some((k) => k >= week)) && guard++ < 520) {
    let cap = hoursPerWeek;
    const sessions = [];
    for (const r of (reviewsDue.get(week) || []).slice(0, 2)) {
      if (cap < REVIEW_H) break;
      sessions.push({ id: `w${week}-${id++}`, week, skillId: r.skillId, name: r.name, hours: REVIEW_H, kind: 'review' });
      cap = round1(cap - REVIEW_H);
    }
    while (queue.length && cap >= 0.25) {
      const cur = queue[0];
      const h = round1(Math.min(SESSION_MAX_H, cur.left, cap));
      sessions.push({ id: `w${week}-${id++}`, week, skillId: cur.skillId, name: cur.name, hours: h, kind: 'learn' });
      cur.left = round1(cur.left - h); cap = round1(cap - h);
      if (cur.left <= 0.04) { queue.shift(); for (const d of [1, 3]) due(week + d, { skillId: cur.skillId, name: cur.name }); }
    }
    plan.push({ week, sessions, hours: round1(sessions.reduce((a, s) => a + s.hours, 0)) });
    week++;
    if (!queue.length && ![...reviewsDue.keys()].some((k) => k >= week)) break;
  }
  const learnHours = round1(steps.reduce((a, s) => a + (s.hours > 0.04 ? s.hours : 0), 0));
  const lastWeek = startWeek + weeks - 1;
  const learnWeeks = plan.filter((w) => w.sessions.some((s) => s.kind === 'learn')).map((w) => w.week);
  const finishWeek = learnWeeks.length ? Math.max(...learnWeeks) : startWeek - 1;
  const weeksLeft = Math.max(1, lastWeek - startWeek + 1);
  return {
    weeks: plan, learnHours, finishWeek, deadlineWeek: lastWeek,
    onTrack: finishWeek <= lastWeek,
    hoursPerWeekNeeded: round1(Math.ceil((learnHours / weeksLeft) * 1.15 * 10) / 10),
  };
}

/** Start a learner's state from their profile and quiz result. */
export function startState(graph, profile, mastery) {
  const pathway = buildPathway(graph, mastery);
  const remaining = Object.fromEntries(pathway.map((p) => [p.skillId, p.hours]));
  return {
    domainId: graph.id, profile, startMastery: { ...mastery }, mastery: { ...mastery },
    needed: { ...remaining }, remaining, finished: {}, week: 1, history: [],
  };
}

/** Steps still to study, in order, using what remains for each skill. */
export function currentSteps(graph, state) {
  const byId = new Map(graph.skills.map((s) => [s.id, s]));
  return topoOrder(graph)
    .filter((id) => (state.remaining[id] ?? 0) > 0.04)
    .map((id) => ({ skillId: id, name: byId.get(id).name, hours: state.remaining[id] }));
}

export function currentSchedule(graph, state) {
  const weeksLeft = state.profile.weeks - (state.week - 1);
  return buildSchedule(currentSteps(graph, state), {
    hoursPerWeek: state.profile.hoursPerWeek, weeks: Math.max(1, weeksLeft), startWeek: state.week, finished: state.finished,
    names: Object.fromEntries(graph.skills.map((s) => [s.id, s.name])),
  });
}

/**
 * Weekly check-in. results: [{ session, done: boolean, rating: 'easy'|'ok'|'hard' }]
 * Returns { state, summary } — a new state (input untouched) and facts for the reflection.
 */
export function applyCheckin(graph, state, results) {
  const s = structuredClone(state);
  const byId = new Map(graph.skills.map((k) => [k.id, k]));
  const summary = { week: state.week, plannedHours: 0, doneHours: 0, completedSkills: [], hardSkills: [], easySkills: [], skipped: 0 };
  for (const { session, done, rating } of results) {
    summary.plannedHours += session.hours;
    if (!done) { summary.skipped++; continue; }
    summary.doneHours += session.hours;
    if (session.kind !== 'learn') continue;
    const id = session.skillId;
    const adj = RATING_ADJUST[rating] ?? 0;
    s.remaining[id] = round1(Math.max(0, (s.remaining[id] ?? 0) - session.hours + adj * session.hours));
    if (rating === 'hard') summary.hardSkills.push(session.name);
    if (rating === 'easy') summary.easySkills.push(session.name);
  }
  for (const [id, left] of Object.entries(s.remaining)) {
    const k = byId.get(id); const need = s.needed[id] || 1; const start = s.startMastery[id] ?? 0;
    if (left <= 0.04) {
      if (!(id in s.finished)) { s.finished[id] = state.week; summary.completedSkills.push(k.name); }
      s.remaining[id] = 0; s.mastery[id] = k.targetLevel;
    } else {
      s.mastery[id] = clamp01(start + (k.targetLevel - start) * Math.max(0, 1 - left / need));
    }
  }
  summary.plannedHours = round1(summary.plannedHours); summary.doneHours = round1(summary.doneHours);
  summary.hardSkills = [...new Set(summary.hardSkills)]; summary.easySkills = [...new Set(summary.easySkills)];
  s.history.push({ week: state.week, ...summary });
  s.week = state.week + 1;
  const next = currentSchedule(graph, s);
  summary.onTrack = next.onTrack;
  summary.finishWeek = next.finishWeek;
  summary.deadlineWeek = next.deadlineWeek;
  summary.nextFocus = next.weeks[0]?.sessions.filter((x) => x.kind === 'learn').map((x) => x.name).filter((v, i, a) => a.indexOf(v) === i) ?? [];
  return { state: s, summary };
}

// ---- Text the app shows when Llama is not loaded (rule-based fallbacks) ----

export function explainPathwayRules(steps, profile) {
  if (!steps.length) return 'Your quiz shows you are already at the target level for every skill in this pathway.';
  const first = steps[0], hours = round1(steps.reduce((a, s) => a + s.hours, 0));
  const parts = [`Start with ${first.name}: it has no open prerequisites and everything after builds on it.`];
  const blocked = steps.find((s) => s.openPrereqs.length);
  if (blocked) parts.push(`${blocked.name} comes later because it needs ${blocked.openPrereqs.join(' and ')} first.`);
  parts.push(`In total you need about ${hours} hours, which is ${Math.ceil(hours / profile.hoursPerWeek)} weeks at ${profile.hoursPerWeek} hours a week.`);
  return parts.join(' ');
}

export function reflectionRules(summary) {
  const out = [];
  out.push(summary.doneHours >= summary.plannedHours * 0.8
    ? `Good week: you studied ${summary.doneHours} of ${summary.plannedHours} planned hours.`
    : `You studied ${summary.doneHours} of ${summary.plannedHours} planned hours, so unfinished sessions move to next week.`);
  if (summary.completedSkills.length) out.push(`You finished ${summary.completedSkills.join(', ')}; short reviews are booked for next week and in three weeks.`);
  if (summary.hardSkills.length) out.push(`${summary.hardSkills.join(', ')} felt hard, so I added extra time for it.`);
  if (summary.nextFocus?.length) out.push(`Next week focuses on ${summary.nextFocus.join(' and ')}.`);
  out.push(summary.onTrack ? 'You are on track for your deadline.' : `At this pace you finish in week ${summary.finishWeek}, after your week ${summary.deadlineWeek} deadline. Add a few hours a week or extend the deadline.`);
  return out.join(' ');
}
