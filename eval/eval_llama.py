"""Run Llama 3.2 1B (the same .pte the phone runs) on the 20 personas with the app's exact goal-reading prompt.

Run inside WSL with the ExecuTorch venv:
    source ~/et-venv/bin/activate && python eval/eval_llama.py <project folder>
Writes eval/llama_raw.json; then `node eval/score.mjs` scores it with the app's own checks.
"""
import contextlib
import io
import json
import os
import sys
import time

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
PARAMS = os.path.expanduser("~/llama-weights/Llama-3.2-1B-Instruct/original/params.json")
sys.path.insert(0, os.path.expanduser("~/executorch"))
from examples.models.llama.runner.native import NativeLlamaRunner, build_args_parser  # noqa: E402

# Identical to app/.../llm/Coach.kt readGoal and website/js/llm.js.
SYSTEM = ('Extract facts from a learner\'s goal. Reply with JSON only: {"weeks": integer or null, "hoursPerWeek": number or null, '
          '"knownSkills": [skill names from the list that the learner says they already know]}. Use null when the text does not say.')
SHOT_USER = "Skills: Spreadsheets, SQL basics, Python basics\nGoal: I use Excel at work and want to switch careers within a year, maybe 5 hours a week"
SHOT_ASSISTANT = '{"weeks": 52, "hoursPerWeek": 5, "knownSkills": ["Spreadsheets"]}'


def fmt(turns):
    """Llama 3 chat template, same layout as LlmEngine.format in the app."""
    out = "<|begin_of_text|>"
    for role, text in turns:
        out += f"<|start_header_id|>{role}<|end_header_id|>\n\n{text}<|eot_id|>"
    return out + "<|start_header_id|>assistant<|end_header_id|>\n\n"


def main():
    graph = json.load(open(f"{ROOT}/content/domains/data-analyst.json", encoding="utf-8"))
    personas = json.load(open(f"{ROOT}/eval/personas.json", encoding="utf-8"))
    names = ", ".join(s["name"] for s in graph["skills"])
    args = build_args_parser().parse_args([
        "--model", "llama3_2", "-f", f"{ROOT}/models/llama3_2_1b_8da4w.pte", "-p", PARAMS,
        "-t", f"{ROOT}/models/tokenizer.model", "-kv", "--temperature", "0",
    ])
    runner = NativeLlamaRunner(args)
    # Stop on <|eot_id|> as well as <|end_of_text|>, like the phone runtime (the .pte metadata lists both).
    # The Python tokenizer only knows 128001 by default.
    stops = {128009, 128001}
    runner.tokenizer.stop_tokens = sorted(stops)
    replies = []
    for p in personas:
        prompt = fmt([("system", SYSTEM), ("user", SHOT_USER), ("assistant", SHOT_ASSISTANT), ("user", f"Skills: {names}\nGoal: {p['goal']}")])
        # The template already starts with <|begin_of_text|>. allowed_special="all" makes the header and
        # <|eot_id|> markers real special tokens; by default they would be encoded as plain text.
        tokens = runner.tokenizer.encode(prompt, bos=False, eos=False, allowed_special="all")
        assert tokens[0] == 128000 and 128006 in tokens, "chat template was not tokenised as special tokens"
        t0 = time.time()
        with contextlib.redirect_stdout(io.StringIO()):  # the runner prints every token
            # 80 new tokens at most, like Coach.readGoal (maxTokens = 80).
            out = runner.generate(prompt_tokens=tokens, max_seq_len=len(tokens) + 80, temperature=0)
        secs = time.time() - t0
        out = out[len(tokens):] if out[:len(tokens)] == tokens else out
        text = runner.tokenizer.decode([t for t in out if t not in stops]).strip()
        replies.append({"id": p["id"], "reply": text, "secs": round(secs, 2), "tokens": len(out)})
        print(f"#{p['id']:>2} {secs:5.1f}s  {text[:110]}", flush=True)
    json.dump({"machine": "i5-1335U laptop CPU in WSL2 (same .pte as the phone)", "replies": replies},
              open(f"{ROOT}/eval/llama_raw.json", "w", encoding="utf-8"), indent=1)


if __name__ == "__main__":
    main()
