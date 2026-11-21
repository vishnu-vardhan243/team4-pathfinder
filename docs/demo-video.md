# Demo video: script and recording steps

**Recorded 2026-11-21** on the vivo V27 (airplane mode, Wi-Fi and Bluetooth off), driven over adb at a readable pace and captured with `screenrecord` in four parts. Edited with `python tools/make_demo_video.py` into `docs/video/pathfinder-demo.mp4` (1920×1080, 3 min 28 s): phone screen on the left, captions on the right, the app logo, title and end card. Nothing is sped up. Where `screenrecord` wrote no frames because the screen was still, the last recorded frame is held. The phone's home screen and quick-settings panel are cut because they show the owner's wallpaper; airplane mode is shown instead by an enlarged inset of the app's own status bar. Llama times in this recording: goal 10.4 s, pathway explanation 12.6 s, reflection 11.8 s. The raw parts and the edit stay out of git (`docs/video/`); upload the final video with the submission.

Still worth adding by hand: a voice-over (the shot list below has the lines) and 5 seconds of the phone in someone's hand with the airplane icon visible.

Target length: about 3 minutes. Everything is recorded on the vivo V27 in **airplane mode with Wi-Fi off**, so the video itself proves nothing leaves the phone.
Be honest in the edit: if a Llama wait is sped up, put "sped up 2×" on screen.

## Before recording

1. Phone: vivo V27, charged above 50 %, Do Not Disturb on, screen timeout 10 min, default font size.
2. Check the model is on the phone (copied and checked on 2026-11-21):

```bash
adb shell ls -l /data/local/tmp/llama/
```

   It must list `llama3_2_1b_8da4w.pte` (822 MB) and `tokenizer.model`. If not, run `bash export/phase1.sh push 1B` from WSL.
3. Install a fresh build of the app:

```bash
cd app && ./gradlew :pathfinder:installDebug
```

4. Clear old progress so onboarding starts from the beginning:

```bash
adb shell pm clear com.team4.pathfinder
```

5. Turn on airplane mode, **then turn Wi-Fi off by hand**: this vivo keeps Wi-Fi on in airplane mode. Bluetooth off too. The status bar should show only the airplane icon.
6. Do one rehearsal run, then clear again (step 4). The first Llama load after install is slower.

## Recording

`screenrecord` stops after 3 minutes per file, so record one file per part and join them in the editor.

```bash
adb shell screenrecord --bit-rate 8000000 /sdcard/demo1.mp4
```

Stop each part with Ctrl+C, then copy the file to the PC:

```bash
adb pull /sdcard/demo1.mp4 docs/video/
```

Also film the phone in your hand for 5 seconds with a second phone, with the airplane icon visible. It makes "on-device" believable in a way a screen recording cannot.

In a second terminal, keep the app's timing log open during the run. The report uses these numbers:

```bash
adb logcat -s Pathfinder
```

## Shot list

The learner in the demo is persona 1 from `eval/personas.json`, so the numbers on screen match the evaluation.

| Time | Screen | What to do | Voice-over |
| --- | --- | --- | --- |
| 0:00–0:15 | Phone in hand, quick settings | Pull down quick settings and show airplane mode on | "This is a personal learning coach that runs entirely on this phone. Airplane mode is on: nothing goes to a server." |
| 0:15–0:30 | Setup | Tap **Load Llama** and wait for the ready message | "It runs Meta's Llama 3.2 1B, compressed to 822 MB with ExecuTorch, on the phone's Arm CPU." |
| 0:30–1:00 | Goal | Type _I know some Excel and want a data analyst job in 6 months. I can study about 8 hours a week._ and tap **Send**. Wait for the profile (26 weeks, 8 h a week, Spreadsheets), then tap **Start the skill quiz** | "I describe my goal in my own words. Llama turns it into facts the app can plan with: a deadline, hours a week, and what I already know." |
| 1:00–1:25 | Quick skill check | Answer the 4 questions (get one wrong on purpose), tap **See my skill gaps** | "A short quiz checks what I really know, so the plan starts at my level, not at zero." |
| 1:25–1:55 | Skill gaps and pathway | Scroll the ordered skills; let Llama's explanation finish; tap **Build my weekly plan** | "The app finds my skill gaps and orders them so each skill comes after the ones it builds on. Llama explains the order in plain words." |
| 1:55–2:20 | This week | Show the sessions; tick one done and rate one **hard**; tap **Finish week 1 and check in** | "Each week gets concrete 90-minute sessions, plus reviews one and three weeks later so things stick." |
| 2:20–2:45 | Week 1 check-in | Let the reflection finish; show what changed in the plan | "At the end of the week the plan adapts: hard topics get more time, and Llama writes a short reflection on my week." |
| 2:45–3:00 | Recent apps | Swipe the app away and reopen it: it opens on the same screen | "Progress is saved on the phone. No account, no cloud, and it works anywhere." |

## End card

> **Team 4 — Pathfinder**
> Vishnu Vardhan and Adity Narayan
> On-device personalised learning assistant
> Llama 3.2 1B · ExecuTorch 1.5.1 (XNNPACK, 4-bit weights) · vivo V27 (Dimensity 7200)
> 12–14 tokens/s on the phone · 822 MB model · works in airplane mode
> Source code and technical report: github.com/vishnu-vardhan243/team4-pathfinder

## After recording

- Check every number shown on screen against `docs/technical-report.md`.
- Keep the raw `.mp4` files: their timings back up the report.
- Compare the goal, explanation and check-in times in the `Pathfinder` log with the report (vivo V27 run on 2026-11-21: 9.3 s, 11.5 s and 10.4 s).
