// Pathfinder web app: the same five screens as the Android app plan.
// Goal chat -> skill quiz -> pathway -> weekly plan -> check-in. Numbers come from engine.js, words from llm.js.
import * as E from './engine.js';
import * as L from './llm.js';

const KEY = 'pathfinder-app-v1';
const STEPS = [['goal', 'Goal'], ['quiz', 'Quiz'], ['pathway', 'Pathway'], ['plan', 'Plan'], ['checkin', 'Check-in']];
const EXAMPLE_GOAL = 'I know some Excel and want a data analyst job in 6 months. I can study about 8 hours a week.';
const $ = (s) => document.querySelector(s);

function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
}

const fresh = () => ({ screen: 'goal', reached: 0, goalText: '', profile: null, quiz: null, answers: {}, state: null, explain: null, results: {}, lastCheckin: null });
let S = (() => { try { return JSON.parse(localStorage.getItem(KEY)) || fresh(); } catch { return fresh(); } })();
const save = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch { /* private mode: progress lasts for this visit */ } };
let graph = null;

function go(screen) {
  S.screen = screen;
  S.reached = Math.max(S.reached, STEPS.findIndex(([k]) => k === screen));
  save(); render(); window.scrollTo({ top: 0, behavior: 'smooth' });
}

// ---------- Model status bar ----------
const model = { status: L.modelStatus(), pct: 0, text: '', cached: false, error: '', lastChange: 0 };
const STALL_MS = 45000;
// WebLLM does not retry a weight file whose download stalls. Offer a resume: reload and start again;
// files already in the browser cache are reused, only the missing ones download.
setInterval(() => { if (model.status === 'loading') renderModel(); }, 5000);

function sourceTag(src) {
  return src === 'llama' ? h('span', { class: 'source', style: 'color:var(--accent)' }, 'Written by Llama 3.2 · on-device')
    : h('span', { class: 'source', style: 'color:var(--muted)' }, 'Written by built-in rules');
}

function renderModel() {
  const box = $('#model');
  const kids = [];
  if (model.status === 'ready') kids.push(h('span', { class: 'pill good' }, 'Llama 3.2 1B · running in this browser'));
  else if (model.status === 'loading') {
    kids.push(h('span', { class: 'pill accent', title: model.text }, `Loading Llama ${Math.round(model.pct * 100)}%`),
      h('span', { class: 'hint', id: 'model-text', style: 'max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, model.text.split('.')[0] || 'Starting'),
      h('div', { class: 'progress', role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(Math.round(model.pct * 100)), 'aria-label': 'Model download' }, h('i', { style: `width:${Math.round(model.pct * 100)}%` })));
    if (Date.now() - model.lastChange > STALL_MS) {
      kids.push(h('button', { class: 'btn small primary', onclick: () => { try { sessionStorage.setItem('pf-resume', '1'); } catch {} location.reload(); } }, 'Download stalled · Resume'));
    }
  } else if (model.status === 'no-webgpu') kids.push(h('span', { class: 'pill wait', title: 'This browser has no WebGPU. Use Chrome or Edge on a computer to run Llama.' }, 'Rules mode · no WebGPU here'));
  else {
    if (model.error) kids.push(h('span', { class: 'hint', style: 'color:var(--bad)' }, model.error));
    kids.push(h('span', { class: 'pill' }, 'Rules mode'));
    kids.push(h('button', { class: 'btn small primary', onclick: startModel }, model.cached ? 'Start Llama (uses files already downloaded)' : `Load Llama 3.2 1B (${L.MODEL_SIZE_LABEL.split(',')[0]})`));
  }
  box.replaceChildren(...kids);
}

async function startModel() {
  model.status = 'loading'; model.error = ''; model.lastChange = Date.now(); renderModel();
  try {
    await L.loadModel((p, t) => { if (t !== model.text) model.lastChange = Date.now(); model.pct = p; model.text = t; renderModel(); });
    model.status = 'ready';
  } catch (e) {
    model.status = L.modelStatus() === 'no-webgpu' ? 'no-webgpu' : 'not-loaded';
    model.error = String(e.message || e).slice(0, 120);
  }
  renderModel();
}

// ---------- Step bar ----------
function renderSteps() {
  const cur = STEPS.findIndex(([k]) => k === S.screen);
  $('#steps').replaceChildren(...STEPS.map(([k, label], i) => h('li', {},
    h('button', { 'aria-current': i === cur ? 'step' : null, disabled: i > S.reached || (k === 'checkin' && !S.lastCheckin), onclick: () => go(k) }, `${i + 1} ${label}`))));
}

// ---------- 1. Goal chat ----------
function goalScreen() {
  const s = h('section', { class: 'screen' },
    h('h2', {}, 'What do you want to achieve?'),
    h('div', { class: 'bubble bot' }, "Hi, I'm your study coach. Tell me your goal, your deadline and how many hours a week you can study. Mention anything you already know."));
  if (!S.profile) {
    const ta = h('textarea', { id: 'goal-text', rows: 4 });
    ta.value = S.goalText || EXAMPLE_GOAL;
    const send = h('button', { class: 'btn primary', onclick: () => sendGoal(ta.value, s, send) }, 'Send');
    s.append(h('div', { class: 'card' },
      h('label', { for: 'goal-text', class: 'field' }, 'Your goal', ta),
      h('div', { class: 'row' }, h('span', { class: 'hint' }, S.goalText ? '' : 'Example text: replace it with your own goal.'), send)));
    return s;
  }
  const p = S.profile;
  s.append(h('div', { class: 'bubble me' }, S.goalText));
  const weeks = h('input', { type: 'number', id: 'weeks', min: '1', max: '520', value: String(p.weeks) });
  const hours = h('input', { type: 'number', id: 'hours', min: '1', max: '80', step: '0.5', value: String(p.hoursPerWeek) });
  const known = new Set(p.knownSkillIds);
  const chips = graph.skills.map((k) => h('label', { class: 'chip-toggle' },
    h('input', { type: 'checkbox', value: k.id, checked: known.has(k.id), 'aria-label': `I already know ${k.name}` }), h('span', {}, k.name)));
  s.append(h('div', { class: 'bubble bot', style: 'max-width:100%;width:100%' },
    h('div', { style: 'display:grid;gap:14px' },
      h('div', { class: 'row' }, h('strong', {}, "Here's what I understood. Fix anything that's wrong."), sourceTag(p.source)),
      !p.domainMatched ? h('p', { class: 'hint' }, "Pathfinder's first pathway is the data analyst career. I'll use it for this demo; more goals are coming.") : null,
      h('div', { class: 'fields' },
        h('label', { class: 'field', for: 'weeks' }, 'Weeks until your deadline', weeks),
        h('label', { class: 'field', for: 'hours' }, 'Hours you can study each week', hours)),
      h('div', { class: 'field' }, 'Skills you already know', h('div', { class: 'chips' }, chips)),
      h('div', { class: 'row' },
        h('button', { class: 'btn', onclick: () => { S.profile = null; save(); render(); } }, 'Change my goal'),
        h('button', { class: 'btn primary', onclick: () => {
          const ids = chips.map((c) => c.querySelector('input')).filter((i) => i.checked).map((i) => i.value);
          S.profile = { ...p, weeks: Math.max(1, Math.round(+weeks.value || p.weeks)), hoursPerWeek: Math.max(1, +hours.value || p.hoursPerWeek), knownSkillIds: ids };
          S.quiz = E.buildQuiz(graph, S.profile);
          S.answers = Object.fromEntries(S.quiz.filter((q) => q.type === 'self').map((q) => [q.skillId, q.preset]));
          S.state = null; S.explain = null; S.results = {}; S.lastCheckin = null; S.reached = 1;
          go('quiz');
        } }, 'Start the skill quiz')))));
  return s;
}

async function sendGoal(text, screen, btn) {
  text = text.trim();
  if (text.length < 8) return;
  S.goalText = text; btn.disabled = true;
  const thinking = h('div', { class: 'bubble bot thinking' }, model.status === 'ready' ? 'Llama is reading your goal' : 'Reading your goal');
  screen.append(h('div', { class: 'bubble me' }, text), thinking);
  const p = await L.readGoal(text, graph);
  S.profile = { ...p, goalText: text, domainMatched: !!E.matchDomain(text, [graph]) };
  save(); render();
}

// ---------- 2. Quiz ----------
function quizScreen() {
  const items = S.quiz;
  const mcqLeft = () => items.filter((q) => q.type === 'mcq' && S.answers[q.skillId] == null).length;
  const status = h('span', { class: 'hint' });
  const next = h('button', { class: 'btn primary', onclick: finishQuiz }, 'See my skill gaps');
  const refresh = () => { const n = mcqLeft(); status.textContent = n ? `${n} question${n > 1 ? 's' : ''} left` : 'All answered'; next.disabled = n > 0; };
  const sets = items.map((q) => {
    if (q.type === 'mcq') {
      return h('fieldset', {}, h('legend', {}, q.q), h('span', { class: 'skill-tag' }, q.skill),
        h('div', { class: 'opts' }, q.options.map((o, i) => h('label', { class: 'opt' },
          h('input', { type: 'radio', name: q.skillId, value: String(i), checked: S.answers[q.skillId] === i, onchange: () => { S.answers[q.skillId] = i; save(); refresh(); } }), o))));
    }
    return h('fieldset', {}, h('legend', {}, `How well do you know ${q.skill.toLowerCase()}?`), h('span', { class: 'skill-tag' }, q.skill),
      h('div', { class: 'opts seg' }, [['none', 'Not yet'], ['some', 'A little'], ['confident', 'Confident']].map(([v, label]) => h('label', { class: 'opt' },
        h('input', { type: 'radio', name: q.skillId, value: v, checked: (S.answers[q.skillId] ?? q.preset) === v, onchange: () => { S.answers[q.skillId] = v; save(); } }), label))));
  });
  refresh();
  return h('section', { class: 'screen' },
    h('h2', {}, 'Quick skill check'),
    h('p', { class: 'lead' }, 'Four short questions and a self-rating for the rest. It takes about two minutes and sets your starting level for each skill.'),
    ...sets,
    h('div', { class: 'row' }, status, next));
}

function finishQuiz() {
  const mastery = E.scoreQuiz(graph, S.quiz, S.answers, S.profile);
  S.state = E.startState(graph, S.profile, mastery);
  S.explain = null; S.results = {}; S.lastCheckin = null;
  go('pathway');
}

// ---------- 3. Pathway ----------
function pathwayScreen() {
  const st = S.state;
  const steps = E.buildPathway(graph, st.mastery);
  const done = graph.skills.filter((k) => st.finished[k.id] != null).map((k) => k.name);
  const bars = graph.skills.map((k) => {
    const m = st.mastery[k.id] ?? 0;
    return h('div', { class: 'bar-row' },
      h('span', {}, k.name),
      h('div', { class: 'bar', role: 'img', 'aria-label': `${k.name}: now ${Math.round(m * 100)}%, target ${Math.round(k.targetLevel * 100)}%` },
        h('i', { style: `width:${Math.round(m * 100)}%` }), h('b', { style: `left:calc(${Math.round(k.targetLevel * 100)}% - 1px)` })),
      h('span', { class: 'num' }, `${Math.round(m * 100)}% / ${Math.round(k.targetLevel * 100)}%`));
  });
  const explainText = h('p', {}, S.explain?.text || '');
  const explainSrc = h('div', {}, S.explain ? sourceTag(S.explain.source) : h('span', { class: 'hint thinking' }, model.status === 'ready' ? 'Llama is writing' : 'Working it out'));
  if (!S.explain) {
    L.explainPathway(steps, { ...S.profile, goalText: S.goalText }, (t) => { explainText.textContent = t; })
      .then((r) => { S.explain = r; save(); explainText.textContent = r.text; explainSrc.replaceChildren(sourceTag(r.source)); });
  }
  const totalH = Math.round(steps.reduce((a, s) => a + s.hours, 0) * 10) / 10;
  return h('section', { class: 'screen' },
    h('h2', {}, 'Your skill gaps and pathway'),
    h('p', { class: 'lead' }, `Bars show your level now; the dark line is the level a data analyst role needs. ${steps.length} skills to work on, about ${totalH} hours in total.`),
    h('div', { class: 'card' }, h('h3', {}, 'Skill levels'), h('div', { class: 'bars' }, bars)),
    h('div', { class: 'card explain' }, h('h3', {}, 'Why this order'), explainText, explainSrc),
    done.length ? h('p', { class: 'hint' }, `Finished: ${done.join(', ')}`) : null,
    h('ol', { class: 'path' }, steps.map((s) => h('li', {},
      h('div', { style: 'display:grid;gap:4px;min-width:0' },
        h('strong', {}, s.name),
        h('span', { class: 'meta' }, s.summary),
        s.openPrereqs.length ? h('span', { class: 'meta' }, `After: ${s.openPrereqs.join(', ')}`) : null,
        h('span', { class: 'meta' }, s.resources.map((r, i) => [i ? ' · ' : '', h('a', { href: r.url, target: '_blank', rel: 'noopener' }, r.title)]))),
      h('span', { class: 'num' }, `${s.hours} h`)))),
    h('div', { class: 'row' }, h('span'), h('button', { class: 'btn primary', onclick: () => go('plan') }, 'Build my weekly plan')));
}

// ---------- 4. Weekly plan ----------
function planScreen() {
  const st = S.state;
  const sched = E.currentSchedule(graph, st);
  const week = sched.weeks[0];
  if (!week || !week.sessions.length) {
    return h('section', { class: 'screen' }, h('h2', {}, 'Pathway complete'),
      h('p', { class: 'lead' }, `You reached the target level in every skill by week ${st.week - 1}. Your portfolio project is next on your CV.`),
      h('div', { class: 'row' }, h('button', { class: 'btn', onclick: () => go('pathway') }, 'See my skill levels')));
  }
  const stats = h('div', { class: 'stats' },
    h('div', { class: 'stat' }, h('b', {}, `Week ${st.week}`), h('span', {}, `of ${st.profile.weeks}`)),
    h('div', { class: 'stat' }, h('b', {}, `Week ${sched.finishWeek}`), h('span', {}, 'Expected finish')),
    h('div', { class: 'stat' }, h('b', {}, `${st.profile.hoursPerWeek} h`), h('span', {}, 'Study time each week')));
  const warn = !sched.onTrack ? h('div', { class: 'card warn' },
    h('strong', {}, `At ${st.profile.hoursPerWeek} hours a week you finish in week ${sched.finishWeek}, after your week ${sched.deadlineWeek} deadline.`),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, `About ${sched.hoursPerWeekNeeded} hours a week would get you there.`),
      h('button', { class: 'btn small', onclick: () => { st.profile.hoursPerWeek = sched.hoursPerWeekNeeded; S.profile.hoursPerWeek = sched.hoursPerWeekNeeded; save(); render(); } }, `Use ${sched.hoursPerWeekNeeded} h a week`))) : null;
  const byId = new Map(graph.skills.map((k) => [k.id, k]));
  const partsOf = {}, partNo = {};
  for (const s of week.sessions) partsOf[s.kind + s.skillId] = (partsOf[s.kind + s.skillId] || 0) + 1;
  const rows = week.sessions.map((s) => {
    const key = s.kind + s.skillId; partNo[key] = (partNo[key] || 0) + 1;
    const part = partsOf[key] > 1 ? ` (part ${partNo[key]} of ${partsOf[key]})` : '';
    const r = S.results[s.id] || (S.results[s.id] = { done: false, rating: 'ok' });
    const row = h('div', { class: `session${r.done ? ' is-done' : ''}` });
    const cb = h('input', { type: 'checkbox', id: `done-${s.id}`, checked: r.done, onchange: () => { r.done = cb.checked; row.classList.toggle('is-done', r.done); save(); } });
    const res = byId.get(s.skillId)?.resources?.[0];
    row.append(cb,
      h('label', { for: `done-${s.id}`, style: 'display:grid;gap:2px;min-width:0;cursor:pointer' },
        h('span', { class: 's-name' }, (s.kind === 'review' ? `Review: ${s.name}` : s.name) + part),
        h('span', { class: 'hint' }, `${s.hours} h`, res && s.kind === 'learn' ? [' · ', h('a', { href: res.url, target: '_blank', rel: 'noopener' }, res.title)] : ' · quick recap and practice')),
      h('select', { 'aria-label': `How did ${s.name} feel?`, onchange: (e) => { r.rating = e.target.value; save(); } },
        [['easy', 'Felt easy'], ['ok', 'Felt OK'], ['hard', 'Felt hard']].map(([v, l]) => h('option', { value: v, selected: r.rating === v }, l))));
    return row;
  });
  save();
  const upcoming = sched.weeks.slice(1, 4).map((w) => h('li', {}, h('b', {}, `Week ${w.week}`),
    h('span', {}, [...new Set(w.sessions.map((s) => (s.kind === 'review' ? `Review ${s.name}` : s.name)))].join(', ')), h('span', { class: 'num' }, `${w.hours} h`)));
  return h('section', { class: 'screen' },
    h('h2', {}, `This week: week ${st.week}`),
    stats, warn,
    h('div', { class: 'card' }, h('div', { class: 'row' }, h('h3', {}, `${week.hours} hours planned`), h('span', { class: 'hint' }, 'Tick what you finish and say how it felt')), h('div', {}, rows)),
    upcoming.length ? h('div', { class: 'card' }, h('h3', {}, 'Coming up'), h('ul', { class: 'upcoming' }, upcoming)) : null,
    h('div', { class: 'row' }, h('button', { class: 'btn', onclick: () => go('pathway') }, 'Back to pathway'),
      h('button', { class: 'btn primary', onclick: () => checkIn(week, sched) }, `Finish week ${st.week} and check in`)));
}

function checkIn(week, sched) {
  const results = week.sessions.map((session) => ({ session, done: !!S.results[session.id]?.done, rating: S.results[session.id]?.rating || 'ok' }));
  const { state, summary } = E.applyCheckin(graph, S.state, results);
  S.lastCheckin = { summary, prevFinish: sched.finishWeek, text: null, source: null };
  S.state = state; S.results = {}; S.explain = null; S.reached = 4;
  go('checkin');
}

// ---------- 5. Check-in ----------
function checkinScreen() {
  const c = S.lastCheckin;
  if (!c) { go('plan'); return h('div'); }
  const sm = c.summary;
  const text = h('p', {}, c.text || '');
  const src = h('div', {}, c.text ? sourceTag(c.source) : h('span', { class: 'hint thinking' }, model.status === 'ready' ? 'Llama is writing your check-in' : 'Writing your check-in'));
  if (!c.text) L.reflect(sm, (t) => { text.textContent = t; }).then((r) => { c.text = r.text; c.source = r.source; save(); text.textContent = r.text; src.replaceChildren(sourceTag(r.source)); });
  const moved = sm.finishWeek - c.prevFinish;
  return h('section', { class: 'screen' },
    h('h2', {}, `Week ${sm.week} check-in`),
    h('div', { class: 'stats' },
      h('div', { class: 'stat' }, h('b', {}, `${sm.doneHours} / ${sm.plannedHours} h`), h('span', {}, 'Studied this week')),
      h('div', { class: 'stat' }, h('b', {}, String(sm.completedSkills.length)), h('span', {}, sm.completedSkills.length ? `Finished: ${sm.completedSkills.join(', ')}` : 'Skills finished')),
      h('div', { class: 'stat' }, h('b', {}, `Week ${sm.finishWeek}`), h('span', {}, moved === 0 ? 'Finish week unchanged' : moved > 0 ? `Finish moved ${moved} week${moved > 1 ? 's' : ''} later` : `Finish moved ${-moved} week${moved < -1 ? 's' : ''} earlier`))),
    h('div', { class: 'card explain' }, h('h3', {}, 'Your coach'), text, src),
    h('div', { class: 'card' }, h('h3', {}, 'What changed in your plan'), h('ul', {},
      sm.hardSkills.length ? h('li', {}, `Extra time added for ${sm.hardSkills.join(', ')} because it felt hard.`) : null,
      sm.easySkills.length ? h('li', {}, `Less time kept for ${sm.easySkills.join(', ')} because it felt easy.`) : null,
      sm.skipped ? h('li', {}, `${sm.skipped} unfinished session${sm.skipped > 1 ? 's' : ''} moved to next week.`) : null,
      sm.completedSkills.length ? h('li', {}, `Reviews booked for ${sm.completedSkills.join(', ')} next week and in three weeks.`) : null,
      h('li', {}, sm.onTrack ? `On track for your week ${sm.deadlineWeek} deadline.` : `Behind your week ${sm.deadlineWeek} deadline: the plan screen suggests how many hours would fix it.`))),
    h('div', { class: 'row' }, h('button', { class: 'btn', onclick: () => go('pathway') }, 'See updated skill levels'),
      h('button', { class: 'btn primary', onclick: () => go('plan') }, `Start week ${S.state.week}`)));
}

// ---------- Shell ----------
const SCREENS = { goal: goalScreen, quiz: quizScreen, pathway: pathwayScreen, plan: planScreen, checkin: checkinScreen };

function render() {
  if ((S.screen !== 'goal' && !S.profile) || (['pathway', 'plan', 'checkin'].includes(S.screen) && !S.state) || (S.screen === 'quiz' && !S.quiz)) S.screen = 'goal';
  renderSteps();
  const main = $('#main');
  main.replaceChildren(SCREENS[S.screen]());
  let armed = false;
  const reset = h('button', { class: 'btn small', onclick: () => {
    if (!armed) { armed = true; reset.textContent = 'Click again to erase your plan'; return; }
    S = fresh(); save(); render();
  } }, 'Start over');
  main.append(h('div', { class: 'row', style: 'margin-top:28px' }, h('span', { class: 'hint' }, 'Your progress is saved in this browser only.'), reset));
}

async function init() {
  try {
    graph = await (await fetch('data/data-analyst.json')).json();
  } catch {
    $('#main').replaceChildren(h('p', {}, 'The skill data could not load. Open this page through the website, not as a file.'));
    return;
  }
  renderModel(); render();
  let resume = false;
  try { resume = sessionStorage.getItem('pf-resume') === '1'; sessionStorage.removeItem('pf-resume'); } catch {}
  if (model.status === 'not-loaded' && resume) { startModel(); return; }
  if (model.status === 'not-loaded') { model.cached = await L.isCached(); renderModel(); }
}
init();
