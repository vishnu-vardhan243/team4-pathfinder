# Team 4: On-device learning assistant

An Android app that turns a learner's goal into a skill-gap analysis, a learning pathway and an adaptive study plan.
All reasoning runs on the phone: a Llama 3.2 model executed by ExecuTorch, no network needed.

**Team 4:** Vishnu Vardhan and Adity Narayan

Plan doc: see "Team 4 Build Plan: On-Device Learning Assistant" (shared Claude doc).

## Layout

| Path | What |
| --- | --- |
| `content/skill-graph.schema.json` | Format every goal domain follows |
| `content/domains/*.json` | One skill graph per goal domain (bundled into the app) |
| `tools/validate_graph.py` | Checks domain files and prints each learning order |
| `export/` | Model export scripts and notes (phase 1) |
| `app/` | Android app (Kotlin + Compose, ExecuTorch 1.5.1). `pathfinder/.../engine/` = learning engine (port of website/js/engine.js), `llm/LlmEngine.kt` = ExecuTorch wrapper, `llm/Coach.kt` = prompts + fallbacks. Tests: `cd app && ./gradlew :pathfinder:testDebugUnitTest` (JDK 21). Open `app/` in Android Studio and set its Gradle JDK to a JDK 21 (newer JDKs are too new for Gradle 8.11.1) |
| `website/` | Showcase page (`index.html`) and a working web version of the app (`app.html`): goal chat, skill quiz, pathway, weekly plan, check-in. Llama 3.2 1B runs in the browser with WebLLM 0.2.86 (WebGPU); without WebGPU it uses built-in rules. Engine tests: `node website/test/engine.test.mjs` |
| `models/` | Local `.pte` and tokenizer files. Git-ignored, never committed |
| `eval/` | Goal-reading evaluation: 20 test learners (`personas.json`), Llama run on the PC with the phone's `.pte` (`eval_llama.py`, in WSL), scoring of rules vs Llama (`node eval/score.mjs` → `results.md`) |
| `docs/technical-report.md` | Technical report: architecture, model, engine, benchmarks, evaluation, limits |
| `docs/logo/` | App logo PNGs (`python tools/make_logo.py`); the launcher icon itself is `app/pathfinder/src/main/res/drawable/ic_launcher_*.xml` |
| `docs/demo-video.md` | Demo video script, shot list and recording steps. `tools/make_demo_video.py` edits the phone recordings (`docs/video/part*.mp4`) into `docs/video/pathfinder-demo.mp4` |

Check content after any edit:

```bash
python3 tools/validate_graph.py content/domains/*.json
```

## Pinned versions

| Component | Version |
| --- | --- |
| ExecuTorch | v1.5.1 (export) + `org.pytorch:executorch-android:1.5.1` (app). v1.6.0 is not on Maven yet, keep both sides equal |
| Model | Llama 3.2 1B Instruct (3B to try) |
| Android NDK | 28.2.13676358 |
| Export machine | WSL2 Ubuntu 22.04, Python 3.10 |

## Phase 1 checklist (gate: on-device tokens/sec recorded)

All model steps run in Linux or WSL from the project root (`cd` into the project folder first):

```bash
hf auth login                        # once, with your own Hugging Face read token
bash export/phase1.sh download 1B    # Meta's original checkpoint into ~/llama-weights
bash export/phase1.sh export 1B      # -> models/llama3_2_1b_8da4w.pte (+ log with peak RAM)
bash export/phase1.sh push 1B        # -> phone: /data/local/tmp/llama/
```

- [x] WSL2 has room to work (moved to E:) and 12 GB RAM
- [x] ExecuTorch cloned at the pinned release and installed in a venv (`~/executorch`, `~/et-venv`, torch 2.14.0+cpu; export pipeline tested with a tiny random model)
- [x] Llama 3.2 1B Instruct weights downloaded (each person accepts Meta's license themselves)
- [x] Exported to `models/llama3_2_1b_8da4w.pte` (XNNPACK, KV cache, 4-bit weights) and tested on the PC
- [ ] `.pte` and tokenizer pushed to the phone with `adb push`
- [x] ExecuTorch Llama demo app built: `demo/executorch-examples/llm/android/LlamaDemo/app/build/outputs/apk/debug/app-debug.apk` (runtime `executorch-android:1.5.1`, JDK 21, Gradle 8.11.1)
- [x] Demo app running the model on the Android emulator (functional check)
- [x] Demo app running the model on a real Arm Android phone: moto g64 5G, model load 0.18 s, 14.4 tokens/s (2026-11-18). **Phase 1 gate met.**
  - The phone needs **64-bit Android** (`adb shell getprop ro.product.cpu.abilist64` must list `arm64-v8a`). ExecuTorch v1.5.1 only builds for arm64-v8a and x86_64.
  - Tried 2026-11-17: Samsung Galaxy M13 (SM-M135FU, Exynos 850, 6 GB) — **not usable**: Samsung ships it with 32-bit-only Android (`armeabi-v7a`, zygote32).
- [x] Benchmarks below filled in (1B; 3B still to try)
- [ ] Skill-graph format agreed and 3–4 domains chosen
- [ ] Resource links in each domain opened and checked by a person

## Phase 5 (gate: code, app, demo video and technical report ready)

- Report: [`docs/technical-report.md`](docs/technical-report.md). Demo script and recording steps: [`docs/demo-video.md`](docs/demo-video.md).
- Goal-reading evaluation on 20 test learners ([`eval/results.md`](eval/results.md)): rules alone 70 % fully right, Llama 3.2 1B alone 0 %, Llama-first merge 30 %, **rules first + Llama + grounding 75 %**. The app and web app now take stated deadlines and hours from the rules and use Llama for known skills and anything the rules miss. 15/15 Kotlin and 11/11 JS tests pass. Checked on the emulator in airplane mode on 2026-11-21: the demo goal and two goals where Llama fills a rules gap were all read right (26 w/8 h/Spreadsheets; 35 w/7 h/Spreadsheets; 26 w/7 h/pandas + Python).
- Real phone, vivo V27 (Dimensity 7200, 8 GB, Android 15), 2026-11-21: the whole app in airplane mode, Llama load 3.2 s, goal 9.3 s, pathway explanation 11.5 s, reflection 10.4 s, 12–14 tok/s; reopened on the same check-in after a force-close. Model on the phone at `/data/local/tmp/llama/` (SHA-256 checked).
- Demo video recorded on the vivo V27 in airplane mode and edited: `docs/video/pathfinder-demo.mp4` (3 min 28 s, not in git; upload with the submission). Home screen and wallpaper cut out; app logo on the title and end cards.
- App logo: a winding path through a skill node to a goal flag. Launcher icon = adaptive vector (`app/pathfinder/src/main/res/drawable/ic_launcher_*.xml`, checked on the vivo V27's round mask); PNGs in `docs/logo/` from `python tools/make_logo.py`.
- Final report: Claude doc "Pathfinder: Final Report (Team 4)"; repository copy `docs/technical-report.md`.
- Still to do: optional voice-over, then publish the repo.

## Phase 3 (gate: onboarding through the weekly plan works on the device with no network) — met 2026-11-21

Full app: model setup, goal chat, skill quiz, pathway, this week, weekly check-in. Progress saved on-device in one JSON file (`data/Store.kt`; Room was planned but a single small state needs no database). 14/14 unit tests (engine + storage).
Offline run on the Pixel 7 emulator, airplane mode on: load Llama 5.9 s → Llama read the goal (15.4 s) → quiz → Llama explained the pathway (19.9 s, 8.4 tok/s) → ticked a session, rated one hard → check-in, Llama reflection (11.3 s) → force-close and reopen: back on the same check-in with the reflection kept.
Fixed during the run: a check-in reflection requested while Llama was busy was dropped; Llama requests now queue.

## Phase 2 (gate: one goal produces a full plan on a device) — met 2026-11-21

- 11/11 Kotlin unit tests pass, including parity with the website engine (same pathway, week 1 and finish week).
- On the Pixel 7 emulator (x86_64, 5 GB): goal "used Python and a bit of SQL… within a year, ~10 h a week" → Llama read 52 weeks / 10 h / SQL + Python (18.9 s), built 12 skills / 129.3 h / finish week 14 of 52, Llama explained the order (29.3 s); whole plan 48.3 s.
- The emulator needs 5 GB RAM (4 GB: Android's low-memory killer stops the app while the weights are prepared) and gets 5-minute Llama time limits; phones keep 60 s.
- Phone timings for the same flow: see Phase 5 (vivo V27).

## Benchmarks

Same prompt for every run: "I know some Excel and want a data analyst job in 6 months. Name the first three skills I should learn, one line each." (greedy, temperature 0).

| Date | Phone (SoC, RAM) | Model | .pte size | Time to first token | Tokens/sec | Peak RAM |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-11-16 | _PC reference, not a phone:_ i5-1335U in WSL2, Python runner | Llama 3.2 1B 8da4w | 822 MB | 6.6 s prefill | 29.6 | 1.6 GB |
| 2026-11-16 | _Emulator, not a phone:_ Pixel 7 AVD, x86_64, 4 vCPU, 4 GB, 16 KB pages (compat mode), LlamaDemo app | Llama 3.2 1B 8da4w | 822 MB | 3.06 s (42-token prompt, 13.7 tok/s prefill) | 3.86 | 1.32 GB RSS |
| 2026-11-18 | **moto g64 5G** (MediaTek Dimensity 7025 / MT6855: 2× Cortex-A78 + 6× A55, 8 GB, Android 15), LlamaDemo app, ExecuTorch 1.5.1 XNNPACK | Llama 3.2 1B 8da4w | 822 MB | 1.34 s (42-token prompt, 31.3 tok/s prefill) | **14.4** (run 2: 13.5) | 1.33 GB RSS |
| | | Llama 3.2 3B 8da4w | | | | |

Emulator run, end to end in the demo app: model load 0.85 s, 138 tokens generated in 35.8 s. The emulator translates nothing to Arm, it runs the x86_64 ExecuTorch build on 4 virtual cores, so its speed says nothing about a phone; it only proves the app + .pte + tokenizer work together.

Web app (`website/app.html`, Llama 3.2 1B q4f16 via WebLLM 0.2.86, i5-1335U integrated GPU, 2026-11-17): first download 664 MB in ~3.5 min (a stalled weight file is handled by the Resume button); start from cache incl. GPU warm-up 31 s; goal reading 16 s; pathway explanation first words 5 s, done 23 s; weekly check-in 15–18 s. Every Llama call falls back to rules after 25 s without output.

Export cost (1B, WSL2, 12 GB RAM limit): 7 min 54 s, peak RAM 8.1 GB. Test prompt: "I know some Excel and want a data analyst job in 6 months. Name the first three skills I should learn, one line each." (greedy, temperature 0).
