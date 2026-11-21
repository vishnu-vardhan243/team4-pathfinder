// Goal-reading accuracy on 20 personas: built-in rules vs Llama 3.2 1B (raw and as the app uses it).
// Run from the project root:  node eval/score.mjs        (Llama replies come from eval/llama_raw.json, made by eval_llama.py)
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import * as E from '../website/js/engine.js';

const root = new URL('../', import.meta.url);
const graph = JSON.parse(readFileSync(new URL('content/domains/data-analyst.json', root), 'utf8'));
const personas = JSON.parse(readFileSync(new URL('eval/personas.json', root), 'utf8'));
const rawPath = new URL('eval/llama_raw.json', root);
const raw = existsSync(rawPath) ? JSON.parse(readFileSync(rawPath, 'utf8')) : null;
const round1 = (x) => Math.round(x * 10) / 10;

/**
 * Post-processing of Llama's reply, as in app/.../llm/Coach.kt readGoal and website/js/llm.js.
 * mode 'raw': Llama's answer as is (only invalid values fall back to rules).
 * mode 'llamaFirst': + grounding; Llama's numbers win (the app before the evaluation).
 * mode 'rulesFirst': + grounding; numbers stated in the text come from the rules, Llama fills the rest (the app now).
 */
function fromLlama(goal, reply, mode) {
  const { profile: rules, found } = E.readGoalRules(goal, graph);
  const grounded = mode !== 'raw';
  const m = reply.match(/\{[\s\S]*?\}/);
  if (!m) return { ...rules, source: 'rules (no JSON)' };
  let j; try { j = JSON.parse(m[0]); } catch { return { ...rules, source: 'rules (bad JSON)' }; }
  const rulesFirst = mode === 'rulesFirst';
  const weeks = rulesFirst && found.weeks ? rules.weeks : Number.isInteger(j.weeks) && j.weeks >= 1 && j.weeks <= 520 ? j.weeks : rules.weeks;
  const hours = rulesFirst && found.hours ? rules.hoursPerWeek : typeof j.hoursPerWeek === 'number' && j.hoursPerWeek > 0 && j.hoursPerWeek <= 80 ? round1(j.hoursPerWeek) : rules.hoursPerWeek;
  const mentioned = new Set(E.mentionedSkillIds(goal, graph));
  const claimed = Array.isArray(j.knownSkills) ? j.knownSkills.filter((k) => typeof k === 'string').map((k) => k.toLowerCase()) : [];
  const known = graph.skills.filter((s) => (!grounded || mentioned.has(s.id)) && claimed.includes(s.name.toLowerCase())).map((s) => s.id);
  return { weeks, hoursPerWeek: hours, knownSkillIds: grounded ? [...new Set([...known, ...rules.knownSkillIds])] : known, source: 'llama' };
}

function score(get) {
  let w = 0, h = 0, k = 0, tp = 0, fp = 0, fn = 0, all = 0;
  const rows = personas.map((p) => {
    const r = get(p);
    const ew = p.expect.weeks, eh = p.expect.hoursPerWeek, ek = new Set(p.expect.known), rk = new Set(r.knownSkillIds);
    const wOk = Math.abs(r.weeks - ew) <= Math.max(1, ew * 0.05);
    const hOk = Math.abs(r.hoursPerWeek - eh) <= 0.5;
    const kOk = ek.size === rk.size && [...ek].every((x) => rk.has(x));
    for (const x of rk) ek.has(x) ? tp++ : fp++;
    for (const x of ek) if (!rk.has(x)) fn++;
    w += wOk; h += hOk; k += kOk; all += wOk && hOk && kOk;
    return { id: p.id, weeks: r.weeks, hours: r.hoursPerWeek, known: [...rk], wOk, hOk, kOk, source: r.source || 'rules' };
  });
  const n = personas.length, pct = (x) => Math.round((x / n) * 100);
  return { rows, weeks: pct(w), hours: pct(h), known: pct(k), allFields: pct(all), precision: tp + fp ? Math.round((tp / (tp + fp)) * 100) : 100, recall: tp + fn ? Math.round((tp / (tp + fn)) * 100) : 100 };
}

const results = { rules: score((p) => E.parseGoalRules(p.goal, graph)) };
if (raw) {
  const byId = new Map(raw.replies.map((r) => [r.id, r]));
  results.llamaRaw = score((p) => fromLlama(p.goal, byId.get(p.id).reply, 'raw'));
  results.llamaFirst = score((p) => fromLlama(p.goal, byId.get(p.id).reply, 'llamaFirst'));
  results.rulesFirst = score((p) => fromLlama(p.goal, byId.get(p.id).reply, 'rulesFirst'));
  const secs = raw.replies.map((r) => r.secs);
  results.llamaTiming = { meanSecs: round1(secs.reduce((a, b) => a + b, 0) / secs.length), maxSecs: round1(Math.max(...secs)), machine: raw.machine };
}

const line = (name, s) => `| ${name} | ${s.weeks}% | ${s.hours}% | ${s.known}% (P ${s.precision}% / R ${s.recall}%) | **${s.allFields}%** |`;
const md = [
  '| Reader | Deadline | Hours a week | Known skills | All three right |', '| --- | --- | --- | --- | --- |',
  line('Built-in rules', results.rules),
  ...(raw ? [
    line('Llama 3.2 1B, raw', results.llamaRaw),
    line('Llama first + grounding (app before the evaluation)', results.llamaFirst),
    line('Rules first, Llama fills gaps + grounding (app now)', results.rulesFirst),
  ] : []),
].join('\n');
console.log(md);
if (raw) console.log(`\nLlama time per goal on ${results.llamaTiming.machine}: mean ${results.llamaTiming.meanSecs} s, max ${results.llamaTiming.maxSecs} s`);
for (const [name, s] of Object.entries(results)) if (s.rows) {
  const misses = s.rows.filter((r) => !(r.wOk && r.hOk && r.kOk)).map((r) => `#${r.id}${r.wOk ? '' : ' weeks=' + r.weeks}${r.hOk ? '' : ' hours=' + r.hours}${r.kOk ? '' : ' known=[' + r.known + ']'}`);
  console.log(`\n${name} misses: ${misses.join('; ') || 'none'}`);
}
writeFileSync(new URL('eval/results.json', root), JSON.stringify(results, null, 2));
writeFileSync(new URL('eval/results.md', root), md + '\n');
