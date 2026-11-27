# On-device passage comparison

Freeze questions and expected zero-based step IDs in `tools/ask_benchmark_questions.json` before changing the index or running either model. A JSON null means that the library cannot answer. The harness uses the same Java semantic retriever shipped in the APK and prompts each model with the same four descriptions. The expected IDs stay out of the prompt.

1. Run `python tools/build_semantic_index.py MODEL_DIRECTORY --benchmark-descriptions descriptions.json` at the repository root. The script verifies the pinned Potion model and tokenizer checksums.
2. Compile `SemanticIndex.java` and `PassagePrompt.java` from `app/src/main/java/ae/fieldguide/` together with `tools/benchmark/AskBench.java` against the Android SDK's `android.jar`. Package the resulting classes with JDK `jar`, run Android build-tools `d8 --min-api 30` on that JAR and put `classes.dex` at the root of `bench.jar`.
3. Stage `bench.jar`, `descriptions.json`, the shipped `semantic.bin` and the frozen questions named `cases.json` under `/data/local/tmp/field-ask123/` on the authorized handset. Put the two GGUF files there as `qwen.gguf` and `gemma.gguf`. Place the arm64 llama.cpp b12590 Android `llama-server` and its shared libraries under `bin/`. Make the server executable and the benchmark JAR read-only.
4. From an Android shell, run the following command. Reverse the model order on the second device.

```sh
cd /data/local/tmp/field-ask123
CLASSPATH=/data/local/tmp/field-ask123/bench.jar app_process /system/bin ae.fieldguide.AskBench comparison usb qwen gemma
```

The result is written to `comparison.jsonl`. Each query records the ranked step IDs, cosine scores, selected step (or null), elapsed time and search-only time. Each block records model startup separately. Score exact IDs and measure paired latency only on supported questions that both models answered correctly.
