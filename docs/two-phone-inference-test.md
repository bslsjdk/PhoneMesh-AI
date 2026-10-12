# Termux distributed inference: 1 → 2 → 3 Android phones

This guide is the terminal-first path for testing real inference. The PhoneMesh Android APK is **not** required to launch the engine; it currently only helps test Wi-Fi Direct transport. The inference engine used here is upstream [prima.cpp](https://github.com/OpenCPIL/prima.cpp). Android/Termux compilation and the user's exact model are not yet verified, so follow the gates in order and save errors instead of guessing flags.

## Hard safety gates

## Low-bandwidth plan (200 KB/s internet)

The internet link is the scarce resource; local phone-to-phone Wi-Fi is a separate link. Do **not** repeat source downloads on every phone.

- Install Termux once on each device. Avoid running `pkg upgrade` unless a package actually requires it; it can pull many unrelated updates. Install only the packages the build reports missing.
- Clone source on **one phone only**, using shallow clones to avoid downloading full Git history.
- Transfer the source tree or built files to other phones over a local link. All phones still need compatible runtime libraries, and a binary built on one phone is not guaranteed to run on another until tested.
- Download the model once. Copy it locally to each node for the upstream prima.cpp baseline; compare SHA-256. Do not re-download it from the internet.
- Do not download a second model just for a smoke test. First test the existing model with a short prompt; if it is unsupported or exceeds the memory ceiling, stop before downloading anything else.

At 200 KB/s, 100 MB takes at least about 8 minutes 20 seconds and 1 GB takes at least about 1 hour 25 minutes, before overhead or interruptions. Exact source/dependency size varies, so check `du -sh` and download progress instead of trusting an estimate.


- Keep the measured runtime/process peak on **each phone at or below 4 GiB**. Stop immediately if the process is killed, Android reports memory pressure, or the phone becomes abnormally hot.
- Start with context 256–512 and 8–32 generated tokens. Test while cool, charging only if the phone stays cool, and keep screens awake.
- Use only a trusted private local link and devices you have permission to use. Do not expose the engine's experimental network ports to public Wi-Fi or the internet.
- Model size on disk is not runtime RAM. Memory mapping is not proof that this device/model combination fits.
- First verify the model with a **single-phone smoke test**. If the engine says the GGUF architecture or quantization is unsupported, stop: distributed flags cannot fix model incompatibility.

## 0. Record the exact model file

On the phone that already has the model, open Termux:

```sh
termux-setup-storage
ls -lh ~/storage/downloads/
mkdir -p ~/models
```

Copy the exact filename, not a wildcard that might match multiple files. Example only:

```sh
cp "$HOME/storage/downloads/EXACT_MODEL_FILENAME.gguf" "$HOME/models/model.gguf"
sha256sum "$HOME/models/model.gguf"
```

Use a local transfer (USB, Nearby Share, or PhoneMesh once its large-file transfer is implemented) to copy it to the other test phone(s), not another internet download. Compare `sha256sum` on every phone; all hashes must match. The upstream multi-node example expects the model path to exist on each node, so this is a temporary baseline, not the final per-node-only-shards design.

## 1. Install dependencies on each phone

Install Termux from its official distribution. Run on each phone:

```sh
pkg update
pkg upgrade
pkg install git clang make cmake pkg-config libzmq fio wget curl
```

If `pkg` cannot find one package, save the exact error and check the current Termux package name; do not randomly substitute libraries.

## 2. Build prima.cpp

Do not compile while a phone is hot or low on battery. The rank-0 scheduler requires HiGHS according to upstream documentation; install it from source in Termux's prefix on the **coordinator phone only**:

```sh
cd ~
git clone https://github.com/ERGO-Code/HiGHS.git
cd ~/HiGHS
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX"
cmake --build build -j2
cmake --install build
```

Then build prima.cpp on all phones. On the GT Neo6 coordinator, enable HiGHS; on worker phones, use the normal build:

```sh
cd ~
git clone https://github.com/OpenCPIL/prima.cpp.git
cd ~/prima.cpp
```

GT Neo6 (rank 0):
```sh
make USE_HIGHS=1 -j2
```

Each worker:
```sh
make -j2
```

If a build fails, stop and keep the complete terminal output. Upstream Linux success does not guarantee Android/Termux success. Verify:
```sh
cd ~/prima.cpp
ls -l ./llama-cli
./llama-cli --help
```

## 3. Single-phone model compatibility smoke test

Before networking, run this on the GT Neo6. Use the exact model file and begin with a short run:

```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf -c 256 -n 8 -p "Reply with one short sentence: what is Wi-Fi?"
```

Only proceed if it loads and generates text, and measured peak memory stays at or below 4 GiB. If the model fails to load, save the full error and stop. Do not assume Ornith-1.5-9B is compatible just because the filename ends in `.gguf`.

## 4. Connect two phones and identify IPs

1. Connect GT Neo6 and X50 using a private Wi-Fi Direct group (or a trusted local Wi-Fi network). PhoneMesh can help with Wi-Fi Direct discovery, but its benchmark success alone does not prove prima.cpp ports work.
2. On each phone, inspect `ip addr` and identify the address that can reach the other phone over this link. Write down the actual IPs:
   - `GT_IP`: GT Neo6, rank 0
   - `X50_IP`: X50, rank 1
3. Verify basic reachability if an appropriate ping tool is available. Do not assume the group-owner IP is always the coordinator IP.
4. Keep the phones awake and allow Termux to run without battery optimization during the test.

## 5. Two-phone inference

All nodes need the same exact model path for this upstream baseline. Start the worker first on X50 (replace `GT_IP` with the actual GT Neo6 address):

```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf --world 2 --rank 1 --master GT_IP --next GT_IP --prefetch
```

Leave it running. Then start the request on GT Neo6 (replace `GT_IP` and `X50_IP` with actual addresses):

```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf -c 256 -n 16 \
  -p "Explain briefly how two phones can cooperate on a computation." \
  --world 2 --rank 0 --master GT_IP --next X50_IP --prefetch
```

Success means rank 0 prints a model response and rank 1 joins without an error. A TCP benchmark or a successful connection in PhoneMesh alone is not distributed inference.

## 6. Three-phone inference

Only attempt this after the two-phone test succeeds. Add a third phone that can compile and run the same engine, and record its reachable IP as `PHONE3_IP`. The ring order is rank 0 → rank 1 → rank 2 → rank 0. Every node uses the GT Neo6 address as `--master`.

Start rank 2 on phone 3 first:
```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf --world 3 --rank 2 --master GT_IP --next GT_IP --prefetch
```

Start rank 1 on X50:
```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf --world 3 --rank 1 --master GT_IP --next PHONE3_IP --prefetch
```

Start rank 0 on GT Neo6:
```sh
cd ~/prima.cpp
./llama-cli -m ~/models/model.gguf -c 256 -n 16 \
  -p "Explain briefly how three phones can cooperate on a computation." \
  --world 3 --rank 0 --master GT_IP --next X50_IP --prefetch
```

The third phone's `--next` points back to GT Neo6; rank 1's `--next` points to phone 3. Replace every placeholder with a real IP, without angle brackets. If the engine rejects the topology or drops a rank, save all three terminal outputs; do not call it a successful three-node test.

## 7. Measure and report

For each run, record:
- device and rank, Android/Termux version, engine Git commit (`git -C ~/prima.cpp rev-parse HEAD`);
- exact model filename, byte size, and `sha256sum`;
- exact command, full output/error, whether every rank stayed connected;
- wall-clock generation time and generated token count/tokens per second;
- process RSS/peak if available, system available memory, and thermal/battery behavior.

Reject any run that exceeds 4 GiB runtime memory on any phone. Compare one-phone, two-phone, and three-phone latency under the same model, prompt, context, and token count. More phones are not automatically faster; network overhead or a weak node can slow the whole ring.

## Current project boundary

This is a **terminal test procedure**, not a claim that the test has passed on the target devices. The APK still does not bundle or launch prima.cpp. The first goal is a reproducible successful one-phone model load, then two-node inference, then three-node inference. Final PhoneMesh work still needs an integrated engine, authenticated peers, resumable chunked local model/shard transfer, and measured proof of the 4 GiB per-node limit. Never split a GGUF file at arbitrary byte offsets.


## Optional: use the repository launcher

After cloning PhoneMesh-AI on each phone, the helper can launch the smoke test or a distributed rank and watch that process's RSS:

```sh
cd ~
git clone https://github.com/bslsjdk/PhoneMesh-AI.git
```

Single-phone smoke test:
```sh
bash ~/PhoneMesh-AI/scripts/termux-run.sh smoke ~/models/model.gguf
```

For two phones, run on X50 (rank 1), then GT Neo6 (rank 0):
```sh
# X50
bash ~/PhoneMesh-AI/scripts/termux-run.sh node ~/models/model.gguf 2 1 GT_IP GT_IP

# GT Neo6 (replace IPs and optionally the prompt)
bash ~/PhoneMesh-AI/scripts/termux-run.sh node ~/models/model.gguf 2 0 GT_IP X50_IP "Explain briefly how two phones can cooperate on a computation."
```

For three phones, start rank 2 on phone 3, rank 1 on X50, and rank 0 on GT Neo6:
```sh
# Phone 3
bash ~/PhoneMesh-AI/scripts/termux-run.sh node ~/models/model.gguf 3 2 GT_IP GT_IP

# X50
bash ~/PhoneMesh-AI/scripts/termux-run.sh node ~/models/model.gguf 3 1 GT_IP PHONE3_IP

# GT Neo6
bash ~/PhoneMesh-AI/scripts/termux-run.sh node ~/models/model.gguf 3 0 GT_IP X50_IP "Explain briefly how three phones can cooperate on a computation."
```

The launcher checks the process's reported RSS approximately once per second and stops it if it observes RSS above 4 GiB. This is a best-effort guard, not a hard real-time guarantee or a replacement for checking Android's system memory and thermal status. It only launches an already-built engine; it does not compile prima.cpp or prove the model is compatible.


## Save internet bandwidth when adding phones

Do not run `git clone` separately on phone 2 and phone 3. Build and validate the engine on the GT Neo6 first. Then copy the tested source/build directory or tested worker executable to the other phones over the local link. Because Termux libraries and ABI details can differ, verify the copied executable with `./llama-cli --help` on each phone before trying a cluster. If a required shared library is missing, install only that dependency on the affected phone. Keep a record of the exact Git commit and binary hash so all nodes use matching builds.
