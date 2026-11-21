# Pathfinder: an on-device personalised learning assistant

**Team 4 · Technical report · November 2026**

Pathfinder is an Android app that turns a learner's goal, written in their own words, into a skill-gap analysis, an ordered learning pathway and a weekly study plan. The plan adapts after each weekly check-in. All of it runs on the phone: Meta's Llama 3.2 1B Instruct, executed by ExecuTorch on the Arm CPU, with no server and no network connection.

## 1. Results at a glance

| What | Result |
| --- | --- |
| Model on a real Arm phone | Llama 3.2 1B Instruct, 4-bit weights, 822 MB `.pte`. On a moto g64 5G (MediaTek Dimensity 7025, 8 GB): **14.4 tokens/s**, first token in 1.34 s, 1.33 GB memory |
| Model in the app on a second phone | vivo V27 (MediaTek Dimensity 7200, 8 GB): **12–14 tokens/s**, Llama loads in 3.2 s; each Llama step answers in 9–12 s |
| Full app, offline | Goal → quiz → pathway → weekly plan → check-in → restart, in airplane mode, on the vivo V27 and on the Pixel 7 emulator (§6, §7.3) |
| Goal reading accuracy (20 test learners) | **75 %** of goals read fully right (deadline, hours and known skills), against 70 % for rules alone and 0 % for Llama alone (§7.1) |
| Tests | 15 Kotlin unit tests (engine + storage), 11 JavaScript engine tests, all passing |
| Demo video | `docs/video/pathfinder-demo.mp4`: 3 min 28 s, recorded live on the vivo V27 in airplane mode, not sped up; script and recording steps in `docs/demo-video.md` |
| App logo | A winding path through a skill node to a goal flag: adaptive vector launcher icon, PNGs in `docs/logo/` |
| Also delivered | A web version of the same app (`website/app.html`) running Llama 3.2 1B in the browser with WebGPU |

## 2. What the app does

1. **Goal.** The learner types something like _"I know some Excel and want a data analyst job in 6 months. I can study about 8 hours a week."_ Llama extracts the deadline (26 weeks), the weekly time (8 h) and the skills they already have (Spreadsheets). The learner sees these values and can edit them.
2. **Skill check.** A short quiz: multiple-choice questions for four core skills, and a quick self-rating (none / some / confident) for the rest.
3. **Skill gaps and pathway.** The app compares each skill's current level with its target level, keeps the skills with a gap, puts them in prerequisite order and estimates the hours each needs. Llama explains the order in three sentences.
4. **Weekly plan.** The hours are split into 90-minute sessions that fit the learner's weekly time, with 30-minute reviews one and three weeks after each skill is studied. The app shows whether the learner will finish before the deadline.
5. **Weekly check-in.** The learner ticks the sessions they did and rates them easy, ok or hard. The app updates the remaining hours and skill levels and rebuilds the plan. Llama writes a three-sentence reflection on the week.
6. **Saved on the phone.** Progress lives in one JSON file in the app's private storage. Force-closing the app and reopening it returns to the same screen.

## 3. Architecture

```
 Jetpack Compose screens (ui/Screens.kt)
              │
 PathfinderViewModel ── Store (data/Store.kt: one JSON file, written atomically)
      │            │
      │            └── Coach (llm/Coach.kt): prompts, answer checks, fallback text
      │                     │
      │               LlmEngine (llm/LlmEngine.kt): ExecuTorch LlmModule, chat template,
      │                     │                        streaming, time limit, one call at a time
      │               llama3_2_1b_8da4w.pte + tokenizer.model  (/data/local/tmp/llama/)
      │
 Engine (engine/Engine.kt): quiz scoring, gaps, pathway, schedule, check-in. Pure Kotlin, no Android.
      │
 Skill graph (content/domains/data-analyst.json, bundled as an asset)
```

**Design decision: Llama handles language, a deterministic engine handles numbers.** A 1B model is good at reading free text and writing short, friendly explanations, but it is not reliable at arithmetic, ordering twelve skills by their prerequisites or keeping a schedule consistent from week to week. So:

| Task | Done by | Why |
| --- | --- | --- |
| Read the goal into deadline, hours a week, known skills | Rules for numbers the text states; **Llama** for known skills and anything the rules miss | Measured: Llama alone misreads deadlines (§7.1) |
| Score the quiz, compute gaps and hours | Engine | Must be exact and repeatable |
| Order skills by prerequisites | Engine (topological sort) | A graph problem with one right answer |
| Build the weekly sessions and reviews | Engine | Arithmetic over hours and weeks |
| Explain why the pathway is in this order | **Llama** | Natural language |
| Update the plan after a check-in | Engine | Must be consistent with past weeks |
| Write the weekly reflection | **Llama** | Natural language, personal tone |

Every Llama task has a rule-based answer behind it. If the model is not loaded, takes too long or replies with something unusable, the app shows the rule-based result and keeps going. The app is never blocked by the model.

The same engine exists twice: in Kotlin for the app and in JavaScript for the web version. A unit test (`EngineTest`) checks that both give the same pathway, the same first week and the same finish week for the same learner.

## 4. Model and on-device runtime

| Item | Choice |
| --- | --- |
| Model | Llama 3.2 1B Instruct (Meta's original checkpoint, used under the Llama 3.2 Community License) |
| Runtime | ExecuTorch 1.5.1: Python exporter, and `org.pytorch:executorch-android:1.5.1` in the app |
| Backend | XNNPACK (optimised Arm CPU kernels) |
| Quantisation | `8da4w`: 4-bit weights in groups of 32, 8-bit dynamic activations; 4-bit embeddings |
| Context | KV cache, 2048 tokens (the v1.5.1 default of 128 is too short for our prompts) |
| File size | 822 MB (`models/llama3_2_1b_8da4w.pte`) |
| Export cost | 7 min 54 s and 8.1 GB peak RAM on a laptop (WSL2) |
| Decoding | Greedy (temperature 0), so the same input gives the same output |

The export is scripted: `export/phase1.sh` downloads the checkpoint (with retries and a SHA-256 check), exports with `export/llama3_2_xnnpack_8da4w.yaml` and pushes the files to the phone.

`LlmEngine` wraps ExecuTorch's `LlmModule`. It builds the Llama 3 chat template (`<|begin_of_text|>`, `<|start_header_id|>role<|end_header_id|>`, `<|eot_id|>`), streams tokens to the screen and runs one request at a time; requests made while the model is busy wait in a queue. A watchdog stops a generation that runs over its limit: 60 s on a phone, 300 s on the emulator, which is several times slower.

### Prompts

**Goal reading** (80 output tokens at most). The system prompt asks for JSON only, with one worked example:

> _System:_ Extract facts from a learner's goal. Reply with JSON only: {"weeks": integer or null, "hoursPerWeek": number or null, "knownSkills": [skill names from the list that the learner says they already know]}. Use null when the text does not say.
> _User:_ Skills: Spreadsheets, SQL basics, Python basics / Goal: I use Excel at work and want to switch careers within a year, maybe 5 hours a week
> _Assistant:_ {"weeks": 52, "hoursPerWeek": 5, "knownSkills": ["Spreadsheets"]}
> _User:_ Skills: _all 12 skill names_ / Goal: _the learner's text_

The app then combines the answer with its rules:
- **Rules first for numbers.** If the goal states a deadline ("in 6 months") or a weekly time ("2 hours a day"), the rule-based value is used. Llama's value is used only for what the rules did not find, and only if it is a whole number of weeks from 1 to 520, or hours from 0 to 80. This order came out of the evaluation (§7.1): Llama 3.2 1B often answers "6 months" with `"weeks": 6`.
- **Known skills** are the union of the rules' list and Llama's list.
- **Grounding:** a skill Llama lists as known is kept only if the goal text actually mentions it, by name or by a keyword such as "Excel" or "pandas". Without this check the 1B model often copied the whole skill list into `knownSkills`.

**Pathway explanation and weekly reflection** use a shared system prompt ("You are Pathfinder, a friendly study coach… No greeting, heading or preamble… Never invent links, courses or numbers that are not given to you"). They receive only facts the engine computed: the ordered steps with levels and hours, or the week's sessions, ratings and plan changes. A small `tidy()` step removes the "Here's your check-in:" type of first line that small models like to add.

## 5. The learning engine

**Skill graph.** One goal domain is included: _data analyst_, 12 skills totalling 152 hours. Each skill has a target level (0.4 to 0.85), estimated hours, prerequisites, free resources and, for four skills, a multiple-choice question. The format is defined by `content/skill-graph.schema.json` and checked by `tools/validate_graph.py`, so adding a domain means writing one JSON file.

**Skill levels.** A correct quiz answer sets the level to 0.8 and a wrong one to 0.25. Self-ratings map to 0 / 0.4 / 0.75. A skill the learner said they know is never set below 0.4.

**Gaps and hours.** gap = target − level. hours = estimated hours × gap / target, with at least 0.5 h. Skills already at their target are left out.

**Pathway.** Kahn's topological sort over the prerequisites. Ties keep the file order, so the result is stable.

**Schedule.** Each week is filled up to the learner's weekly hours with sessions of at most 1.5 h, following the pathway. Each skill gets 0.5 h reviews one and three weeks after it was studied. The plan reports the finish week and whether it is before the deadline.

**Check-in.** For each session done, the remaining hours of that skill go down by the session length. "Hard" adds back 25 % of the session and "easy" removes 15 % more. Levels move toward the target in proportion to the hours done. A skill with no hours left is marked finished at its target level. Then the schedule is rebuilt from the new week.

## 6. Performance

### Raw model speed

| Device | Time to first token | Generation | Memory |
| --- | --- | --- | --- |
| **moto g64 5G** (Dimensity 7025: 2× Cortex-A78 + 6× A55, 8 GB, Android 15), ExecuTorch demo app | **1.34 s** (42-token prompt, 31.3 tok/s prefill) | **14.4 tok/s** (second run 13.5) | 1.33 GB |
| **vivo V27** (Dimensity 7200: 2× Cortex-A715 + 6× A510, 8 GB, Android 15), inside Pathfinder | 2.7–6.3 s (the three app prompts) | **12.0–13.8 tok/s** | 1.32 GB (PSS, Llama loaded) |
| Laptop reference: i5-1335U, WSL2, Python runner | 6.6 s prefill | 29.6 tok/s | 1.6 GB |
| Pixel 7 emulator (x86_64, 4 vCPU), functional check only | 3.06 s | 3.9 tok/s | 1.32 GB |

The emulator runs the x86_64 build of ExecuTorch on virtual cores, so its speed says nothing about a phone. It was used to test the app end to end.

### The app, step by step (offline)

Measured inside Pathfinder; the phone numbers are from its log (`adb logcat -s Pathfinder`). "First token" includes reading the whole prompt (prefill), which is why it is longer for the pathway step: its prompt lists all 12 skills.

| Step | **vivo V27** (Dimensity 7200: 2× Cortex-A715 + 6× A510, 8 GB, Android 15), 2026-11-21 | Pixel 7 emulator (x86_64, 5 GB) |
| --- | --- | --- |
| Load Llama | **3.2 s** | 5.9 s |
| Llama reads the goal | **9.3 s** (first token 3.8 s, 76 tokens at 13.8 tok/s) | 15.4 s |
| Llama explains the pathway | **11.5 s** (first token 6.3 s, 62 tokens at 12.0 tok/s) | 19.9 s (8.4 tok/s) |
| Llama writes the weekly reflection | **10.4 s** (first token 2.7 s, 103 tokens at 13.4 tok/s) | 11.3 s |

On the phone every Llama step finishes in about 10 s, and the explanation and reflection stream onto the screen as they are written, so the learner reads the first words after 3–6 s. The speed inside the app (12–14 tok/s) is close to the moto g64's 14.4 tok/s in the ExecuTorch demo app. With Llama loaded the app used 1.32 GB on the V27 (PSS, `dumpsys meminfo`), in line with the 1.33 GB measured on the moto g64.

### Web version (Llama 3.2 1B q4f16 via WebLLM 0.2.86, laptop integrated GPU)

One-time download 664 MB (about 3.5 min). Start from cache, including GPU warm-up: 31 s. Goal reading 16 s; pathway explanation 5 s to first words and 23 s in total; weekly check-in 15–18 s.

## 7. Evaluation

### 7.1 Goal reading on 20 test learners

`eval/personas.json` holds 20 goals written to cover different phrasings: months, weeks and years, word numbers ("two years", "five hours"), daily hours ("2 hours a day" = 14 a week), no deadline (26-week default), negation ("never used SQL"), job titles ("Python developer") and tools that are not skill names ("Google Sheets", "Tableau", "matplotlib"). Each goal has the expected deadline in weeks, hours a week and known skills.

A deadline counts as right within ±5 % (at least ±1 week). Hours count as right within ±0.5 h. Known skills must match the expected set exactly; precision and recall are also reported. "Llama + app checks" is what the app shows: Llama's answer after range checks and grounding, falling back to rules field by field.

| Reader | Deadline | Hours a week | Known skills (precision / recall) | All three right |
| --- | --- | --- | --- | --- |
| Rules only | 100 % | 90 % | 80 % (95 % / 88 %) | 70 % |
| Llama 3.2 1B alone | 40 % | 85 % | 0 % (9 % / 83 %) | 0 % |
| Llama first, with grounding (the app before this evaluation) | 40 % | 85 % | 85 % (86 % / 100 %) | 30 % |
| **Rules first, Llama fills gaps, with grounding (the app now)** | **95 %** | **95 %** | **85 % (86 % / 100 %)** | **75 %** |

Llama ran on a laptop CPU with the same `.pte` file and the exact prompt the phone uses: 12.1 s per goal on average (the first call, 55 s, includes warm-up). Raw replies are in `eval/llama_raw.json`, and per-goal results in `eval/results.json`.

**What we learned.**

- **Llama 3.2 1B is not reliable with numbers.** It often copies the number without converting the unit: "in 6 months" → 6 weeks, "9 months" → 9, "2 hours a day" → 2 hours a week. For "no deadline" it made one up (15 weeks).
- **Llama alone also over-claims skills.** In 19 of 20 goals it listed 8 or more of the 12 skills as already known. It got 0 goals fully right on its own.
- **Grounding fixes the skills.** Keeping only skills the goal names turns Llama's skill list from useless (9 % precision) into the best one (100 % recall, against 88 % for rules).
- **Llama does add value once the rules handle the numbers.** It catches skills the rules miss:
  - "Marketing analyst using Google Sheets daily" → Spreadsheets
  - "I study statistics" → Descriptive statistics
  - "I'm a Python developer" → Python basics
  - "7 hours weekly", which the rules do not parse
- **The order of the merge matters most.** Our first version let Llama's numbers win: 30 % of goals fully right, worse than the rules alone. Rules first raised it to 75 %. Without this evaluation the app would have shipped the worse order: the demo goal ("6 months") would have shown a 6-week plan.
- **Errors that remain** (5 of 20 goals):
  - two learners who say they _want to learn_ a skill got it marked as known ("learn Python", "learn SQL and Power BI")
  - "never used SQL" counted as knowing SQL
  - "2 hours every day" read as 2 h a week
  - "no deadline" got Llama's 15 weeks instead of the 26-week default

  Every value is shown to the learner on the goal screen and can be edited before the quiz, and the quiz then corrects the skill levels.

The 20 goals were written by the team, so the numbers show the direction, not a precise accuracy. The rules were written before the evaluation and were not tuned to these goals.

Reproduce: run `python eval/eval_llama.py <project root>` inside the ExecuTorch venv (it runs the same `.pte` the phone uses with the app's exact prompt), then `node eval/score.mjs`.

### 7.2 Engine correctness

15 Kotlin unit tests cover goal reading by rules, grounding, prerequisite order, quiz scoring, the pathway, the schedule (weekly hours, reviews, deadline check), the check-in update, parity with the JavaScript engine, and saving and loading progress (an unreadable file starts fresh instead of crashing). The JavaScript engine has 11 tests of its own.

### 7.3 End-to-end runs

- **Phase 2:** one goal produced a full plan on the emulator. Llama read 52 weeks / 10 h / SQL + Python, the engine built 12 steps (129.3 h, finishing in week 14 of 52) and Llama explained the order. The whole plan took 48.3 s.
- **Rules-first goal reading on the device** (emulator, airplane mode, after the change in §7.1). Three goals went through the app with Llama loaded, and all three were read fully right:

  | Goal | App result | Where each value came from | Llama time |
  | --- | --- | --- | --- |
  | Excel, 6 months, 8 h a week (the demo goal) | 26 weeks, 8 h, Spreadsheets | Numbers from rules; before the change Llama's "6 weeks" would have won | 19.6 s |
  | Google Sheets daily, 8 months, 1 hour a day | 35 weeks, 7 h, Spreadsheets | Numbers from rules; Spreadsheets from Llama (the rules miss "Google Sheets") | 35.0 s |
  | pandas and Python, half a year, 7 hours weekly | 26 weeks, 7 h, pandas + Python basics | Deadline from rules; hours from Llama (the rules miss "7 hours weekly") | 12.6 s |

- **Real phone, vivo V27, 2026-11-21.** The whole app in airplane mode with the demo goal typed in: Llama loaded (3.2 s) → read the goal as 26 weeks / 8 h / Spreadsheets → quiz → pathway with Llama's explanation → two sessions ticked, one rated hard → check-in (1.5 of 8 h done, finish moved from week 14 to 15, still on track for week 26) with Llama's reflection → app force-closed and reopened on the same check-in, reflection kept. No crash. (This vivo keeps Wi-Fi on in airplane mode; the app has no `INTERNET` permission, so it could not use it.)
- **Phase 3:** the whole app in airplane mode, from loading the model to a weekly check-in, then a forced restart. The app returned to the check-in with the reflection kept.

### 7.4 What we have not measured yet

- The quality of the pathway explanations and reflections. They were read by the team but not scored. One weak example from the phone run: the reflection was friendly but generic, repeated itself, guessed at feelings ("you're feeling confident") and gave no concrete tip, although the prompt asks for one. The facts shown next to it (hours studied, extra time for the hard skill, sessions moved, on track) come from the engine and were right.
- Whether learners find the plans useful: there was no user study.

## 8. Privacy and offline use

- The app does not even request Android's `INTERNET` permission (see `AndroidManifest.xml`), so it cannot send anything anywhere. Llama, the engine and the skill graph are all on the phone.
- The learner's goal, answers and progress stay in the app's private storage. There is no account and no analytics. Cloud backup and phone-to-phone transfer are switched off (`allowBackup="false"` plus `res/xml/data_extraction_rules.xml` for Android 12+).
- Resource links in the pathway are labelled "(online)"; opening one is the only time the learner leaves the app.

## 9. Limitations and next steps

| Limitation | Next step |
| --- | --- |
| One goal domain (data analyst) | The schema and validator are ready: add domains such as web development, IELTS or school maths as JSON files, and pick the domain from the goal text |
| The 1B model misreads numbers and over-claims skills (§7.1); the app relies on rules for stated numbers | Try Llama 3.2 3B on phones with 8 GB+; more worked examples in the prompt; a constrained JSON decoder |
| The model is not bundled in the app (822 MB, pushed with `adb`) | Download it on first start with a resume and checksum check, or use Play Asset Delivery |
| Weekly reflections from the 1B model can be generic (§7.4) | Give the prompt one worked example; check the reply names at least one skill from the week and fall back to the rule-based text if not |
| Resource links were not checked by a person | Check every link before release |
| No user study | Five learners for two weeks: plan completion, check-in rate and a short survey |

## 10. How to build and run

Everything is in the repository; the `README.md` has the full steps.

1. Export the model (WSL, once): `bash export/phase1.sh download 1B`, then `export 1B` and `push 1B`. Each person accepts Meta's Llama license on Hugging Face first.
2. Build and install the app with JDK 21: `cd app && ./gradlew :pathfinder:installDebug`.
3. Open Pathfinder, tap **Load Llama**, and describe your goal.
4. Tests: `./gradlew :pathfinder:testDebugUnitTest` and `node website/test/engine.test.mjs`.
5. Web version: serve `website/` with any static server and open `app.html` in a browser with WebGPU.
6. Logo and demo video: `python tools/make_logo.py`, then `python tools/make_demo_video.py` (needs ffmpeg 7+ and the phone recordings in `docs/video/`).
