#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash scripts/rpc-driver.sh <model.gguf> <host:port[,host:port...]> [prompt]

Example (run from the llama.cpp checkout):
  bash ../PhoneMesh-AI/scripts/rpc-driver.sh "$HOME/models/model.gguf" 192.168.1.23:50052,192.168.1.24:50052

This is a low-context smoke test (context 256, batch 128, 16 output tokens).
For a single-phone baseline, run llama-cli with the same settings but without
--rpc. This script does not build llama.cpp or enforce a hard RSS limit.
EOF
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" || $# -lt 2 || $# -gt 3 ]]; then
  usage
  [[ ${1:-} == "-h" || ${1:-} == "--help" ]] && exit 0
  exit 2
fi

model="$1"
endpoints="$2"
prompt="${3:-Reply with one short sentence: what is Wi-Fi?}"
bin="${LLAMA_CLI_BIN:-./build/bin/llama-cli}"

if [[ ! -f "$model" ]]; then
  echo "Model file not found: $model" >&2
  exit 1
fi
if [[ ! -x "$bin" ]]; then
  echo "llama-cli binary is not executable: $bin" >&2
  echo "Build llama.cpp with -DGGML_RPC=ON first." >&2
  exit 1
fi
if [[ "$endpoints" == *" "* || "$endpoints" == *";"* ]]; then
  echo "Endpoints must be comma-separated host:port values with no spaces." >&2
  exit 2
fi

echo "Starting short RPC smoke test. Monitor RSS on every phone."
exec "$bin" -m "$model" -c 256 -b 128 -n 16 --rpc "$endpoints" -ngl 99 -p "$prompt"
