# llama.cpp RPC on Android / Termux

This is the preferred first experiment for PhoneMesh-AI: use upstream `llama.cpp` RPC rather than implementing our own layer transport.

**What upstream actually supports:** with `GGML_RPC=ON`, each worker runs `ggml-rpc-server`; the driver passes one or more endpoints to `llama-cli --rpc`. The current upstream documentation says weights and KV cache are distributed across local and remote devices in proportion to available memory by default, with `--tensor-split` available to override proportions.

**Important limits:** RPC is explicitly documented upstream as proof-of-concept, fragile, and insecure. Never expose it to the public internet or an untrusted Wi-Fi network. Bind each worker to its private LAN IP, keep devices on the same trusted network, and stop the server when done. This is a CPU/RPC baseline; it does not automatically enable a phone's NPU. Do not assume it will be faster than one phone.

## 0. Before building

- Use Termux on each phone.
- Keep the already-downloaded GGUF on the driver phone. The worker phones do not need a second full model file for the initial RPC test.
- Check that all phones are on the same trusted Wi-Fi and note each phone's LAN IP. Do not use a public/shared hotspot.
- Use a short context first. PhoneMesh-AI's hard target is **less than 4 GiB runtime RSS per process**; the launcher cannot guarantee this by itself.
- Avoid `pkg upgrade` just for this test. Install only the build tools needed.

## 1. Build llama.cpp on each phone

Run on the driver and on every worker. The shallow clone reduces source download size.

```bash
pkg install git cmake clang make
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git
cd llama.cpp
cmake -S . -B build -DGGML_RPC=ON -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF
cmake --build build --config Release -j1
```

Builds can take a while on phones. Use `-j1` to reduce build memory pressure. Confirm the expected binaries exist:

```bash
ls -lh build/bin/ggml-rpc-server build/bin/llama-cli
```

If the build fails, keep the complete final error output. Do not download another copy of the model to troubleshoot a compiler error.

## 2. Start each worker

On each worker phone, find its private Wi-Fi IP in Android Wi-Fi details. Run from the `llama.cpp` directory, replacing the example IP with that phone's own LAN IP:

```bash
./build/bin/ggml-rpc-server --host 192.168.1.23 --port 50052
```

The IP is only an example. Bind to the worker's actual private LAN address, not `0.0.0.0`, unless you understand and deliberately accept the extra exposure. Leave the terminal session running. The server output should list the exposed devices and the endpoint. A CPU-only worker is expected to expose a CPU device.

For a second worker, use that phone's own LAN IP and a port such as `50052` on that separate phone.

## 3. Run the driver

The driver phone needs the GGUF file locally. From its `llama.cpp` directory, replace the model path and endpoint IPs:

```bash
./build/bin/llama-cli \
  -m "$HOME/models/Ornith-1.5-9B.gguf" \
  -c 256 -b 128 -n 16 \
  --rpc 192.168.1.23:50052,192.168.1.24:50052 \
  -ngl 99 \
  -p "Reply with one short sentence: what is Wi-Fi?"
```

This is a smoke test, not a performance benchmark. Use the exact local filename. If only one worker is available, pass just one endpoint. If the model is not a compatible GGUF or the model architecture is not supported by the current llama.cpp build, this command will fail; RPC cannot fix model-format incompatibility.

## 4. Measure before claiming success

First run the same prompt on the driver alone, without `--rpc`. Then repeat with one worker and two workers. Keep model file, context, batch, prompt, output token count, and thermal conditions matched. Record:

- first-token latency and total generation time;
- generated tokens/s;
- peak RSS for every process (driver and each worker);
- worker temperature/thermal state and available system RAM;
- whether output completed correctly and whether the process crashed.

Do not accept a configuration that exceeds 4 GiB RSS on any process. Reduce context/batch or exclude a worker if memory headroom is insufficient. Do not conclude distributed inference is faster from one run.

## 5. Optional scripts in this repository

From the PhoneMesh-AI checkout, while the current directory is the llama.cpp checkout:

```bash
bash ../PhoneMesh-AI/scripts/rpc-worker.sh 192.168.1.23 50052 ./build/bin/ggml-rpc-server
bash ../PhoneMesh-AI/scripts/rpc-driver.sh "$HOME/models/Ornith-1.5-9B.gguf" 192.168.1.23:50052,192.168.1.24:50052
```

Replace the example paths and private IP addresses with the actual values. The helper scripts only launch already-built binaries; they do not build llama.cpp, download models, or enforce a hard OS memory limit. See each script's usage text.

## Troubleshooting

- **Connection refused / timeout:** verify both phones are on the same trusted Wi-Fi, the worker server is still running, and the IP/port are correct.
- **No remote device listed:** rebuild both sides from the same current upstream source with `-DGGML_RPC=ON`.
- **Out of memory:** stop the run, lower `-c` and `-b`, use fewer workers, and check every process's RSS. A model file's on-disk size is not the same as runtime memory use.
- **Very slow generation:** RPC sends work across the network; Wi-Fi overhead may outweigh the compute saved. Compare matched single-phone and multi-phone runs.
- **Security:** the upstream RPC interface is not authenticated. Do not expose it to the internet or untrusted devices.
