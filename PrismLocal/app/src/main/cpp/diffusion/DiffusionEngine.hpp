#pragma once

#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <functional>
#include <cstdint>

namespace prism::diffusion {

struct DiffusionResult {
    bool success = false;
    int width = 0;
    int height = 0;
    int channels = 0;
    std::string output_path;
    std::vector<uint8_t> png_bytes;
    std::string error_message;
    float generation_time_sec = 0.0f;
};

using ProgressCallback = std::function<void(int step, int total_steps, float time_sec)>;

class DiffusionEngine {
public:
    DiffusionEngine();
    ~DiffusionEngine();

    bool loadModel(
        const std::string& model_path,
        const std::string& vae_path = "",
        const std::string& taesd_path = "",
        int n_threads = 4,
        bool on_gpu = true,
        bool enable_vae_tiling = true
    );

    DiffusionResult generate(
        const std::string& prompt,
        const std::string& negative_prompt = "",
        const std::string& output_file_path = "",
        int width = 512,
        int height = 512,
        int steps = 4,
        float cfg_scale = 1.0f,
        int64_t seed = 42,
        ProgressCallback progress_cb = nullptr
    );

    void cancel();
    void unload();
    bool isLoaded() const;

    struct Impl;
private:
    std::unique_ptr<Impl> impl_;
};

} // namespace prism::diffusion
