// Llama 3.2 1B Instruct running in the visitor's browser (WebLLM on WebGPU).
// Everything here has a rule-based fallback in engine.js, so the app works without the model.
import { readGoalRules, explainPathwayRules, reflectionRules, mentionedSkillIds } from './engine.js';

const WEBLLM_URL = 'https://cdn.jsdelivr.net/npm/@mlc-ai/web-llm@0.2.86/+esm';
export const MODEL_ID = 'Llama-3.2-1B-Instruct-q4f16_1-MLC';
export const MODEL_SIZE_LABEL = 'about 0.9 GB, downloaded once and kept in this browser';

let engine = null;
let lib = null;

export function modelStatus() {
  if (engine) return 'ready';
  if (typeof navigator === 'undefined' || !('gpu' in navigator)) return 'no-webgpu';
  return 'not-loaded';
}

async function webllm() {
  if (!lib) lib = await import(WEBLLM_URL);
  return lib;
}

/** True when the weights are already in this browser's cache (no download needed). */
export async function isCached() {
  // Look in WebLLM's Cache Storage directly, so a first visit does not download the 6.8 MB library just to check.
  try {
    if (typeof caches === 'undefined') return false;
    if (!(await caches.has('webllm/model'))) return false;
    const keys = await (await caches.open('webllm/model')).keys();
    return keys.some((r) => r.url.includes(MODEL_ID));
  } catch { return false; }
}

/** Download (or load from cache) and start the model. onProgress(fraction 0-1, text). */
export async function loadModel(onProgress = () => {}) {
  if (engine) return engine;
  if (modelStatus() === 'no-webgpu') throw new Error('This browser has no WebGPU, so the model cannot run here. Use Chrome or Edge on a computer.');
  const adapter = await navigator.gpu.requestAdapter();
  if (!adapter) throw new Error('WebGPU is switched off or unsupported on this device.');
  const l = await webllm();
  const e = await l.CreateMLCEngine(MODEL_ID, { initProgressCallback: (r) => onProgress(r.progress ?? 0, r.text ?? '') });
  // Warm-up: the first generation compiles GPU programs and can take 20 s+. Do it now, not on the learner's first question.
  onProgress(1, 'Warming up Llama on your GPU');
  await e.chat.completions.create({ messages: [{ role: 'user', content: 'Hi' }], max_tokens: 1, temperature: 0 });
  engine = e;
  return engine;
}

const FIRST_TOKEN_MS = 25000;
const TOTAL_MS = 60000;

// Streams a reply. Gives up (and stops the GPU work) if nothing arrives in 25 s or the whole reply takes over 60 s,
// so the app falls back to its rule-based text instead of waiting forever.
async function complete(messages, { maxTokens = 220, temperature = 0.4, onToken } = {}) {
  let text = '', timer, first = true;
  const work = (async () => {
    const stream = await engine.chat.completions.create({ messages, max_tokens: maxTokens, temperature, stream: true });
    for await (const chunk of stream) {
      const d = chunk.choices?.[0]?.delta?.content || '';
      if (d) {
        if (first) { first = false; clearTimeout(timer); timer = setTimeout(() => stop('slow'), TOTAL_MS); }
        text += d; onToken?.(text);
      }
    }
    return text.trim();
  })();
  let stop;
  const guard = new Promise((_, reject) => {
    stop = (why) => { try { engine.interruptGenerate(); } catch { /* already finished */ } reject(new Error(`Llama ${why === 'slow' ? 'took too long' : 'did not start'}`)); };
    timer = setTimeout(() => stop('no-start'), FIRST_TOKEN_MS);
  });
  try { return await Promise.race([work, guard]); } finally { clearTimeout(timer); }
}

const firstJson = (s) => { const m = s.match(/\{[\s\S]*?\}/); if (!m) throw new Error('no JSON in reply'); return JSON.parse(m[0]); };

const COACH = 'You are Pathfinder, a friendly study coach inside a learning app. Talk to the learner as "you" and never about yourself. No greeting, heading or preamble: start with the first sentence. Be specific and brief. Never invent links, courses or numbers that are not given to you.';

// Small models still sometimes open with "Here's your check-in:". Drop such a first line.
const tidy = (s) => s.replace(/^\s*(?:here(?:'s| is)[^\n]*:|[^\n]{0,60}:)\s*\n+/i, '').trim();

/**
 * Read a free-text goal into { weeks, hoursPerWeek, knownSkillIds, source }.
 * Deadline and hours stated in the text come from the rules; Llama fills in what the rules did not find
 * and adds known skills. Every Llama value is validated; known skills must be named in the goal.
 */
export async function readGoal(text, graph) {
  const { profile: rules, found } = readGoalRules(text, graph);
  if (!engine) return { ...rules, source: 'rules' };
  const names = graph.skills.map((s) => s.name);
  try {
    const raw = await complete([
      { role: 'system', content: 'Extract facts from a learner\'s goal. Reply with JSON only: {"weeks": integer or null, "hoursPerWeek": number or null, "knownSkills": [skill names from the list that the learner says they already know]}. Use null when the text does not say.' },
      { role: 'user', content: 'Skills: Spreadsheets, SQL basics, Python basics\nGoal: I use Excel at work and want to switch careers within a year, maybe 5 hours a week' },
      { role: 'assistant', content: '{"weeks": 52, "hoursPerWeek": 5, "knownSkills": ["Spreadsheets"]}' },
      { role: 'user', content: `Skills: ${names.join(', ')}\nGoal: ${text}` },
    ], { maxTokens: 80, temperature: 0 });
    const j = firstJson(raw);
    const weeks = found.weeks ? rules.weeks : Number.isInteger(j.weeks) && j.weeks > 0 && j.weeks <= 520 ? j.weeks : rules.weeks;
    const hours = found.hours ? rules.hoursPerWeek : typeof j.hoursPerWeek === 'number' && j.hoursPerWeek > 0 && j.hoursPerWeek <= 80 ? Math.round(j.hoursPerWeek * 10) / 10 : rules.hoursPerWeek;
    // A 1B model sometimes copies the whole skill list back. Only accept skills the goal actually mentions.
    const mentioned = new Set(mentionedSkillIds(text, graph));
    const known = Array.isArray(j.knownSkills)
      ? graph.skills.filter((s) => mentioned.has(s.id) && j.knownSkills.some((k) => typeof k === 'string' && k.toLowerCase() === s.name.toLowerCase())).map((s) => s.id)
      : [];
    return { weeks, hoursPerWeek: hours, knownSkillIds: [...new Set([...known, ...rules.knownSkillIds])], source: 'llama' };
  } catch {
    return { ...rules, source: 'rules' };
  }
}

/** Short explanation of the pathway order, streamed. Falls back to the rule text. */
export async function explainPathway(steps, profile, onToken) {
  const fallback = explainPathwayRules(steps, profile);
  if (!engine || !steps.length) { onToken?.(fallback); return { text: fallback, source: 'rules' }; }
  const facts = steps.map((s, i) => `${i + 1}. ${s.name}: current level ${Math.round(s.mastery * 100)}%, target ${Math.round(s.target * 100)}%, ${s.hours} h${s.openPrereqs.length ? `, needs ${s.openPrereqs.join(' and ')} first` : ''}`).join('\n');
  try {
    const text = await complete([
      { role: 'system', content: COACH },
      { role: 'user', content: `Learner goal: ${profile.goalText}\nThey can study ${profile.hoursPerWeek} hours a week for ${profile.weeks} weeks.\nTheir pathway, already in the right order:\n${facts}\n\nIn 3 short sentences, explain why the first two steps come first and what the learner gains by the end. Address them as "you".` },
    ], { maxTokens: 160, onToken: (t) => onToken?.(tidy(t)) });
    return text ? { text: tidy(text), source: 'llama' } : { text: fallback, source: 'rules' };
  } catch {
    onToken?.(fallback); return { text: fallback, source: 'rules' };
  }
}

/** Weekly reflection from the check-in summary, streamed. Falls back to the rule text. */
export async function reflect(summary, onToken) {
  const fallback = reflectionRules(summary);
  if (!engine) { onToken?.(fallback); return { text: fallback, source: 'rules' }; }
  const facts = [
    `Week ${summary.week}: studied ${summary.doneHours} of ${summary.plannedHours} planned hours.`,
    summary.completedSkills.length ? `Finished: ${summary.completedSkills.join(', ')}.` : 'No skill finished this week.',
    summary.hardSkills.length ? `Rated hard: ${summary.hardSkills.join(', ')} (extra time was added).` : '',
    summary.easySkills.length ? `Rated easy: ${summary.easySkills.join(', ')}.` : '',
    `Next week: ${summary.nextFocus.join(', ') || 'reviews only'}.`,
    summary.onTrack ? 'On track for the deadline.' : `Behind: finishing in week ${summary.finishWeek}, deadline is week ${summary.deadlineWeek}.`,
  ].filter(Boolean).join('\n');
  try {
    const text = await complete([
      { role: 'system', content: COACH },
      { role: 'user', content: `The learner's week:\n${facts}\n\nWrite their weekly check-in in exactly 3 short sentences, addressed to them as "you": how the week went, what changed in their plan, one concrete tip for next week. Use only these facts.` },
    ], { maxTokens: 150, onToken: (t) => onToken?.(tidy(t)) });
    return text ? { text: tidy(text), source: 'llama' } : { text: fallback, source: 'rules' };
  } catch {
    onToken?.(fallback); return { text: fallback, source: 'rules' };
  }
}
