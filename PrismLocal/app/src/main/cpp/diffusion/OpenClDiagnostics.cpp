#include "OpenClDiagnostics.hpp"
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include "stable-diffusion.h"
#include "../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <stdexcept>
#include <vector>

#if LLMHOST_OPENCL_ENABLED
#include <CL/cl.h>
extern "C" const char* prism_opencl_driver_path(void);
#endif

namespace prism::diffusion {
using Json = nlohmann::ordered_json;
static void log(const char* tag, const Json& value) {
    __android_log_print(ANDROID_LOG_INFO, tag, "%s", value.dump().c_str());
}

// Both runs use explicit GGML backends. No scheduler or implicit CPU fallback.
static std::vector<float> matrix(ggml_backend_dev_t device, Json& result) {
    auto* backend = ggml_backend_dev_init(device, nullptr);
    if (!backend) throw std::runtime_error("ggml backend initialization failed");
    auto* ctx = ggml_init({4 * 1024 * 1024, nullptr, true});
    if (!ctx) { ggml_backend_free(backend); throw std::runtime_error("ggml context allocation failed"); }
    ggml_backend_buffer_t buffer = nullptr;
    try {
        auto* a = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 32, 32);
        auto* b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 32, 32);
        auto* c = ggml_mul_mat(ctx, a, b);
        if (!ggml_backend_dev_supports_op(device, c)) throw std::runtime_error("MUL_MAT unsupported on selected device");
        auto* graph = ggml_new_graph_custom(ctx, 16, false);
        ggml_build_forward_expand(graph, c);
        buffer = ggml_backend_alloc_ctx_tensors(ctx, backend);
        if (!buffer) throw std::runtime_error("ggml tensor buffer allocation failed");
        std::vector<float> inputA(1024), inputB(1024), output(1024);
        for (int i = 0; i < 1024; ++i) {
            inputA[i] = (i % 11 - 5) * 0.125f;
            inputB[i] = (i % 7 - 3) * 0.25f;
        }
        ggml_backend_tensor_set(a, inputA.data(), 0, inputA.size() * sizeof(float));
        ggml_backend_tensor_set(b, inputB.data(), 0, inputB.size() * sizeof(float));
        const auto started = std::chrono::steady_clock::now();
        const auto status = ggml_backend_graph_compute(backend, graph);
        ggml_backend_synchronize(backend);
        if (status != GGML_STATUS_SUCCESS) throw std::runtime_error("ggml graph execution failed: " + std::to_string(status));
        ggml_backend_tensor_get(c, output.data(), 0, output.size() * sizeof(float));
        result = {{"device", ggml_backend_dev_name(device)}, {"backend", ggml_backend_name(backend)},
                  {"device_type", ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_GPU ? "GPU" : "CPU"},
                  {"milliseconds", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count()}};
        ggml_backend_buffer_free(buffer); ggml_free(ctx); ggml_backend_free(backend);
        return output;
    } catch (...) {
        if (buffer) ggml_backend_buffer_free(buffer);
        ggml_free(ctx); ggml_backend_free(backend);
        throw;
    }
}

std::string openClDiagnostics(bool compute) {
    Json report = {{"status", "GPU_NO_DETECTADA"}, {"runtime_available", false}, {"compute_verified", false}};
#if LLMHOST_OPENCL_ENABLED
    report["driver_library"] = prism_opencl_driver_path();
    cl_uint platformCount = 0;
    cl_int status = clGetPlatformIDs(0, nullptr, &platformCount);
    report["platform_query_status"] = status;
    report["platform_count"] = platformCount;
    report["platforms"] = Json::array();
    if (status != CL_SUCCESS || platformCount == 0 || platformCount > 16) {
        report["error"] = "OpenCL platform enumeration failed or invalid count";
        log("PrismOpenCL", report); return report.dump();
    }
    std::vector<cl_platform_id> platforms(platformCount);
    status = clGetPlatformIDs(platformCount, platforms.data(), nullptr);
    if (status != CL_SUCCESS) {
        report["error"] = "OpenCL platform list failed: " + std::to_string(status);
        log("PrismOpenCL", report); return report.dump();
    }
    report["runtime_available"] = true;
    bool gpuFound = false;
    for (auto platform : platforms) {
        auto platformString = [&](cl_platform_info key) {
            char value[2048] = {};
            cl_int error = clGetPlatformInfo(platform, key, sizeof(value), value, nullptr);
            return error == CL_SUCCESS ? std::string(value) : "QUERY_FAILED:" + std::to_string(error);
        };
        Json p = {{"name", platformString(CL_PLATFORM_NAME)}, {"vendor", platformString(CL_PLATFORM_VENDOR)},
                  {"version", platformString(CL_PLATFORM_VERSION)}, {"devices", Json::array()}};
        cl_uint count = 0;
        status = clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, 0, nullptr, &count);
        p["device_query_status"] = status;
        if (status == CL_SUCCESS && count > 0 && count <= 64) {
            std::vector<cl_device_id> devices(count);
            status = clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, count, devices.data(), nullptr);
            p["device_list_status"] = status;
            if (status == CL_SUCCESS) for (auto device : devices) {
                auto info = [&](cl_device_info key) {
                    char value[2048] = {};
                    cl_int error = clGetDeviceInfo(device, key, sizeof(value), value, nullptr);
                    return error == CL_SUCCESS ? std::string(value) : "QUERY_FAILED:" + std::to_string(error);
                };
                cl_device_type type = 0; cl_ulong memory = 0, maxAllocation = 0;
                const auto typeStatus = clGetDeviceInfo(device, CL_DEVICE_TYPE, sizeof(type), &type, nullptr);
                const auto memoryStatus = clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(memory), &memory, nullptr);
                const auto allocationStatus = clGetDeviceInfo(device, CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeof(maxAllocation), &maxAllocation, nullptr);
                const bool gpu = typeStatus == CL_SUCCESS && (type & CL_DEVICE_TYPE_GPU);
                gpuFound |= gpu;
                Json d = {{"name", info(CL_DEVICE_NAME)}, {"vendor", info(CL_DEVICE_VENDOR)},
                          {"type", gpu ? "GPU" : (type & CL_DEVICE_TYPE_CPU) ? "CPU" : "OTHER_OR_UNKNOWN"},
                          {"opencl_version", info(CL_DEVICE_VERSION)}, {"driver_version", info(CL_DRIVER_VERSION)},
                          {"global_memory_bytes", memoryStatus == CL_SUCCESS ? Json(memory) : Json(nullptr)},
                          {"max_allocation_bytes", allocationStatus == CL_SUCCESS ? Json(maxAllocation) : Json(nullptr)},
                          {"type_query_status", typeStatus}, {"memory_query_status", memoryStatus}, {"allocation_query_status", allocationStatus}};
                p["devices"].push_back(d); log("PrismOpenCL", d);
            }
        }
        report["platforms"].push_back(p);
    }
    if (gpuFound) report["status"] = "GPU_DETECTADA_SIN_COMPUTO";
#else
    report["error"] = "This CPU build does not include OpenCL";
#endif
    const size_t size = sd_list_devices(nullptr, 0);
    std::vector<char> list(size + 1); sd_list_devices(list.data(), list.size());
    report["ggml_devices"] = list.data();
    if (compute) {
        try {
            auto* gpu = ggml_backend_dev_by_name("GPUOpenCL");
            auto* cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
            if (!gpu || !cpu || ggml_backend_dev_type(gpu) != GGML_BACKEND_DEVICE_TYPE_GPU)
                throw std::runtime_error("Explicit GPUOpenCL or CPU device unavailable");
            Json cpuReport, gpuReport;
            const auto reference = matrix(cpu, cpuReport);
            const auto actual = matrix(gpu, gpuReport);
            float error = 0;
            for (size_t i = 0; i < reference.size(); ++i) {
                if (!std::isfinite(actual[i])) throw std::runtime_error("GPU produced non-finite result");
                error = std::max(error, std::fabs(actual[i] - reference[i]));
            }
            report["cpu_matrix"] = cpuReport; report["gpu_matrix"] = gpuReport;
            report["max_absolute_error"] = error; report["tolerance"] = 0.001;
            report["compute_verified"] = error <= 0.001f;
            if (error <= 0.001f) report["status"] = "GPU_COMPUTO_VERIFICADO";
            else report["error"] = "CPU/GPU numerical mismatch";
        } catch (const std::exception& error) { report["error"] = error.what(); }
    }
    log("PrismGPU", report); return report.dump();
}
}
