#!/data/data/com.termux/files/usr/bin/bash
# PhoneMesh low-download capability inventory. This script makes no network calls.
set -u

section() { printf '\n==== %s ====\n' "$1"; }

section "Android / device"
printf 'date: '; date 2>/dev/null || true
printf 'kernel: '; uname -a 2>/dev/null || true
for key in ro.product.manufacturer ro.product.model ro.soc.model ro.board.platform ro.build.version.release ro.build.version.sdk ro.product.cpu.abi; do
  value="$(getprop "$key" 2>/dev/null || true)"
  [ -n "$value" ] && printf '%s=%s\n' "$key" "$value"
done

section "Termux / ABI"
printf 'prefix=%s\n' "${PREFIX:-unknown}"
printf 'termux_arch=%s\n' "${TERMUX_ARCH:-unknown}"
printf 'process_abi='
getprop ro.product.cpu.abi 2>/dev/null || true
printf 'available_storage:\n'
df -h "$HOME" 2>/dev/null || true
printf 'memory_summary:\n'
grep -E 'MemTotal|MemAvailable|SwapTotal|SwapFree' /proc/meminfo 2>/dev/null || true

section "Relevant installed Termux packages"
if command -v dpkg-query >/dev/null 2>&1; then
  dpkg-query -W -f='${Package} ${Version}\n' 2>/dev/null | grep -Ei 'termux|clang|make|cmake|git|zeromq|libzmq|highs|onnx|tensorflow|qnn|llama|vulkan' || true
else
  echo "dpkg-query unavailable"
fi

section "Possible vendor accelerator libraries (visibility only)"
for dir in /vendor/lib64 /vendor/lib /system/lib64 /system_ext/lib64 "$PREFIX/lib"; do
  [ -d "$dir" ] || continue
  echo "-- $dir"
  ls "$dir" 2>/dev/null | grep -Ei 'qnn|htp|hexagon|cdsprpc|adreno|vulkan|neural|nnapi|tensorflow|onnx' | head -n 80 || true
done

section "Termux-visible shared libraries"
if command -v ldconfig >/dev/null 2>&1; then
  ldconfig -p 2>/dev/null | grep -Ei 'qnn|htp|hexagon|cdsprpc|vulkan|onnx|tensorflow' || true
else
  echo "ldconfig unavailable; this is not a failure by itself"
fi

section "Result / interpretation"
cat <<'EOF'
This is an inventory only. It does not load an NPU, run inference, or prove that a vendor library is callable from Termux.
Android may hide vendor libraries or require a native Android app/vendor service. A missing result is inconclusive.
Do not download an SDK yet. Save this output from each phone and compare the exact SoC, ABI, and visible runtime libraries.
EOF
