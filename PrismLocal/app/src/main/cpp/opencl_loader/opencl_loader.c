#include <CL/cl.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdatomic.h>
#include <android/log.h>

#define LOG_TAG "OpenCLLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void* g_opencl_handle = NULL;
static pthread_once_t g_init_once = PTHREAD_ONCE_INIT;
static const char* g_driver_path = "UNAVAILABLE";

static void* load_symbol(const char* name) {
    if (!g_opencl_handle) return NULL;
    dlerror();
    void* symbol = dlsym(g_opencl_handle, name);
    const char* error = dlerror();
    if (error) LOGE("dlsym(%s): %s", name, error);
    return error ? NULL : symbol;
}

static void init_opencl_lib(void) {
    static const char* candidate_paths[] = {
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "libOpenCL.so",
        NULL
    };

    for (int i = 0; candidate_paths[i] != NULL; ++i) {
        dlerror();
        g_opencl_handle = dlopen(candidate_paths[i], RTLD_NOW | RTLD_LOCAL);
        if (g_opencl_handle) {
            // This shim also has the SONAME libOpenCL.so. Never resolve calls
            // back to ourselves, which would recurse until stack exhaustion.
            void* entry = dlsym(g_opencl_handle, "clGetPlatformIDs");
            if (!entry || entry == (void*)&clGetPlatformIDs) {
                LOGE("Rejected self/invalid OpenCL driver: %s", candidate_paths[i]);
                dlclose(g_opencl_handle);
                g_opencl_handle = NULL;
                continue;
            }
            LOGI("Successfully loaded OpenCL library from: %s", candidate_paths[i]);
            g_driver_path = candidate_paths[i];
            return;
        }
        const char* error = dlerror();
        LOGW("dlopen(%s): %s", candidate_paths[i], error ? error : "unknown error");
    }

    LOGW("Could not find or dlopen libOpenCL.so from any standard vendor path");
}

static void ensure_opencl_loaded(void) {
    pthread_once(&g_init_once, init_opencl_lib);
}

const char* prism_opencl_driver_path(void) {
    ensure_opencl_loaded();
    return g_driver_path;
}

#define RESOLVE_FN(name) \
    typedef typeof(name)* fn_##name; \
    static _Atomic(void*) cached_##name = NULL; \
    ensure_opencl_loaded(); \
    void* resolved_##name = atomic_load_explicit(&cached_##name, memory_order_acquire); \
    if (!resolved_##name) { \
        resolved_##name = load_symbol(#name); \
        if (resolved_##name == (void*)&name) { \
            LOGE("Rejected wrapper recursion for %s", #name); \
            resolved_##name = NULL; \
        } \
        atomic_store_explicit(&cached_##name, resolved_##name, memory_order_release); \
    } \
    fn_##name p_##name = (fn_##name)resolved_##name;

// 1. Platform & Device APIs
CL_API_ENTRY cl_int CL_API_CALL
clGetPlatformIDs(cl_uint num_entries, cl_platform_id *platforms, cl_uint *num_platforms) {
    RESOLVE_FN(clGetPlatformIDs);
    if (!p_clGetPlatformIDs) return CL_DEVICE_NOT_FOUND;
    return p_clGetPlatformIDs(num_entries, platforms, num_platforms);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetPlatformInfo(cl_platform_id platform, cl_platform_info param_name,
                  size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    RESOLVE_FN(clGetPlatformInfo);
    if (!p_clGetPlatformInfo) return CL_INVALID_PLATFORM;
    return p_clGetPlatformInfo(platform, param_name, param_value_size, param_value, param_value_size_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type,
               cl_uint num_entries, cl_device_id *devices, cl_uint *num_devices) {
    RESOLVE_FN(clGetDeviceIDs);
    if (!p_clGetDeviceIDs) return CL_DEVICE_NOT_FOUND;
    return p_clGetDeviceIDs(platform, device_type, num_entries, devices, num_devices);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetDeviceInfo(cl_device_id device, cl_device_info param_name,
                size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    RESOLVE_FN(clGetDeviceInfo);
    if (!p_clGetDeviceInfo) return CL_INVALID_DEVICE;
    return p_clGetDeviceInfo(device, param_name, param_value_size, param_value, param_value_size_ret);
}

// 2. Context APIs
CL_API_ENTRY cl_context CL_API_CALL
clCreateContext(const cl_context_properties *properties, cl_uint num_devices,
                const cl_device_id *devices,
                void (CL_CALLBACK *pfn_notify)(const char *, const void *, size_t, void *),
                void *user_data, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateContext);
    if (!p_clCreateContext) {
        if (errcode_ret) *errcode_ret = CL_DEVICE_NOT_FOUND;
        return NULL;
    }
    return p_clCreateContext(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clReleaseContext(cl_context context) {
    RESOLVE_FN(clReleaseContext);
    if (!p_clReleaseContext) return CL_INVALID_CONTEXT;
    return p_clReleaseContext(context);
}

// 3. Command Queue APIs
CL_API_ENTRY cl_command_queue CL_API_CALL
clCreateCommandQueue(cl_context context, cl_device_id device,
                     cl_command_queue_properties properties, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateCommandQueue);
    if (!p_clCreateCommandQueue) {
        if (errcode_ret) *errcode_ret = CL_INVALID_CONTEXT;
        return NULL;
    }
    return p_clCreateCommandQueue(context, device, properties, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clReleaseCommandQueue(cl_command_queue command_queue) {
    RESOLVE_FN(clReleaseCommandQueue);
    if (!p_clReleaseCommandQueue) return CL_INVALID_COMMAND_QUEUE;
    return p_clReleaseCommandQueue(command_queue);
}

// 4. Memory Object APIs
CL_API_ENTRY cl_mem CL_API_CALL
clCreateBuffer(cl_context context, cl_mem_flags flags, size_t size,
               void *host_ptr, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateBuffer);
    if (!p_clCreateBuffer) {
        if (errcode_ret) *errcode_ret = CL_INVALID_CONTEXT;
        return NULL;
    }
    return p_clCreateBuffer(context, flags, size, host_ptr, errcode_ret);
}

CL_API_ENTRY cl_mem CL_API_CALL
clCreateSubBuffer(cl_mem buffer, cl_mem_flags flags,
                  cl_buffer_create_type buffer_create_type,
                  const void *buffer_create_info, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateSubBuffer);
    if (!p_clCreateSubBuffer) {
        if (errcode_ret) *errcode_ret = CL_INVALID_MEM_OBJECT;
        return NULL;
    }
    return p_clCreateSubBuffer(buffer, flags, buffer_create_type, buffer_create_info, errcode_ret);
}

CL_API_ENTRY cl_mem CL_API_CALL
clCreateBufferWithProperties(cl_context context, const cl_mem_properties *properties,
                             cl_mem_flags flags, size_t size, void *host_ptr, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateBufferWithProperties);
    if (!p_clCreateBufferWithProperties) {
        return clCreateBuffer(context, flags, size, host_ptr, errcode_ret);
    }
    return p_clCreateBufferWithProperties(context, properties, flags, size, host_ptr, errcode_ret);
}

CL_API_ENTRY cl_mem CL_API_CALL
clCreateImage(cl_context context, cl_mem_flags flags, const cl_image_format *image_format,
              const cl_image_desc *image_desc, void *host_ptr, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateImage);
    if (!p_clCreateImage) {
        if (errcode_ret) *errcode_ret = CL_INVALID_CONTEXT;
        return NULL;
    }
    return p_clCreateImage(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clRetainMemObject(cl_mem memobj) {
    RESOLVE_FN(clRetainMemObject);
    if (!p_clRetainMemObject) return CL_INVALID_MEM_OBJECT;
    return p_clRetainMemObject(memobj);
}

CL_API_ENTRY cl_int CL_API_CALL
clReleaseMemObject(cl_mem memobj) {
    RESOLVE_FN(clReleaseMemObject);
    if (!p_clReleaseMemObject) return CL_INVALID_MEM_OBJECT;
    return p_clReleaseMemObject(memobj);
}

// 5. Enqueue Read/Write/Copy Buffer APIs
CL_API_ENTRY cl_int CL_API_CALL
clEnqueueReadBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read,
                    size_t offset, size_t size, void *ptr,
                    cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    RESOLVE_FN(clEnqueueReadBuffer);
    if (!p_clEnqueueReadBuffer) return CL_INVALID_COMMAND_QUEUE;
    return p_clEnqueueReadBuffer(command_queue, buffer, blocking_read, offset, size, ptr,
                                 num_events_in_wait_list, event_wait_list, event);
}

CL_API_ENTRY cl_int CL_API_CALL
clEnqueueWriteBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write,
                     size_t offset, size_t size, const void *ptr,
                     cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    RESOLVE_FN(clEnqueueWriteBuffer);
    if (!p_clEnqueueWriteBuffer) return CL_INVALID_COMMAND_QUEUE;
    return p_clEnqueueWriteBuffer(command_queue, buffer, blocking_write, offset, size, ptr,
                                  num_events_in_wait_list, event_wait_list, event);
}

CL_API_ENTRY cl_int CL_API_CALL
clEnqueueCopyBuffer(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer,
                    size_t src_offset, size_t dst_offset, size_t size,
                    cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    RESOLVE_FN(clEnqueueCopyBuffer);
    if (!p_clEnqueueCopyBuffer) return CL_INVALID_COMMAND_QUEUE;
    return p_clEnqueueCopyBuffer(command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size,
                                 num_events_in_wait_list, event_wait_list, event);
}

CL_API_ENTRY cl_int CL_API_CALL
clEnqueueFillBuffer(cl_command_queue command_queue, cl_mem buffer,
                    const void *pattern, size_t pattern_size,
                    size_t offset, size_t size,
                    cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    RESOLVE_FN(clEnqueueFillBuffer);
    if (!p_clEnqueueFillBuffer) return CL_INVALID_COMMAND_QUEUE;
    return p_clEnqueueFillBuffer(command_queue, buffer, pattern, pattern_size, offset, size,
                                 num_events_in_wait_list, event_wait_list, event);
}

// 6. Program APIs
CL_API_ENTRY cl_program CL_API_CALL
clCreateProgramWithSource(cl_context context, cl_uint count, const char **strings,
                          const size_t *lengths, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateProgramWithSource);
    if (!p_clCreateProgramWithSource) {
        if (errcode_ret) *errcode_ret = CL_INVALID_CONTEXT;
        return NULL;
    }
    return p_clCreateProgramWithSource(context, count, strings, lengths, errcode_ret);
}

CL_API_ENTRY cl_program CL_API_CALL
clCreateProgramWithBinary(cl_context context, cl_uint num_devices,
                          const cl_device_id *device_list, const size_t *lengths,
                          const unsigned char **binaries, cl_int *binary_status, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateProgramWithBinary);
    if (!p_clCreateProgramWithBinary) {
        if (errcode_ret) *errcode_ret = CL_INVALID_CONTEXT;
        return NULL;
    }
    return p_clCreateProgramWithBinary(context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clBuildProgram(cl_program program, cl_uint num_devices, const cl_device_id *device_list,
               const char *options, void (CL_CALLBACK *pfn_notify)(cl_program, void *), void *user_data) {
    RESOLVE_FN(clBuildProgram);
    if (!p_clBuildProgram) return CL_INVALID_PROGRAM;
    return p_clBuildProgram(program, num_devices, device_list, options, pfn_notify, user_data);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetProgramBuildInfo(cl_program program, cl_device_id device, cl_program_build_info param_name,
                      size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    RESOLVE_FN(clGetProgramBuildInfo);
    if (!p_clGetProgramBuildInfo) return CL_INVALID_PROGRAM;
    return p_clGetProgramBuildInfo(program, device, param_name, param_value_size, param_value, param_value_size_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clReleaseProgram(cl_program program) {
    RESOLVE_FN(clReleaseProgram);
    if (!p_clReleaseProgram) return CL_INVALID_PROGRAM;
    return p_clReleaseProgram(program);
}

// 7. Kernel APIs
CL_API_ENTRY cl_kernel CL_API_CALL
clCreateKernel(cl_program program, const char *kernel_name, cl_int *errcode_ret) {
    RESOLVE_FN(clCreateKernel);
    if (!p_clCreateKernel) {
        if (errcode_ret) *errcode_ret = CL_INVALID_PROGRAM;
        return NULL;
    }
    return p_clCreateKernel(program, kernel_name, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clSetKernelArg(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void *arg_value) {
    RESOLVE_FN(clSetKernelArg);
    if (!p_clSetKernelArg) return CL_INVALID_KERNEL;
    return p_clSetKernelArg(kernel, arg_index, arg_size, arg_value);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetKernelWorkGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name,
                         size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    RESOLVE_FN(clGetKernelWorkGroupInfo);
    if (!p_clGetKernelWorkGroupInfo) return CL_INVALID_KERNEL;
    return p_clGetKernelWorkGroupInfo(kernel, device, param_name, param_value_size, param_value, param_value_size_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetKernelInfo(cl_kernel kernel, cl_kernel_info param_name,
                size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    RESOLVE_FN(clGetKernelInfo);
    if (!p_clGetKernelInfo) return CL_INVALID_KERNEL;
    return p_clGetKernelInfo(kernel, param_name, param_value_size, param_value, param_value_size_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetKernelSubGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name,
                        size_t input_value_size, const void *input_value,
                        size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    RESOLVE_FN(clGetKernelSubGroupInfo);
    if (!p_clGetKernelSubGroupInfo) return CL_INVALID_KERNEL;
    return p_clGetKernelSubGroupInfo(kernel, device, param_name, input_value_size, input_value,
                                     param_value_size, param_value, param_value_size_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clReleaseKernel(cl_kernel kernel) {
    RESOLVE_FN(clReleaseKernel);
    if (!p_clReleaseKernel) return CL_INVALID_KERNEL;
    return p_clReleaseKernel(kernel);
}

// 8. Enqueue Execution & Sync APIs
CL_API_ENTRY cl_int CL_API_CALL
clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim,
                       const size_t *global_work_offset, const size_t *global_work_size,
                       const size_t *local_work_size, cl_uint num_events_in_wait_list,
                       const cl_event *event_wait_list, cl_event *event) {
    RESOLVE_FN(clEnqueueNDRangeKernel);
    if (!p_clEnqueueNDRangeKernel) return CL_INVALID_COMMAND_QUEUE;
    return p_clEnqueueNDRangeKernel(command_queue, kernel, work_dim, global_work_offset,
                                    global_work_size, local_work_size,
                                    num_events_in_wait_list, event_wait_list, event);
}

CL_API_ENTRY cl_int CL_API_CALL
clFinish(cl_command_queue command_queue) {
    RESOLVE_FN(clFinish);
    if (!p_clFinish) return CL_INVALID_COMMAND_QUEUE;
    return p_clFinish(command_queue);
}

CL_API_ENTRY cl_int CL_API_CALL
clFlush(cl_command_queue command_queue) {
    RESOLVE_FN(clFlush);
    if (!p_clFlush) return CL_INVALID_COMMAND_QUEUE;
    return p_clFlush(command_queue);
}

// 9. Event APIs
CL_API_ENTRY cl_int CL_API_CALL
clWaitForEvents(cl_uint num_events, const cl_event *event_list) {
    RESOLVE_FN(clWaitForEvents);
    if (!p_clWaitForEvents) return CL_INVALID_EVENT;
    return p_clWaitForEvents(num_events, event_list);
}

CL_API_ENTRY cl_int CL_API_CALL
clReleaseEvent(cl_event event) {
    RESOLVE_FN(clReleaseEvent);
    if (!p_clReleaseEvent) return CL_INVALID_EVENT;
    return p_clReleaseEvent(event);
}

CL_API_ENTRY cl_int CL_API_CALL
clGetEventProfilingInfo(cl_event event, cl_profiling_info param_name,
                        size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    RESOLVE_FN(clGetEventProfilingInfo);
    if (!p_clGetEventProfilingInfo) return CL_INVALID_EVENT;
    return p_clGetEventProfilingInfo(event, param_name, param_value_size, param_value, param_value_size_ret);
}

CL_API_ENTRY cl_int CL_API_CALL
clEnqueueBarrierWithWaitList(cl_command_queue command_queue,
                            cl_uint num_events_in_wait_list,
                            const cl_event *event_wait_list,
                            cl_event *event) {
    RESOLVE_FN(clEnqueueBarrierWithWaitList);
    if (!p_clEnqueueBarrierWithWaitList) return CL_INVALID_COMMAND_QUEUE;
    return p_clEnqueueBarrierWithWaitList(command_queue, num_events_in_wait_list, event_wait_list, event);
}

CL_API_ENTRY cl_int CL_API_CALL
clEnqueueMarkerWithWaitList(cl_command_queue command_queue,
                           cl_uint num_events_in_wait_list,
                           const cl_event *event_wait_list,
                           cl_event *event) {
    RESOLVE_FN(clEnqueueMarkerWithWaitList);
    if (!p_clEnqueueMarkerWithWaitList) return CL_INVALID_COMMAND_QUEUE;
    return p_clEnqueueMarkerWithWaitList(command_queue, num_events_in_wait_list, event_wait_list, event);
}
