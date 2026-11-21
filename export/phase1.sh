#!/usr/bin/env bash
# Phase 1 helper: get Llama 3.2 onto the phone with ExecuTorch v1.5.1.
# Run inside WSL Ubuntu from the project root:
#   bash export/phase1.sh download [1B|3B]   # needs Meta's license accepted + `hf auth login` done by you
#   bash export/phase1.sh export   [1B|3B]   # writes models/llama3_2_<size>_8da4w.pte
#   bash export/phase1.sh push     [1B|3B]   # copies the .pte + tokenizer to the phone over adb
set -euo pipefail

CMD=${1:?usage: phase1.sh download|export|push [1B|3B]}
SIZE=${2:-1B}
case "$SIZE" in 1B|3B) ;; *) echo "size must be 1B or 3B"; exit 2 ;; esac
size_lc=$(echo "$SIZE" | tr 'A-Z' 'a-z')

PROJECT=$(cd "$(dirname "$0")/.." && pwd)
ET_DIR=${ET_DIR:-$HOME/executorch}
VENV=${VENV:-$HOME/et-venv}
WEIGHTS=${WEIGHTS:-$HOME/llama-weights/Llama-3.2-$SIZE-Instruct}
PTE_NAME="llama3_2_${size_lc}_8da4w.pte"
ADB=${ADB:-/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe}
DEVICE_DIR=/data/local/tmp/llama

# shellcheck disable=SC1091
source "$VENV/bin/activate"

case "$CMD" in
  download)
    if command -v hf >/dev/null; then HF=(hf); else HF=(huggingface-cli); fi
    "${HF[@]}" auth whoami 2>/dev/null || "${HF[@]}" whoami 2>/dev/null || {
      echo "Not logged in to Hugging Face. Run: ${HF[*]} login   (paste your own read token)"; exit 1; }
    mkdir -p "$WEIGHTS"
    # Only Meta's original checkpoint: consolidated.00.pth, params.json, tokenizer.model.
    # Plain HTTPS (Xet transfers dropped on our connection) and retries; hf can exit 0 after a failed
    # file, so success is judged by the files actually being there.
    export HF_HUB_DISABLE_XET=1 HF_HUB_DOWNLOAD_TIMEOUT=60
    for attempt in 1 2 3 4 5; do
      "${HF[@]}" download "meta-llama/Llama-3.2-$SIZE-Instruct" --include "original/*" --local-dir "$WEIGHTS" || true
      ok=1
      for f in consolidated.00.pth params.json tokenizer.model; do [ -f "$WEIGHTS/original/$f" ] || ok=0; done
      [ "$ok" = 1 ] && break
      echo "download incomplete (attempt $attempt), retrying in 20s"; sleep 20
    done
    [ "$ok" = 1 ] || { echo "download failed after 5 attempts"; exit 1; }
    ls -lh "$WEIGHTS/original"
    # Verify the big checkpoint against the SHA-256 Hugging Face records for it.
    python -I - "meta-llama/Llama-3.2-$SIZE-Instruct" "$WEIGHTS/original/consolidated.00.pth" <<'PY'
import hashlib, sys
from huggingface_hub import HfApi
repo, path = sys.argv[1], sys.argv[2]
want = HfApi().get_paths_info(repo, ["original/consolidated.00.pth"])[0].lfs.sha256
h = hashlib.sha256()
with open(path, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 24), b""):
        h.update(chunk)
print("expected", want)
print("actual  ", h.hexdigest())
sys.exit(0 if h.hexdigest() == want else 1)
PY
    echo "checkpoint SHA-256 OK"
    ;;

  export)
    CK="$WEIGHTS/original"
    for f in consolidated.00.pth params.json tokenizer.model; do
      [ -f "$CK/$f" ] || { echo "missing $CK/$f (run download first)"; exit 1; }
    done
    mkdir -p "$PROJECT/models"
    cd "$ET_DIR"
    /usr/bin/time -v python -m extension.llm.export.export_llm \
      --config "$PROJECT/export/llama3_2_xnnpack_8da4w.yaml" \
      base.checkpoint="$CK/consolidated.00.pth" \
      base.params="$CK/params.json" \
      export.output_name="$PROJECT/models/$PTE_NAME" 2>&1 | tee "$PROJECT/models/export_${size_lc}.log"
    # (v1.5.1 uses an output_name ending in .pte as the full path and ignores output_dir.)
    cp "$CK/tokenizer.model" "$PROJECT/models/tokenizer.model"
    ls -lh "$PROJECT/models"
    ;;

  push)
    [ -x "$ADB" ] || { echo "adb not found at $ADB (set ADB=...)"; exit 1; }
    "$ADB" get-state >/dev/null 2>&1 || { echo "No phone found. Connect it with USB debugging on."; "$ADB" devices; exit 1; }
    "$ADB" shell mkdir -p "$DEVICE_DIR"
    "$ADB" push "$(wslpath -w "$PROJECT/models/$PTE_NAME")" "$DEVICE_DIR/"
    # The demo docs name the Llama 3 tokenizer tokenizer.bin; push both names so either picker works.
    "$ADB" push "$(wslpath -w "$PROJECT/models/tokenizer.model")" "$DEVICE_DIR/tokenizer.model"
    "$ADB" push "$(wslpath -w "$PROJECT/models/tokenizer.model")" "$DEVICE_DIR/tokenizer.bin"
    "$ADB" shell ls -l "$DEVICE_DIR"
    ;;

  *) echo "unknown command $CMD"; exit 2 ;;
esac
