"""Edit the demo recordings into one 1920x1080 video with captions, the logo, a title card and an end card.

Input: docs/video/part1..4.mp4 from `adb shell screenrecord` on the phone (1080x2400, variable frame rate),
and docs/logo/pathfinder-logo-1024.png (made by tools/make_logo.py).
Output: docs/video/pathfinder-demo.mp4. Needs ffmpeg 7+ on PATH and Segoe UI (Windows) or DejaVu Sans fonts.

    python tools/make_demo_video.py

Nothing is sped up. Edits: the phone's home screen and quick settings (which show the owner's wallpaper) are cut;
part 1 opens by holding its first app frame; screenrecord writes no frames while the screen is still, so a part
whose ending was still is padded by holding its last recorded frame. No other frames are added.
"""
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VID = ROOT / "docs" / "video"
WORK = VID / "build"
LOGO = ROOT / "docs" / "logo" / "pathfinder-logo-1024.png"
FPS = 30

PHONE = "vivo V27 (Dimensity 7200)"
FOOTER = f"Llama 3.2 1B · ExecuTorch 1.5.1 · {PHONE} · airplane mode · real time, not sped up"
# Status bar crop (phone pixels) shown enlarged while the "Offline" caption is up: airplane icon, no Wi-Fi icon.
STATUS_BAR = (600, 0, 480, 100)

# part: cut = seconds dropped from the start (home screen / wallpaper), hold = seconds the first kept frame is held,
# length = final length, inset_until = show the status-bar inset until this time, caps = [(start, heading, body)].
# Caption times are on the final timeline: recorded time - cut + hold. A caption lasts until the next one.
PARTS = [
    dict(part=1, cut=8.8, hold=7, length=59, inset_until=9.3, caps=[
        (0.0, "Offline", "Airplane mode is on, with Wi-Fi and Bluetooth off (see the status bar). Nothing in this video uses the internet."),
        (3.6, "Pathfinder", "A personal learning coach that runs entirely on this phone. No account, no server."),
        (9.3, "Loading Llama", "Meta's Llama 3.2 1B, compressed to 822 MB with 4-bit weights, runs on the phone's Arm CPU through ExecuTorch."),
        (16.6, "Loading Llama", "Loaded in 3.2 seconds. Ready to work offline."),
        (30.2, "Your goal", "The learner describes their goal in their own words."),
        (40.2, "Llama reads the goal", "Llama turns the goal into facts the app can plan with. This takes about 10 seconds on the phone."),
        (51.3, "Understood", "26 weeks, 8 hours a week, already knows Spreadsheets. Deadline and hours stated in the text are read by rules; Llama adds the skills. The learner can correct anything here."),
    ]),
    dict(part=2, cut=0, hold=0, length=80, inset_until=0, caps=[
        (0.0, "Skill check", "A short quiz finds the learner's real starting level, so the plan does not start from zero."),
        (30.1, "Skill gaps", "The app compares each skill with its target level and puts the gaps in prerequisite order."),
        (49.5, "Llama explains", "Llama explains the order in plain words, streamed as it writes. About 12 seconds on the phone."),
        (68.2, "Learning pathway", "Each skill shows the hours it needs, what it builds on, and a free resource to learn from."),
    ]),
    dict(part=3, cut=0, hold=0, length=48, inset_until=0, caps=[
        (0.0, "Weekly plan", "The pathway becomes a weekly plan: sessions of up to 90 minutes that fit 8 hours a week, with reviews 1 and 3 weeks later."),
        (10.3, "A week later", "The learner ticks the sessions they did and rates them. Advanced SQL felt hard."),
        (21.7, "Weekly check-in", "The plan adapts: extra time for the hard skill, unfinished sessions moved, still on track for the week-26 deadline. Llama writes a short reflection on the week."),
    ]),
    dict(part=4, cut=5.4, hold=0, length=8, inset_until=0, caps=[
        (0.0, "Saved on the phone", "The app was force-closed and opened again. It reopens exactly where the learner left off, reflection included. Progress is stored on the device."),
    ]),
]

TITLE = [  # (text, size, colour, y, bold)
    ("Pathfinder", 120, "white", 400, True),
    ("An on-device personalised learning assistant", 48, "0xd6dcf5", 560, False),
    ("Team 4: Vishnu Vardhan and Adity Narayan", 40, "0x8ea2ff", 640, False),
    ("Llama 3.2 1B + ExecuTorch on an Arm Android phone", 32, "0x9aa3c0", 725, False),
    (f"Recorded live on a {PHONE} in airplane mode. Not sped up.", 32, "0x9aa3c0", 775, False),
]
END = [
    ("Pathfinder · Team 4", 80, "white", 310, True),
    ("Vishnu Vardhan and Adity Narayan", 40, "0x8ea2ff", 435, False),
    ("Llama 3.2 1B Instruct · ExecuTorch 1.5.1 (XNNPACK, 4-bit weights) · 822 MB", 36, "0xd6dcf5", 520, False),
    (f"{PHONE}: 10-14 tokens/s, each Llama step about 10-12 s", 36, "0xd6dcf5", 580, False),
    ("Goal reading: 75 % fully right on 20 test learners (rules + Llama)", 36, "0xd6dcf5", 640, False),
    ("No internet permission · progress stays on the phone", 36, "0xd6dcf5", 700, False),
    ("Source code and report: github.com/vishnu-vardhan243/team4-pathfinder", 32, "0x8ea2ff", 815, False),
]
BG = "0x0b1020"


def fonts():
    """Copy fonts next to the build so filter paths need no escaping (Windows drive colons break drawtext)."""
    cands = [("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/segoeuib.ttf"),
             ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")]
    for reg, bold in cands:
        if os.path.exists(reg) and os.path.exists(bold):
            shutil.copy(reg, WORK / "regular.ttf"); shutil.copy(bold, WORK / "bold.ttf")
            return
    sys.exit("No usable font found")


def text_file(name, text):
    # Plain \n line ends: drawtext treats the \r of Windows line ends as an extra line break.
    (WORK / name).write_text(text, encoding="utf-8", newline="\n")
    return name


def dt(textfile, size, colour, x, y, bold=False, enable=None, fade_from=None):
    alpha = f":alpha='min(1,(t-{fade_from})/0.35)'" if fade_from is not None else ""
    en = f":enable='{enable}'" if enable else ""
    return (f"drawtext=fontfile={'bold' if bold else 'regular'}.ttf:textfile={textfile}:fontsize={size}:fontcolor={colour}"
            f":x={x}:y={y}:line_spacing=14:expansion=none{alpha}{en}")


def card(name, lines, secs, logo_px, logo_y):
    """Title or end card: logo (input 0) centred above lines of text."""
    f = [f"color=c={BG}:s=1920x1080:r={FPS}:d={secs}[bg]",
         f"[0:v]scale={logo_px}:{logo_px},format=rgba,fade=t=in:st=0:d=0.5:alpha=1[logo]",
         f"[bg][logo]overlay=(W-w)/2:{logo_y}:shortest=1[base]"]
    chain = "[base]" + ",".join(dt(text_file(f"{name}{i}.txt", t), s, c, "(w-text_w)/2", y, b, fade_from=0.3 + i * 0.25)
                                for i, (t, s, c, y, b) in enumerate(lines))
    chain += f",fade=t=out:st={secs - 0.6}:d=0.6[v]"
    return ";".join(f + [chain])


def part_filter(p):
    n, length = p["part"], p["length"]
    f = [f"[0:v]trim=start={p['cut']},setpts=PTS-STARTPTS,fps={FPS},"
         f"tpad=start_mode=clone:start_duration={p['hold']}:stop_mode=clone:stop_duration={length},"
         f"trim=duration={length},setpts=PTS-STARTPTS,split[raw][raw2]",
         "[raw]scale=450:1000,pad=462:1012:6:6:color=0x2a3350[ph]",
         f"color=c={BG}:s=1920x1080:r={FPS}:d={length}[bg]",
         "[1:v]scale=56:56[logo]"]
    x, y, w, h = STATUS_BAR
    f.append(f"[raw2]crop={w}:{h}:{x}:{y},scale={w * 2}:{h * 2},pad={w * 2 + 8}:{h * 2 + 8}:4:4:color=0x8ea2ff[inset]")
    draws = [dt(text_file(f"p{n}_label.txt", "TEAM 4 · PATHFINDER"), 28, "0x8ea2ff", 834, 112, True),
             dt(text_file(f"p{n}_footer.txt", FOOTER), 24, "0x7c86a8", 760, 1000)]
    caps = p["caps"]
    for i, (start, head, body) in enumerate(caps):
        end = caps[i + 1][0] if i + 1 < len(caps) else length + 1
        en = f"between(t,{start},{end})"
        draws.append(dt(text_file(f"p{n}_h{i}.txt", head), 60, "white", 760, 170, True, en, start))
        draws.append(dt(text_file(f"p{n}_b{i}.txt", textwrap.fill(body, 46)), 40, "0xd6dcf5", 760, 280, False, en, start))
    f.append("[bg][ph]overlay=150:34[s1]")
    f.append("[s1][logo]overlay=760:100:shortest=1[s2]")
    if p["inset_until"]:
        f.append(f"[s2][inset]overlay=760:560:enable='lt(t,{p['inset_until']})'[s3]")
    else:
        f.append("[inset]nullsink;[s2]null[s3]")
    f.append("[s3]" + ",".join(draws) + "[v]")
    return ";".join(f)


def run(args):
    print(" ".join(str(a) for a in args[:6]), "...", flush=True)
    subprocess.run(args, check=True, cwd=WORK)


def encode(out, filt_name, filt, inputs, secs):
    (WORK / filt_name).write_text(filt, encoding="utf-8")
    run(["ffmpeg", "-v", "error", "-y", *inputs, "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
         "-/filter_complex", filt_name, "-map", "[v]", "-map", f"{inputs.count('-i')}:a", "-shortest",
         "-c:v", "libx264", "-crf", "20", "-preset", "medium", "-pix_fmt", "yuv420p", "-r", str(FPS),
         "-c:a", "aac", "-b:a", "96k", "-t", str(secs), out])


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    fonts()
    logo_still = ["-loop", "1", "-framerate", str(FPS), "-i", str(LOGO)]
    segs = []
    encode("title.mp4", "title.txt", card("title", TITLE, 5, 220, 150), logo_still, 5); segs.append("title.mp4")
    for p in PARTS:
        src = VID / f"part{p['part']}.mp4"
        encode(f"p{p['part']}.mp4", f"p{p['part']}.txt", part_filter(p), ["-i", str(src), *logo_still], p["length"])
        segs.append(f"p{p['part']}.mp4")
    encode("end.mp4", "end.txt", card("end", END, 8, 170, 120), logo_still, 8); segs.append("end.mp4")
    (WORK / "list.txt").write_text("".join(f"file '{s}'\n" for s in segs), encoding="utf-8")
    out = VID / "pathfinder-demo.mp4"
    run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", "-movflags", "+faststart", str(out)])
    print("wrote", out)


if __name__ == "__main__":
    main()
