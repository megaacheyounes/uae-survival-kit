#include <jni.h>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"
#include <dlfcn.h>
#include <stdexcept>
#include <cmath>
#include <limits>
#include <memory>
#include <string>
#include <vector>
#include <mutex>

namespace {
std::mutex gate;
std::once_flag initialized;
using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
void logError(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, "FieldGuideAI", text);
}
std::string readString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string copy(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_ae_fieldguide_LocalAI_complete(
        JNIEnv *env, jobject, jstring modelPath, jstring input, jint maxLabel) {
    std::lock_guard<std::mutex> lock(gate);
    auto fail = [&](const char *message) -> jstring {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
        return nullptr;
    };
    try {
        std::call_once(initialized, [] {
            llama_log_set(logError, nullptr);
            Dl_info library{};
            if (!dladdr(reinterpret_cast<void*>(&logError), &library) || !library.dli_fname)
                throw std::runtime_error("Cannot locate installed native libraries");
            std::string path(library.dli_fname);
            auto slash = path.find_last_of('/');
            if (slash == std::string::npos) throw std::runtime_error("Missing native library directory");
            ggml_backend_load_all_from_path(path.substr(0, slash).c_str());
            if (!ggml_backend_dev_count()) throw std::runtime_error("No supported CPU backend");
            llama_backend_init();
        });
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        Model model(llama_model_load_from_file(readString(env, modelPath).c_str(), mp), llama_model_free);
        if (!model) return fail("Could not load the verified model on this device.");
        const llama_vocab *vocab = llama_model_get_vocab(model.get());
        auto cp = llama_context_default_params();
        cp.n_ctx = 1536;
        cp.n_batch = 256;
        cp.n_threads = 2;
        cp.n_threads_batch = 2;
        Context ctx(llama_init_from_model(model.get(), cp), llama_free);
        if (!ctx) return fail("Could not allocate the local AI context.");
        auto prompt = readString(env, input);
        jclass threadClass = env->FindClass("java/lang/Thread");
        jobject thread = env->CallStaticObjectMethod(threadClass, env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;"));
        jmethodID interrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
        std::vector<llama_token> ids(cp.n_ctx);
        int count = llama_tokenize(vocab, prompt.data(), prompt.size(), ids.data(), ids.size(), true, true);
        if (count <= 0 || count + 8 >= static_cast<int>(cp.n_ctx)) return fail("Question exceeds the local context.");
        for (int i = 0; i < count; i += cp.n_batch) {
            if (env->CallBooleanMethod(thread, interrupted)) return fail("Question cancelled.");
            int length = std::min<int>(cp.n_batch, count - i);
            if (llama_decode(ctx.get(), llama_batch_get_one(ids.data() + i, length))) return fail("The model could not process the source passages.");
        }
        if (maxLabel == 0) {
            using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
            Sampler sampler(llama_sampler_init_greedy(), llama_sampler_free);
            std::string output;
            for (int i = 0; i < 112 && count + i < static_cast<int>(cp.n_ctx) - 1; ++i) {
                if (env->CallBooleanMethod(thread, interrupted)) return fail("Question cancelled.");
                auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
                if (llama_vocab_is_eog(vocab, token)) break;
                char piece[512];
                int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
                if (n < 0) return fail("Unable to read generated text.");
                output.append(piece, n);
                if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1))) return fail("Answer generation failed.");
            }
            // Token pieces are UTF-8, whereas NewStringUTF expects modified UTF-8.
            jbyteArray bytes = env->NewByteArray(output.size());
            if (!bytes) return nullptr;
            env->SetByteArrayRegion(bytes, 0, output.size(), reinterpret_cast<const jbyte*>(output.data()));
            jclass stringClass = env->FindClass("java/lang/String");
            return static_cast<jstring>(env->NewObject(stringClass, env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V"), bytes, env->NewStringUTF("UTF-8")));
        }
        // For passage selection, constrain the token to a valid label (0 = no source).
        float *logits = llama_get_logits_ith(ctx.get(), -1);
        constexpr char labels[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        if (!logits || maxLabel < 1 || maxLabel >= static_cast<int>(sizeof(labels) - 1)) return fail("No model scores were available.");
        float best = -std::numeric_limits<float>::infinity();
        llama_token selected = -1;
        int label = -1;
        for (int i = 0; i <= maxLabel; ++i) {
            char digit = labels[i];
            llama_token token;
            if (llama_tokenize(vocab, &digit, 1, &token, 1, false, false) != 1) continue;
            if (std::isfinite(logits[token]) && logits[token] > best) {
                best = logits[token];
                selected = token;
                label = i;
            }
        }
        if (selected < 0 || llama_decode(ctx.get(), llama_batch_get_one(&selected, 1))) {
            return fail("The device could not generate a valid local response.");
        }
        return env->NewStringUTF(std::to_string(label).c_str());
    } catch (...) { return fail("The local inference runtime failed."); }
}
