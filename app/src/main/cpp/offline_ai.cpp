#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

static std::atomic_bool stopped{false};
static std::once_flag initialized;
using Clock = std::chrono::steady_clock;
static double elapsed(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }
static bool abort_run(void *) { return stopped.load(); }
static bool load_progress(float, void *) { return !stopped.load(); }
static void check_stop() { if (stopped.load()) throw std::runtime_error("Cancelled"); }
static void set_batch(llama_batch_ext * b, const llama_token * tokens, int count, int pos) {
    llama_batch_ext_clear(b);
    for (int i = 0; i < count; ++i) {
        int index = llama_batch_ext_add_token(b, 0, tokens[i]);
        if (index < 0) throw std::runtime_error("Could not create token batch");
        llama_pos p = pos + i;
        llama_batch_ext_set_pos(b, index, &p);
    }
    llama_batch_ext_set_output_logits(b, count - 1, true);
}
extern "C" JNIEXPORT void JNICALL Java_com_chase_companion_OfflineAi_resetNative(JNIEnv *, jclass) { stopped.store(false); }
extern "C" JNIEXPORT void JNICALL Java_com_chase_companion_OfflineAi_cancelNative(JNIEnv *, jclass) { stopped.store(true); }
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_chase_companion_OfflineAi_runNative(JNIEnv * env, jclass, jstring filename, jbyteArray input, jint threads, jobject sink) {
    try {
        check_stop();
        std::call_once(initialized, [] { llama_backend_init(); });
        jclass type = env->GetObjectClass(sink);
        jmethodID phase = env->GetMethodID(type, "phase", "(Ljava/lang/String;)V");
        jmethodID chunk = env->GetMethodID(type, "chunk", "([B)V");
        if (env->ExceptionCheck()) return nullptr;
        auto status = [&](const char * text) {
            jstring message = env->NewStringUTF(text);
            env->CallVoidMethod(sink, phase, message);
            env->DeleteLocalRef(message);
            if (env->ExceptionCheck()) throw std::runtime_error("Status callback failed");
        };
        const char * rawpath = env->GetStringUTFChars(filename, nullptr);
        if (!rawpath) return nullptr;
        std::string path(rawpath); env->ReleaseStringUTFChars(filename, rawpath);
        std::string user(env->GetArrayLength(input), '\0');
        env->GetByteArrayRegion(input, 0, user.size(), reinterpret_cast<jbyte *>(user.data()));
        if (env->ExceptionCheck()) return nullptr;
        std::string prompt = "<|im_start|>system\nYou are a helpful assistant. Answer briefly and honestly.<|im_end|>\n<|im_start|>user\n" + user + "\n/no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        status("Loading bundled model on CPU...");
        auto started = Clock::now();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0; mp.progress_callback = load_progress;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), mp), llama_model_free);
        check_stop();
        if (!model) throw std::runtime_error("Could not load bundled model; check available memory");
        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, false, true);
        if (count <= 0 || count > 768) throw std::runtime_error("Prompt is too long for the diagnostic context");
        std::vector<llama_token> tokens(count);
        count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), false, true);
        if (count <= 0) throw std::runtime_error("Prompt tokenization failed");
        auto cp = llama_context_default_params();
        cp.n_ctx = 1024; cp.n_batch = 128; cp.n_ubatch = 64;
        cp.n_threads = cp.n_threads_batch = std::clamp<int>(threads, 1, 4);
        cp.abort_callback = abort_run;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Could not allocate inference context");
        double load_ms = elapsed(started);
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(context.get()), llama_batch_ext_free);
        if (!batch) throw std::runtime_error("Could not allocate token batch");
        status("Processing prompt locally...");
        auto prefill = Clock::now();
        for (int pos = 0; pos < count; pos += 128) {
            check_stop();
            set_batch(batch.get(), tokens.data() + pos, std::min(128, count - pos), pos);
            if (llama_process(context.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get())) { check_stop(); throw std::runtime_error("Prompt evaluation failed"); }
        }
        double prefill_ms = elapsed(prefill);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Could not allocate sampler");
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        status("Generating locally; no server connection...");
        auto generation = Clock::now();
        int generated = 0; double first_ms = -1;
        for (; generated < 128; ) {
            check_stop();
            llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            std::vector<char> piece(256);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (n < 0) throw std::runtime_error("Could not decode generated token");
            jbyteArray bytes = env->NewByteArray(n);
            if (!bytes) return nullptr;
            env->SetByteArrayRegion(bytes, 0, n, reinterpret_cast<const jbyte *>(piece.data()));
            env->CallVoidMethod(sink, chunk, bytes); env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) return nullptr;
            if (generated == 0) first_ms = load_ms + prefill_ms + elapsed(generation);
            generated++;
            if (generated == 128) break;
            set_batch(batch.get(), &token, 1, count + generated - 1);
            if (llama_process(context.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get())) { check_stop(); throw std::runtime_error("Generation failed"); }
        }
        double values[] = {load_ms, prefill_ms, elapsed(generation), static_cast<double>(generated), first_ms};
        jdoubleArray result = env->NewDoubleArray(5);
        if (result) env->SetDoubleArrayRegion(result, 0, 5, values);
        return result;
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/RuntimeException"), error.what());
        return nullptr;
    }
}
