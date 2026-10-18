#include "DiffusionEngine.hpp"

#include <android/log.h>
#include <chrono>
#include <mutex>
#include <cstdio>
#include <cstdlib>

#include "stable-diffusion.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "../third_party/llama.cpp/vendor/nlohmann/json.hpp"

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
    sample_method_t sampler = SAMPLE_METHOD_COUNT;
    bool on_gpu = false;
    std::atomic<uint64_t> eval_nodes{0};
    std::atomic<uint64_t> observed_gpu_nodes{0};
    nlohmann::ordered_json backend_info = {{"status", "BACKEND_NOT_VERIFIED"}};
};

static void sd_progress_adapter(int step, int steps, float time, void* data) {
    LOGI("diffusion_progress step=%d total=%d", step, steps);
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

static bool sd_cancel_eval(ggml_tensor* tensor, bool ask, void* data) {
    // Request a callback after each graph node, not only after a complete
    // denoising step. Returning false on observation aborts backend compute.
    auto* impl = static_cast<DiffusionEngine::Impl*>(data);
    if (ask) {
        if (impl->on_gpu && impl->eval_nodes.fetch_add(1) % 64 == 0) {
            __android_log_print(ANDROID_LOG_INFO, "PrismDiffusion", "graph_node=%llu op=%s buffer=%s",
                static_cast<unsigned long long>(impl->eval_nodes.load()), ggml_op_name(tensor->op),
                tensor->buffer ? ggml_backend_buffer_name(tensor->buffer) : "VIEW_OR_UNALLOCATED");
        }
        return true;
    }
    const bool math_node = tensor->op == GGML_OP_MUL_MAT || tensor->op == GGML_OP_CONV_2D || tensor->op == GGML_OP_IM2COL;
    if (impl->on_gpu && math_node && tensor->buffer &&
        std::string(ggml_backend_buffer_name(tensor->buffer)).find("OpenCL") != std::string::npos) {
        impl->observed_gpu_nodes.fetch_add(1);
    }
    return !impl->cancel_requested.load(std::memory_order_acquire);
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
    return loadModelWithBackend(model_path, vae_path, taesd_path, n_threads,
        on_gpu ? "GPU_OPENCL" : "CPU", enable_vae_tiling, false);
}

std::string DiffusionEngine::backendInfo() const {
    std::lock_guard<std::mutex> lock(impl_->mu);
    auto info = impl_->backend_info;
    info["completed_opencl_math_nodes"] = impl_->observed_gpu_nodes.load();
    return info.dump();
}

bool DiffusionEngine::loadModelWithBackend(const std::string& model_path,
    const std::string& vae_path, const std::string& taesd_path, int n_threads,
    const std::string& mode, bool enable_vae_tiling, bool hybrid) {
    std::lock_guard<std::mutex> operation_lock(impl_->operation_mu);
    std::lock_guard<std::mutex> generation_lock(g_generation_mu);
    if (impl_->shutting_down.load()) return false;
    if (mode != "CPU" && mode != "GPU_OPENCL" && mode != "AUTO") return false;
    impl_->observed_gpu_nodes.store(0);
    std::string fallback;
    bool on_gpu = mode != "CPU";
    nlohmann::ordered_json info = {{"requested", mode}, {"selected", "NONE"},
        {"status", "BACKEND_NOT_VERIFIED"}, {"hybrid", hybrid}};
    auto publish = [&]() {
        std::lock_guard<std::mutex> lock(impl_->mu);
        impl_->backend_info = info;
        __android_log_print(ANDROID_LOG_INFO, "PrismDiffusion", "%s", info.dump().c_str());
    };
    publish();
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
    if (on_gpu) {
        const size_t size = sd_list_devices(nullptr, 0);
        std::vector<char> devices(size + 1);
        sd_list_devices(devices.data(), devices.size());
        LOGI("Available diffusion devices: %s", devices.data());
        if (std::string(devices.data()).find("GPUOpenCL\t") == std::string::npos) {
            fallback = "GPUOpenCL device unavailable";
        } else {
            auto* device = ggml_backend_dev_by_name("GPUOpenCL");
            size_t free = 0, total = 0;
            ggml_backend_dev_memory(device, &free, &total);
            info["gpu_candidate_device"] = ggml_backend_dev_description(device);
            info["gpu_memory_query_available"] = total > 0;
            info["gpu_candidate_free_bytes"] = total > 0 ? nlohmann::ordered_json(free) : nlohmann::ordered_json(nullptr);
            info["gpu_candidate_total_bytes"] = total > 0 ? nlohmann::ordered_json(total) : nlohmann::ordered_json(nullptr);
            // Basic reserve; graph/tensor allocations still have their own checked
            // failure paths. Advertised shared memory is not guaranteed available.
            if (total > 0 && free < 256ULL * 1024 * 1024) fallback = "Less than 256 MiB device memory available";
        }
        if (!fallback.empty()) {
            info["fallback_reason"] = fallback;
            if (mode == "GPU_OPENCL") { publish(); return false; }
            on_gpu = false;
        }
    }
    if (on_gpu) {
        params.backend = hybrid ? "CPU,diffusion=GPUOpenCL" : "GPUOpenCL";
        params.params_backend = params.backend;
        // Existing ggml kernels include a tiled Qualcomm F16/F32 convolution.
        // Avoid materializing im2col and generic matmul for image convolutions.
        params.diffusion_conv_direct = true;
        params.vae_conv_direct = !hybrid;
    } else {
        params.backend = "CPU";
        params.params_backend = "CPU";
    }
    params.wtype = SD_TYPE_COUNT; // Auto-detect quantization (e.g. Q8_0)
    params.rng_type = STD_DEFAULT_RNG;
    params.enable_mmap = true;
    params.auto_fit = false;

    // Disable forced flash-attention for guaranteed stability on mobile ARM CPU and OpenCL
    params.diffusion_flash_attn = false;
    params.flash_attn = false;

    sd_ctx_t* ctx = new_sd_ctx(&params);
    if (!ctx && on_gpu && mode == "AUTO") {
        fallback = "OpenCL context initialization failed";
        on_gpu = false;
        params.backend = "CPU"; params.params_backend = "CPU";
        params.diffusion_conv_direct = false; params.vae_conv_direct = false;
        ctx = new_sd_ctx(&params);
    }
    info["selected"] = ctx ? (on_gpu ? "GPU_OPENCL" : "CPU") : "NONE";
    if (ctx) {
        auto* selected_device = on_gpu ? ggml_backend_dev_by_name("GPUOpenCL")
            : ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        info["device"] = selected_device ? ggml_backend_dev_description(selected_device) : "UNKNOWN";
    }
    info["diffusion_backend"] = on_gpu ? "GPUOpenCL" : "CPU";
    info["clip_backend"] = on_gpu && !hybrid ? "GPUOpenCL" : "CPU";
    info["vae_backend"] = on_gpu && !hybrid ? "GPUOpenCL" : "CPU";
    info["fallback_reason"] = fallback;
    info["loaded"] = ctx != nullptr;
    // Public SD API exposes no per-context executed-backend getter. Assignment
    // alone is never reported as verified compute; retain native graph evidence.
    publish();
    impl_->on_gpu = on_gpu;
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
        info["loaded"] = false; info["selected"] = "NONE";
        info["error"] = "Shutdown requested during model load";
        publish();
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
    sample_method_t sampler;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        ctx = impl_->sd_ctx;
        sampler = impl_->sampler;
        if (!ctx || impl_->shutting_down.load()) {
            result.error_message = impl_->shutting_down.load()
                ? "Diffusion engine is shutting down" : "Diffusion model is not loaded";
            return result;
        }
        impl_->cancel_requested.store(false);
        impl_->active_progress_cb = progress_cb;
        impl_->backend_info["status"] = "BACKEND_NOT_VERIFIED";
        impl_->backend_info["last_generation"] = "running";
    }

    std::lock_guard<std::mutex> generation_lock(g_generation_mu);
    sd_set_progress_callback(sd_progress_adapter, impl_.get());
    impl_->eval_nodes.store(0);
    impl_->observed_gpu_nodes.store(0);
    sd_set_backend_eval_callback(sd_cancel_eval, impl_.get());
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
    gen_params.sample_params.sample_method = sampler == SAMPLE_METHOD_COUNT ? sd_get_default_sample_method(ctx) : sampler;
    gen_params.sample_params.scheduler = sd_get_default_scheduler(ctx, gen_params.sample_params.sample_method);
    if (sampler == LCM_SAMPLE_METHOD) gen_params.sample_params.scheduler = LCM_SCHEDULER;

    LOGI("Starting txt2img: '%s', steps=%d, cfg=%.2f, res=%dx%d",
         prompt.c_str(), gen_params.sample_params.sample_steps, cfg_scale, gen_params.width, gen_params.height);

    sd_image_t* images = nullptr;
    int num_images = 0;

    bool ok = generate_image(ctx, &gen_params, &images, &num_images);

    auto end_time = std::chrono::steady_clock::now();
    result.generation_time_sec = std::chrono::duration<float>(end_time - start_time).count();

    if (impl_->cancel_requested.load()) {
        {
            std::lock_guard<std::mutex> lock(impl_->mu);
            impl_->backend_info["last_generation"] = "cancelled";
        }
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
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        impl_->backend_info["last_generation"] = "completed";
        impl_->backend_info["status"] = impl_->on_gpu
            ? (impl_->observed_gpu_nodes.load() > 0 ? "STABLE_DIFFUSION_GPU_VERIFICADO" : "BACKEND_NOT_VERIFIED")
            : "CPU_IMAGE_VERIFIED";
    }
    LOGI("generate_image finished successfully in %.2fs", result.generation_time_sec);
    return result;
}

bool DiffusionEngine::configureSampler(const std::string& name) {
    auto sampler = name == "default" ? SAMPLE_METHOD_COUNT : str_to_sample_method(name.c_str());
    if (name != "default" && sampler == SAMPLE_METHOD_COUNT) return false;
    std::lock_guard<std::mutex> lock(impl_->mu);
    impl_->sampler = sampler;
    return true;
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
