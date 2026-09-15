# NeuTTS Android

An Android app implementing a system `TextToSpeechService` that runs [NeuTTS-Air](https://huggingface.co/neuphonic/neutts-air) /
[NeuTTS-Nano](https://huggingface.co/neuphonic/neutts-nano) fully on-device: a GGUF LLM backbone
(llama.cpp) generates acoustic tokens, and a NeuCodec ONNX decoder turns them into 24kHz PCM audio.
Any app (Maps, etc.) can select it as their TTS engine and route speech through it, cloning one of
the bundled reference voices.

## Requirements

- Android device/emulator, **arm64-v8a only** (`minSdk 28`)
- Network access on first launch (models are downloaded, not bundled — see below)

## Build & run

```
git clone --recurse-submodules <repo-url>
# or, if already cloned without submodules:
git submodule update --init

./gradlew assembleDebug        # build debug APK
./gradlew installDebug         # build + install on a connected device/emulator
```

`llama.cpp` and `espeak-ng` are vendored as git submodules (`app/src/main/cpp/llama.cpp`,
`app/src/main/cpp/espeak-ng`) — the `--recurse-submodules`/`submodule update --init` step above is
required or the native build will fail with missing source. Native code (JNI bridge, llama.cpp,
espeak-ng) builds automatically via CMake as part of the Gradle build — no separate native build
step needed.

On first launch, `MainActivity` downloads the selected GGUF backbone and the NeuCodec ONNX decoder
into app-private storage (`context.filesDir/models/`) and shows download progress. These persist
across app upgrades and reinstalls-over (`adb install -r`); only a full uninstall or clearing app
data removes them.

## Architecture

**Pipeline:** text → phonemize (espeak-ng) → prompt template → GGUF backbone (llama.cpp) →
acoustic codes → NeuCodec ONNX decoder → PCM.

- **`NeuTtsEngine.kt`** — orchestrates the full pipeline. Builds NeuTTS's exact chat-style prompt
  template, phonemizes reference + input text, runs the backbone via JNI, decodes codes to PCM.
  Shared by `MainActivity` (manual test UI) and `NeuTTSService` (system TTS integration).
- **`app/src/main/cpp/neutts_bridge.cpp`** — JNI bridge to llama.cpp. Loads the GGUF model, runs
  autoregressive sampling (top-k=50, temp=0.7) to generate `<|speech_N|>` tokens. Special token IDs
  (start/end/base) are passed in at init time since they differ per backbone (see `Backbone` in
  `NeuTtsEngine.kt`) — verify new models' IDs with `llama-tokenize` before adding them, since GGUF
  vocab *array index* does not always equal the actual token ID.
- **KV-cache reuse**: `neutts_bridge.cpp` finds the longest common token prefix between consecutive
  `nativeGenerateCodes` calls and skips re-decoding it. In practice this is the boilerplate + the
  reference voice's phonemized transcript — identical across calls for the same voice — so back-to-
  back synthesis with an unchanged voice is dramatically faster (only the new input text decodes).
- **`EspeakPhonemizer.kt` / `app/src/main/cpp/phonemizer.cpp`** — wraps the vendored official
  [espeak-ng](https://github.com/espeak-ng/espeak-ng) library to convert text to IPA phonemes before
  it reaches the backbone. This is required: NeuTTS was trained on phonemized text, not raw English,
  and skipping this step produces garbled/unconditioned audio.
- **`NeuCodecDecoder.kt`** — wraps the NeuCodec ONNX decoder via ONNX Runtime's Java API (no native
  JNI needed here; only the LLM backbone runs through the C++ bridge).
- **`ReferenceVoiceCodec.kt`** — reads NeuTTS's `.pt` reference-voice files. These are plain
  `torch.save(tensor)` output (a zip archive); we read the raw int32 storage directly without a
  pickle interpreter, since the tensor shape is always `(N codes, 1)`.

### Native dependencies (vendored, built from source via CMake)

- **`app/src/main/cpp/llama.cpp/`** — git submodule, upstream [llama.cpp](https://github.com/ggerganov/llama.cpp).
  Provides the GGUF backbone inference.
- **`app/src/main/cpp/espeak-ng/`** — git submodule, upstream [espeak-ng](https://github.com/espeak-ng/espeak-ng)
  (GPL-3.0). Provides phonemization. `app/src/main/assets/espeak-ng-data/` contains
  English-only phoneme/dictionary data pre-compiled on a host machine (cross-compiling can't
  self-host the data-compilation step) — do not regenerate by hand; rebuild via a native (non-NDK)
  espeak-ng build and copy `espeak-ng-data/{en_dict,intonations,phondata,phondata-manifest,
  phonindex,phontab,lang/gmw/en*}`.

### Performance

- `app/src/main/cpp/CMakeLists.txt` sets `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm` explicitly.
  Without this, NDK cross-compilation builds ggml's CPU backend with no ARM SIMD dot-product
  instructions (`GGML_NATIVE` is forced off when cross-compiling), forcing a software-emulated
  fallback for every quantized matmul — roughly an order of magnitude slower. This was the dominant
  fix for on-device latency; confirm it's still in effect if inference ever regresses drastically.
- Thread count is set to `std::thread::hardware_concurrency()`, not hardcoded.

## Models

Backbones and the codec decoder are hosted as public GCS objects (see `NeuTtsEngine.BACKBONES`).
Two backbones are selectable in the UI:

| Backbone | Size | Notes |
|---|---|---|
| NeuTTS-Nano (default) | 229M | Much faster; default for real-time use |
| NeuTTS-Air | 748M | Higher quality, slower |

Both use the same NeuCodec ONNX decoder (`neucodec-onnx-decoder-int8-model.onnx`).

## Known limitations

- Phonemization uses espeak-ng's `en-us` voice always; no other languages are wired up (English-only
  `espeak-ng-data` is bundled).
- No streaming synthesis — the full audio is generated before playback starts.
- The reference voices bundled in `assets/` (`sayoni`, `dave`) are fixed; there's no in-app flow to
  clone a new voice from user-supplied audio (would require an on-device NeuCodec *encoder*, which
  isn't part of this pipeline).
