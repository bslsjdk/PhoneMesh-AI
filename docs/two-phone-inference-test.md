# Two-phone inference test (Android / Termux)

This is the first practical route to test distributed inference on the real phones while the Android app's native backend is still being integrated. It uses the upstream prima.cpp engine from Termux; it does **not** claim that the PhoneMesh APK itself can launch inference yet.

## Important limits

- Target devices: GT Neo6 (rank 0 / coordinator) and X50 5G (rank 1 / worker).
- Keep each phone's measured process/runtime peak at or below 4 GiB. Stop immediately if Android reports memory pressure, the process is killed, or the phone becomes abnormally hot.
- Start with a short prompt, context 512, and at most 32 generated tokens. Only increase after checking memory and temperature.
- Upstream prima.cpp's documented multi-device command supplies a model path on each device. For this first integration test, put the **same exact GGUF file on both phones** (copy locally from the phone that already has it; no second internet download is needed). This is a temporary compatibility baseline, not the final per-node-only-shard design.
- Model file size is not the same as runtime RAM. prima.cpp uses memory mapping/lazy loading, but that does not prove the Android process will stay under 4 GiB. Measure it on each actual phone.
- Use only your own two phones on the private Wi-Fi Direct group. prima.cpp's distributed RPC/network path is experimental; do not expose it to public Wi-Fi or the internet.

## 1. Prepare both phones

Install Termux from its official source. Open Termux on each phone and run:

```sh
pkg update
pkg upgrade
pkg install git clang make cmake pkg-config libzmq fio wget curl
termux-setup-storage
```

Ensure the same model file is present on both phones. For example, if it is in Downloads:

```sh
ls -lh ~/storage/downloads/
mkdir -p ~/models
cp ~/storage/downloads/Ornith-1.5-9B*.gguf ~/models/model.gguf
```

Adjust the source filename to the exact file you have. If the wildcard matches multiple files, do not use this command unchanged; choose the exact file. Check the two copies match by comparing `sha256sum ~/models/model.gguf` on both phones.

## 2. Build prima.cpp on each phone

This is a native build and may take a while. Run on both phones:

```sh
cd ~
git clone https://github.com/OpenCPIL/prima.cpp.git
cd ~/prima.cpp
make -j2
```

If dependency detection or compilation fails, stop and save the complete error output; don't keep changing random compiler flags. The project has evolving dependencies, so successful upstream Linux builds do not guarantee a successful Termux build on every Android version.

Verify the executable and help output:

```sh
cd ~/prima.cpp
ls -l ./llama-cli
./llama-cli --help
```

## 3. Connect the phones

1. Open PhoneMesh-AI on both phones, enable Wi-Fi, scan and connect the phones using Wi-Fi Direct.
2. Record the group-owner IP shown in the app. The other phone needs to reach that IP through the Wi-Fi Direct interface.
3. Keep the phones on this private group. Do not open prima.cpp ports to other networks.
4. If Android battery restrictions suspend Termux, allow Termux to run while testing and keep both screens awake.

## 4. Start the worker first (X50, rank 1)

Replace `OWNER_IP` with the group-owner IP displayed by PhoneMesh. Run:

```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf \
  --world 2 --rank 1 --master OWNER_IP --next OWNER_IP --prefetch
```

Leave it running in this terminal.

## 5. Start inference on the GT Neo6 (rank 0)

Replace `OWNER_IP` with the same group-owner IP. If GT Neo6 is the group owner, use its Wi-Fi Direct group-owner IP; if Android assigns roles differently, use the actual owner IP in both commands and keep rank 0 on GT Neo6 for this first test.

```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf \
  -c 512 -n 32 -p "Explain in one short paragraph how Wi-Fi Direct connects two phones." \
  --world 2 --rank 0 --master OWNER_IP --next WORKER_IP --prefetch
```

Replace `WORKER_IP` with the X50's reachable Wi-Fi Direct address. The worker's `--next` address should point back to the coordinator/owner IP. The rank-0 node initiates the request; rank 1 must already be running.

If the worker fails to join, save both terminal outputs and the app's connection status. Do not assume a successful TCP benchmark means the distributed inference ports are reachable.

## 6. Measure, don't guess

Record on each phone before and after the run:
- model file SHA-256 and size;
- process peak RSS (if available via `/proc/<pid>/status` or Android process tools);
- available memory, thermal status, generation time, and tokens/s;
- whether both ranks remained active or the engine removed one node;
- exact command and full output.

Reject a run if either node exceeds the 4 GiB runtime ceiling. The first successful run proves only that the selected model and two-node setup can execute; it does not prove it is faster than single-phone inference.

## What this does not solve yet

The current APK still contains Wi-Fi Direct discovery and a bounded-memory TCP transfer benchmark, not a bundled native inference engine. This document is a temporary Termux route to validate the distributed runtime. The final PhoneMesh integration still needs a pinned, reproducible Android engine build, secure node authentication, robust model/shard transfer, and tests confirming the actual per-node memory peaks. Do not split a GGUF file at arbitrary byte offsets.
