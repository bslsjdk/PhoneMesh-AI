#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash scripts/rpc-worker.sh <private_lan_ip> [port] [rpc_server_binary]

Example (run from the llama.cpp checkout):
  bash ../PhoneMesh-AI/scripts/rpc-worker.sh 192.168.1.23 50052 ./build/bin/ggml-rpc-server

Bind only to this phone's private LAN IP. The upstream RPC server has no
authentication; never expose it to the public internet or an untrusted network.
This script launches an existing binary; it does not build llama.cpp.
EOF
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" || $# -lt 1 || $# -gt 3 ]]; then
  usage
  [[ ${1:-} == "-h" || ${1:-} == "--help" ]] && exit 0
  exit 2
fi

host="$1"
port="${2:-50052}"
bin="${3:-./build/bin/ggml-rpc-server}"

if [[ "$host" == "0.0.0.0" || "$host" == "::" || "$host" == "*" ]]; then
  echo "Refusing wildcard bind. Supply this phone's private LAN IP." >&2
  exit 2
fi
if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
  echo "Invalid TCP port: $port" >&2
  exit 2
fi
if [[ ! -x "$bin" ]]; then
  echo "RPC server binary is not executable: $bin" >&2
  echo "Build llama.cpp with -DGGML_RPC=ON first." >&2
  exit 1
fi

echo "Starting RPC worker at $host:$port"
echo "Keep this terminal open. Stop with Ctrl+C when finished."
exec "$bin" --host "$host" --port "$port"
