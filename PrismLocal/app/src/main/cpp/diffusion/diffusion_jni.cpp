#include <jni.h>
#include <string>
#include <exception>
#include "DiffusionEngine.hpp"
#include "runtime/NativeHandleRegistry.hpp"

using prism::diffusion::DiffusionEngine;
using prism::diffusion::DiffusionResult;

static llmhost::NativeHandleRegistry<DiffusionEngine> g_diffusion_engines;

static std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* utf = env->GetStringUTFChars(jstr, nullptr);
    if (!utf) return "";
    std::string str(utf);
    env->ReleaseStringUTFChars(jstr, utf);
    return str;
}

static jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

static jbyteArray toJByteArray(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    if (bytes.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    return arr;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_prismai_llmhost_diffusion_NativeDiffusionBridge_nativeCreate(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    try {
        return g_diffusion_engines.insert(std::make_shared<DiffusionEngine>());
    } catch (const std::exception&) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_prismai_llmhost_diffusion_NativeDiffusionBridge_nativeDestroy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto engine = g_diffusion_engines.retire(handle);
    if (engine) engine->shutdown();
}

JNIEXPORT jboolean JNICALL
Java_com_prismai_llmhost_diffusion_NativeDiffusionBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring model_path,
    jstring vae_path,
    jstring taesd_path,
    jint threads,
    jboolean on_gpu,
    jboolean vae_tiling
) {
    auto engine = g_diffusion_engines.acquire(handle);
    if (!engine) return JNI_FALSE;

    const std::string m_path = jstringToString(env, model_path);
    const std::string v_path = jstringToString(env, vae_path);
    const std::string t_path = jstringToString(env, taesd_path);

    bool ok = engine->loadModel(m_path, v_path, t_path, threads, on_gpu, vae_tiling);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_prismai_llmhost_diffusion_NativeDiffusionBridge_nativeGenerate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring prompt,
    jstring negative_prompt,
    jstring output_file_path,
    jint width,
    jint height,
    jint steps,
    jfloat cfg_scale,
    jlong seed
) {
    auto engine = g_diffusion_engines.acquire(handle);

    const std::string p = jstringToString(env, prompt);
    const std::string np = jstringToString(env, negative_prompt);
    const std::string out_path = jstringToString(env, output_file_path);

    DiffusionResult res;
    if (engine) {
        res = engine->generate(p, np, out_path, width, height, steps, cfg_scale, seed);
    } else {
        res.error_message = "Diffusion bridge is closed or invalid";
    }

    jclass result_class = env->FindClass("com/prismai/llmhost/diffusion/DiffusionNativeResult");
    if (!result_class) return nullptr;

    jmethodID ctor = env->GetMethodID(
        result_class,
        "<init>",
        "(ZIIILjava/lang/String;[BLjava/lang/String;F)V"
    );
    if (!ctor) return nullptr;

    jstring j_output_path = stringToJstring(env, res.output_path);
    jbyteArray j_png_bytes = toJByteArray(env, res.png_bytes);
    jstring j_error = stringToJstring(env, res.error_message);

    jobject obj = env->NewObject(
        result_class,
        ctor,
        res.success ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(res.width),
        static_cast<jint>(res.height),
        static_cast<jint>(res.channels),
        j_output_path,
        j_png_bytes,
        j_error,
        static_cast<jfloat>(res.generation_time_sec)
    );

    if (j_output_path) env->DeleteLocalRef(j_output_path);
    if (j_png_bytes) env->DeleteLocalRef(j_png_bytes);
    if (j_error) env->DeleteLocalRef(j_error);
    env->DeleteLocalRef(result_class);

    return obj;
}

JNIEXPORT void JNICALL
Java_com_prismai_llmhost_diffusion_NativeDiffusionBridge_nativeCancel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto engine = g_diffusion_engines.acquire(handle);
    if (engine) engine->cancel();
}

JNIEXPORT jboolean JNICALL
Java_com_prismai_llmhost_diffusion_NativeDiffusionBridge_nativeIsLoaded(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto engine = g_diffusion_engines.acquire(handle);
    return (engine && engine->isLoaded()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
