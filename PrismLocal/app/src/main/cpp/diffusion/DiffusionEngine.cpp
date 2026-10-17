#include "DiffusionEngine.hpp"

#include <android/log.h>
#include <chrono>
#include <mutex>
#include <cstdio>
#include <cstdlib>

#include "stable-diffusion.h"
#include "ggml.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#define LOG_TAG "PrismDiffusionNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void custom_sd_log(sd_log_level_t level, const char* text, void* /*data*/) {
    android_LogPriority prio = ANDROID_LOG_INFO;
    switch (level) {
        case SD_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case SD_LOG_INFO:  prio = ANDROID_LOG_INFO; break;
        case SD_LOG_WARN:  prio = ANDROID_LOG_WARN; break;
        case SD_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        default:           prio = ANDROID_LOG_VERBOSE; break;
    }
    __android_log_print(prio, LOG_TAG, "%s", text);
}

static void custom_ggml_abort(const char* error) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "FATAL GGML_ABORT: %s", error);
}

namespace prism::diffusion {

struct DiffusionEngine::Impl {
    // Serialize operations that create/use/free sd_ctx. Cancellation only needs
    // mu, so it can interrupt a generation while this lock is held.
    std::mutex operation_mu;
    mutable std::mutex mu;
    sd_ctx_t* sd_ctx = nullptr;
    std::atomic<bool> shutting_down{false};
    std::atomic<bool> cancel_requested{false};
    ProgressCallback active_progress_cb = nullptr;
    bool enable_vae_tiling = true;
};

static void sd_progress_adapter(int step, int steps, float time, void* data) {
    auto* impl = static_cast<DiffusionEngine::Impl*>(data);
    ProgressCallback callback;
    if (impl) {
        std::lock_guard<std::mutex> lock(impl->mu);
        callback = impl->active_progress_cb;
    }
    if (callback) callback(step, steps, time);
}

// stable-diffusion's progress callback is process-global, not context-local.
// Never let a second engine replace callback userdata while the first runs.
static std::mutex g_generation_mu;

static bool sd_cancel_eval(ggml_tensor*, bool ask, void* data) {
    // Request a callback after each graph node, not only after a complete
    // denoising step. Returning false on observation aborts backend compute.
    if (ask) return true;
    return !static_cast<std::atomic<bool>*>(data)->load(std::memory_order_acquire);
}

DiffusionEngine::DiffusionEngine() : impl_(std::make_unique<Impl>()) {
    sd_set_log_callback(custom_sd_log, nullptr);
    ggml_set_abort_callback(custom_ggml_abort);
}

DiffusionEngine::~DiffusionEngine() {
    shutdown();
    unload();
}

bool DiffusionEngine::loadModel(
    const std::string& model_path,
    const std::string& vae_path,
    const std::string& taesd_path,
    int n_threads,
    bool on_gpu,
    bool enable_vae_tiling
) {
    std::lock_guard<std::mutex> operation_lock(impl_->operation_mu);
    std::lock_guard<std::mutex> generation_lock(g_generation_mu);
    if (impl_->shutting_down.load()) return false;
    sd_ctx_t* previous = nullptr;
    bool accepted = false;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        previous = impl_->sd_ctx;
        impl_->sd_ctx = nullptr;
    }
    if (previous) free_sd_ctx(previous);

    impl_->enable_vae_tiling = enable_vae_tiling;

    // Snapdragon 7 Gen 3 topology: exactly 4 Performance cores (Cortex-A715)
    int threads_to_use = (n_threads > 0 && n_threads <= 4) ? n_threads : 4;

    LOGI("Loading diffusion model: %s (threads=%d, gpu=%d, vae_tiling=%d, taesd=%s)",
         model_path.c_str(), threads_to_use, on_gpu ? 1 : 0, enable_vae_tiling ? 1 : 0,
         taesd_path.empty() ? "NONE" : taesd_path.c_str());

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);

    params.model_path = model_path.c_str();
    if (!vae_path.empty()) {
        params.vae_path = vae_path.c_str();
    }
    if (!taesd_path.empty()) {
        params.taesd_path = taesd_path.c_str();
    }
    params.n_threads = threads_to_use;
    params.wtype = SD_TYPE_COUNT; // Auto-detect quantization (e.g. Q8_0)
    params.rng_type = STD_DEFAULT_RNG;
    params.enable_mmap = true;

    // Disable forced flash-attention for guaranteed stability on mobile ARM CPU and OpenCL
    params.diffusion_flash_attn = false;
    params.flash_attn = false;

    sd_ctx_t* ctx = new_sd_ctx(&params);
    if (!ctx) {
        LOGE("Failed to create sd_ctx for model: %s", model_path.c_str());
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        // Shutdown may have been requested while new_sd_ctx was loading.
        if (!impl_->shutting_down.load()) {
            impl_->sd_ctx = ctx;
            impl_->cancel_requested.store(false);
            accepted = true;
        }
    }
    if (!accepted) {
        free_sd_ctx(ctx);
        return false;
    }
    LOGI("Diffusion model loaded successfully: %s", sd_get_model_version_name(ctx));
    return true;
}

DiffusionResult DiffusionEngine::generate(
    const std::string& prompt,
    const std::string& negative_prompt,
    const std::string& output_file_path,
    int width,
    int height,
    int steps,
    float cfg_scale,
    int64_t seed,
    ProgressCallback progress_cb
) {
    std::lock_guard<std::mutex> operation_lock(impl_->operation_mu);
    DiffusionResult result;
    auto start_time = std::chrono::steady_clock::now();

    sd_ctx_t* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        ctx = impl_->sd_ctx;
        if (!ctx || impl_->shutting_down.load()) {
            result.error_message = impl_->shutting_down.load()
                ? "Diffusion engine is shutting down" : "Diffusion model is not loaded";
            return result;
        }
        impl_->cancel_requested.store(false);
        impl_->active_progress_cb = progress_cb;
    }

    std::lock_guard<std::mutex> generation_lock(g_generation_mu);
    sd_set_progress_callback(sd_progress_adapter, impl_.get());
    sd_set_backend_eval_callback(sd_cancel_eval, &impl_->cancel_requested);
    struct ProgressReset {
        Impl* impl;
        ~ProgressReset() {
            sd_set_backend_eval_callback(nullptr, nullptr);
            sd_set_progress_callback(nullptr, nullptr);
            std::lock_guard<std::mutex> lock(impl->mu);
            impl->active_progress_cb = nullptr;
        }
    } progress_reset{impl_.get()};

    sd_img_gen_params_t gen_params;
    sd_img_gen_params_init(&gen_params);

    gen_params.prompt = prompt.c_str();
    gen_params.negative_prompt = negative_prompt.c_str();
    gen_params.width = (width > 0 && width % 8 == 0) ? width : 512;
    gen_params.height = (height > 0 && height % 8 == 0) ? height : 512;
    gen_params.seed = seed;
    gen_params.sample_params.sample_steps = steps > 0 ? steps : 1;
    gen_params.sample_params.guidance.txt_cfg = cfg_scale;
    gen_params.batch_count = 1;
    gen_params.vae_tiling_params.enabled = impl_->enable_vae_tiling;

    // Use fast Euler A or model default
    gen_params.sample_params.sample_method = sd_get_default_sample_method(ctx);
    gen_params.sample_params.scheduler = sd_get_default_scheduler(ctx, gen_params.sample_params.sample_method);

    LOGI("Starting txt2img: '%s', steps=%d, cfg=%.2f, res=%dx%d",
         prompt.c_str(), gen_params.sample_params.sample_steps, cfg_scale, gen_params.width, gen_params.height);

    sd_image_t* images = nullptr;
    int num_images = 0;

    bool ok = generate_image(ctx, &gen_params, &images, &num_images);

    auto end_time = std::chrono::steady_clock::now();
    result.generation_time_sec = std::chrono::duration<float>(end_time - start_time).count();

    if (impl_->cancel_requested.load()) {
        result.error_message = "Generation cancelled by user";
        if (images) {
            for (int i = 0; i < num_images; ++i) {
                free(images[i].data);
            }
            free(images);
        }
        return result;
    }

    if (!ok || num_images <= 0 || !images || !images[0].data) {
        result.error_message = "Image generation failed";
        LOGE("generate_image failed or returned 0 images");
        if (images) {
            for (int i = 0; i < num_images; ++i) {
                free(images[i].data);
            }
            free(images);
        }
        return result;
    }

    result.width = images[0].width;
    result.height = images[0].height;
    result.channels = images[0].channel;

    if (!output_file_path.empty()) {
        int write_rc = stbi_write_png(
            output_file_path.c_str(),
            result.width,
            result.height,
            result.channels,
            images[0].data,
            result.width * result.channels
        );
        if (write_rc != 0) {
            result.output_path = output_file_path;
            LOGI("Saved generated image to file: %s (time: %.2fs)",
                 output_file_path.c_str(), result.generation_time_sec);
        } else {
            LOGW("Failed to write PNG to file: %s", output_file_path.c_str());
        }
    }

    // Also encode to memory PNG buffer
    int png_len = 0;
    unsigned char* png_mem = stbi_write_png_to_mem(
        images[0].data,
        0,
        result.width,
        result.height,
        result.channels,
        &png_len,
        nullptr
    );
    if (png_mem && png_len > 0) {
        result.png_bytes.assign(png_mem, png_mem + png_len);
        free(png_mem);
    }

    for (int i = 0; i < num_images; ++i) {
        free(images[i].data);
    }
    free(images);

    result.success = true;
    LOGI("generate_image finished successfully in %.2fs", result.generation_time_sec);
    return result;
}

void DiffusionEngine::cancel() {
    std::lock_guard<std::mutex> lock(impl_->mu);
    impl_->cancel_requested.store(true);
    if (impl_->sd_ctx) {
        sd_cancel_generation(impl_->sd_ctx, SD_CANCEL_ALL);
    }
}

void DiffusionEngine::unload() {
    std::lock_guard<std::mutex> operation_lock(impl_->operation_mu);
    std::lock_guard<std::mutex> generation_lock(g_generation_mu);
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (impl_->sd_ctx) {
        LOGI("Unloading diffusion context");
        free_sd_ctx(impl_->sd_ctx);
        impl_->sd_ctx = nullptr;
    }
}

void DiffusionEngine::shutdown() {
    impl_->shutting_down.store(true);
    cancel();
}

bool DiffusionEngine::isLoaded() const {
    std::lock_guard<std::mutex> lock(impl_->mu);
    return impl_->sd_ctx != nullptr;
}

} // namespace prism::diffusion
