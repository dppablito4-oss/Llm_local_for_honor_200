package com.prismai.validation;

import dalvik.system.DexClassLoader;
import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Runs against the actual shrunk APK, without installing it or influencing R8. */
public final class MinifiedJniSmoke {
    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected APK, native library directory, cache directory");
        }
        ClassLoader loader = new DexClassLoader(args[0], args[2], args[1],
                MinifiedJniSmoke.class.getClassLoader());
        Class<?> bridgeType = Class.forName(
                "com.prismai.llmhost.diffusion.NativeDiffusionBridge", true, loader);
        Method create = accessible(bridgeType, "nativeCreate");
        Method destroy = accessible(bridgeType, "nativeDestroy", long.class);
        Method generate = accessible(bridgeType, "nativeGenerate", long.class,
                String.class, String.class, String.class, int.class, int.class,
                int.class, float.class, long.class);
        try (Closeable bridge = (Closeable) bridgeType.getConstructor().newInstance()) {
            long handle = (Long) create.invoke(bridge);
            if (handle == 0) throw new AssertionError("Native engine allocation failed");
            try {
                // No model required: this still crosses the real JNI result constructor.
                // Run this before reflecting on the constructor to reproduce GetMethodID failure.
                for (int i = 0; i < 10; i++) {
                    Object result;
                    try {
                        result = generate.invoke(bridge, handle, "JNI smoke", "", "",
                                512, 512, 1, 1.0f, 42L);
                    } catch (InvocationTargetException failure) {
                        throw new AssertionError("Actual minified nativeGenerate failed", failure.getCause());
                    }
                    if (result == null) throw new AssertionError("JNI returned null carrier");
                    Class<?> type = result.getClass();
                    if ((Boolean) type.getMethod("getSuccess").invoke(result)) {
                        throw new AssertionError("Generation without a model must report failure");
                    }
                    if (!"Diffusion model is not loaded".equals(type.getMethod("getErrorMessage").invoke(result))) {
                        throw new AssertionError("Native error message was not preserved");
                    }
                }
            } finally {
                destroy.invoke(bridge, handle);
            }
        }
        // Also check every field on the success shape, independently of a large model download.
        Class<?> carrier = Class.forName(
                "com.prismai.llmhost.diffusion.DiffusionNativeResult", true, loader);
        byte[] png = new byte[] { (byte) 137, 80, 78, 71 };
        Object success = carrier.getConstructor(boolean.class, int.class, int.class,
                int.class, String.class, byte[].class, String.class, float.class)
                .newInstance(true, 512, 256, 3, "/smoke/output.png", png, "", 1.25f);
        check(carrier, success, "getSuccess", true);
        check(carrier, success, "getWidth", 512);
        check(carrier, success, "getHeight", 256);
        check(carrier, success, "getChannels", 3);
        check(carrier, success, "getOutputPath", "/smoke/output.png");
        check(carrier, success, "getErrorMessage", "");
        check(carrier, success, "getGenerationTimeSec", 1.25f);
        if (!Arrays.equals(png, (byte[]) carrier.getMethod("getPngBytes").invoke(success))) {
            throw new AssertionError("PNG carrier corrupted");
        }
        System.out.println("PASS: minified JNI native return x10 + success carrier constructor/fields");
    }

    private static Method accessible(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static void check(Class<?> type, Object result, String getter, Object expected) throws Exception {
        Object actual = type.getMethod(getter).invoke(result);
        if (!expected.equals(actual)) throw new AssertionError(getter + ": " + actual);
    }
}
