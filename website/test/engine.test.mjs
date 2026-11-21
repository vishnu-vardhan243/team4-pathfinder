// Run: node website/test/engine.test.mjs   (from the project root)
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as E from '../js/engine.js';

const graph = JSON.parse(readFileSync(new URL('../data/data-analyst.json', import.meta.url), 'utf8'));
const byId = new Map(graph.skills.map((s) => [s.id, s]));
let passed = 0;
const test = (name, fn) => { fn(); passed++; console.log('ok  ', name); };

test('matches the data analyst domain from a goal', () => {
  assert.equal(E.matchDomain('I want a data analyst job', [graph])?.id, 'data-analyst');
  assert.equal(E.matchDomain('I want to bake bread', [graph]), null);
});

test('reads deadline, hours and known skills from a goal', () => {
  const p = E.parseGoalRules('I know some Excel and want a data analyst job in 6 months, about 8 hours a week', graph);
  assert.deepEqual(p, { weeks: 26, hoursPerWeek: 8, knownSkillIds: ['spreadsheets'] });
  const q = E.parseGoalRules('I want to learn SQL in 3 months, 1 hour a day', graph);
  assert.equal(q.weeks, 13); assert.equal(q.hoursPerWeek, 7); assert.deepEqual(q.knownSkillIds, []);
  const r = E.parseGoalRules('Become an analyst', graph);
  assert.equal(r.weeks, 26); assert.equal(r.hoursPerWeek, 6);
  const s = E.parseGoalRules("I've used Python and a bit of SQL at uni. I want to become a data analyst within a year, around 10 hours a week.", graph);
  assert.deepEqual(s, { weeks: 52, hoursPerWeek: 10, knownSkillIds: ['sql-basics', 'python-basics'] });
  assert.equal(E.parseGoalRules('in two months, five hours a week', graph).weeks, 9);
  assert.equal(E.parseGoalRules('within half a year', graph).weeks, 26);
  assert.equal(E.parseGoalRules('ready in 3 weeks, 2 hours per day', graph).weeks, 3);
});

test('rules report which values the goal stated (Llama fills only the rest)', () => {
  assert.deepEqual(E.readGoalRules('Data analyst in 6 months, 8 hours a week', graph).found, { weeks: true, hours: true });
  const d = E.readGoalRules('Become an analyst, no rush', graph);
  assert.deepEqual(d.found, { weeks: false, hours: false });
  assert.deepEqual(d.profile, { weeks: 26, hoursPerWeek: 6, knownSkillIds: [] });
});

test('only skills named in the goal count as mentioned (grounds Llama)', () => {
  assert.deepEqual(E.mentionedSkillIds('I use Excel and some Power BI', graph), ['spreadsheets', 'bi-dashboards']);
  assert.deepEqual(E.mentionedSkillIds('I want a data analyst job', graph), []);
});

test('topological order puts every prerequisite first', () => {
  const order = E.topoOrder(graph);
  assert.equal(order.length, graph.skills.length);
  for (const s of graph.skills) for (const p of s.prereqs) assert.ok(order.indexOf(p) < order.indexOf(s.id), `${p} before ${s.id}`);
});

// A learner who knows Excel, gets the SQL question right and the others wrong.
const profile = { weeks: 26, hoursPerWeek: 8, knownSkillIds: ['spreadsheets'] };
const items = E.buildQuiz(graph, profile);
const answers = {};
for (const it of items) answers[it.skillId] = it.type === 'mcq' ? (it.skillId === 'sql-basics' ? it.answer : (it.answer + 1) % it.options.length) : it.preset;
const mastery = E.scoreQuiz(graph, items, answers, profile);

test('quiz builds one item per skill and scores 0-1', () => {
  assert.equal(items.length, graph.skills.length);
  assert.equal(items.filter((i) => i.type === 'mcq').length, 4);
  for (const v of Object.values(mastery)) assert.ok(v >= 0 && v <= 1);
  assert.equal(mastery['sql-basics'], 0.8);
  assert.equal(mastery['spreadsheets'], 0.4); // wrong answer, but says they know Excel
});

const pathway = E.buildPathway(graph, mastery);
test('pathway skips mastered skills and keeps prerequisite order', () => {
  assert.ok(!pathway.find((p) => p.skillId === 'sql-basics'), 'SQL basics at 0.8 is within 0.05 of its 0.85 target');
  const ids = pathway.map((p) => p.skillId);
  for (const id of ids) for (const pre of byId.get(id).prereqs) if (ids.includes(pre)) assert.ok(ids.indexOf(pre) < ids.indexOf(id));
  assert.deepEqual(pathway[0].openPrereqs, []);
  for (const p of pathway) assert.ok(p.hours > 0 && p.hours <= byId.get(p.skillId).estHours);
});

let state = E.startState(graph, profile, mastery);
const plan = E.currentSchedule(graph, state);
test('schedule respects weekly hours and covers every pathway hour', () => {
  for (const w of plan.weeks) assert.ok(w.hours <= profile.hoursPerWeek + 1e-9, `week ${w.week} has ${w.hours} h`);
  const learned = plan.weeks.flatMap((w) => w.sessions).filter((s) => s.kind === 'learn');
  const total = Math.round(learned.reduce((a, s) => a + s.hours, 0) * 10) / 10;
  assert.equal(total, plan.learnHours);
  assert.equal(plan.learnHours, Math.round(pathway.reduce((a, p) => a + p.hours, 0) * 10) / 10);
  for (const s of learned) assert.ok(s.hours <= 1.5);
});

test('check-in: done sessions reduce remaining hours, hard adds time, week advances', () => {
  const week1 = plan.weeks[0].sessions;
  const results = week1.map((session, i) => ({ session, done: true, rating: i === 0 ? 'hard' : 'ok' }));
  const before = { ...state.remaining };
  const { state: s2, summary } = E.applyCheckin(graph, state, results);
  assert.equal(s2.week, 2); assert.equal(state.week, 1, 'input state untouched');
  assert.equal(summary.doneHours, plan.weeks[0].hours);
  const first = week1[0];
  const spent = week1.filter((s) => s.skillId === first.skillId).reduce((a, s) => a + s.hours, 0);
  const expectFirst = Math.max(0, before[first.skillId] - spent + 0.25 * first.hours);
  assert.ok(Math.abs(s2.remaining[first.skillId] - expectFirst) <= 0.1 + 1e-9, `${s2.remaining[first.skillId]} vs ${expectFirst}`);
  assert.ok(summary.hardSkills.includes(first.name));
  state = s2;
});

test('finished skills get spaced reviews and reach their target level', () => {
  let s = state, guard = 0;
  while (!Object.keys(s.finished).length && guard++ < 20) {
    const wk = E.currentSchedule(graph, s).weeks[0];
    s = E.applyCheckin(graph, s, wk.sessions.map((session) => ({ session, done: true, rating: 'ok' }))).state;
  }
  const [doneId, weekDone] = Object.entries(s.finished)[0];
  assert.equal(s.mastery[doneId], byId.get(doneId).targetLevel);
  const next = E.currentSchedule(graph, s).weeks.find((w) => w.week === weekDone + 1);
  const review = next?.sessions.find((x) => x.kind === 'review' && x.skillId === doneId);
  assert.ok(review, 'review booked next week');
  assert.equal(review.name, byId.get(doneId).name, 'review shows the skill name, not its id');
});

test('deadline check flags a plan that cannot fit', () => {
  const tight = E.buildSchedule(pathway, { hoursPerWeek: 2, weeks: 4 });
  assert.equal(tight.onTrack, false);
  assert.ok(tight.hoursPerWeekNeeded > 2);
  assert.ok(E.reflectionRules({ doneHours: 2, plannedHours: 2, completedSkills: [], hardSkills: [], nextFocus: ['SQL basics'], onTrack: false, finishWeek: 30, deadlineWeek: 4 }).includes('week 30'));
});

console.log(`\n${passed} tests passed`);
console.log('Sample pathway:', pathway.map((p) => `${p.name} ${p.hours}h`).join(' > '));
console.log('Week 1:', plan.weeks[0].sessions.map((s) => `${s.name} ${s.hours}h`).join(', '), `| finish week ${plan.finishWeek} of ${plan.deadlineWeek}`);
console.log('Why this order (rules):', E.explainPathwayRules(pathway, profile));
