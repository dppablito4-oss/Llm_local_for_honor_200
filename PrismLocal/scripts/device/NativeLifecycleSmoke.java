package com.prismai.validation;

import dalvik.system.DexClassLoader;
import java.io.Closeable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real JNI calls, retired handles and active diffusion teardown. */
public final class NativeLifecycleSmoke {
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("APK, libs, cache, tiny LLM, diffusion model required");
        ClassLoader loader = new DexClassLoader(args[0], args[2], args[1], NativeLifecycleSmoke.class.getClassLoader());
        Class<?> llm = Class.forName("com.prismai.llmhost.bridge.NativeLlmBridge", true, loader);
        Method create = method(llm, "nativeCreateEngine", boolean.class);
        Method destroy = method(llm, "nativeDestroyEngine", long.class);
        Method pressure = method(llm, "nativeSetMemoryPressure", long.class, int.class);
        Method threads = method(llm, "nativeSetThreadCount", long.class, int.class);
        Method state = method(llm, "nativeGetState", long.class, int.class);
        Constructor<?> llmCtor = llm.getDeclaredConstructor(long.class, int.class);
        llmCtor.setAccessible(true);
        for (int round = 0; round < 100; round++) {
            long handle = (Long) create.invoke(null, false);
            Object bridge = llmCtor.newInstance(handle, round);
            race(() -> {
                pressure.invoke(bridge, handle, 1);
                threads.invoke(bridge, handle, 2);
                state.invoke(bridge, handle, 1);
            }, () -> { destroy.invoke(bridge, handle); destroy.invoke(bridge, handle); });
            require(((Integer) state.invoke(bridge, handle, 1)) == 6, "retired LLM handle must be tombstoned");
        }
        System.out.println("PASS: 100 LLM destroy/pressure/thread/drain-state races and stale handles");
        realText(loader, llm, llmCtor, create, destroy, args[3]);

        Class<?> diffusion = Class.forName("com.prismai.llmhost.diffusion.NativeDiffusionBridge", true, loader);
        Method diffusionCreate = method(diffusion, "nativeCreate");
        Method diffusionDestroy = method(diffusion, "nativeDestroy", long.class);
        Method loaded = method(diffusion, "nativeIsLoaded", long.class);
        Method cancel = method(diffusion, "nativeCancel", long.class);
        Method generate = method(diffusion, "nativeGenerate", long.class, String.class, String.class,
                String.class, int.class, int.class, int.class, float.class, long.class);
        try (Closeable bridge = (Closeable) diffusion.getConstructor().newInstance()) {
            for (int round = 0; round < 100; round++) {
                long handle = (Long) diffusionCreate.invoke(bridge);
                race(() -> {
                    loaded.invoke(bridge, handle);
                    cancel.invoke(bridge, handle);
                    Object result = generate.invoke(bridge, handle, "lifecycle", "", "", 512, 512, 1, 1f, 42L);
                    require(result != null, "stale diffusion handle must return a valid failure carrier");
                }, () -> { diffusionDestroy.invoke(bridge, handle); diffusionDestroy.invoke(bridge, handle); });
                require(!(Boolean) loaded.invoke(bridge, handle), "retired diffusion handle must not resolve");
            }
        }
        System.out.println("PASS: 100 diffusion destroy/generate/cancel/status races and stale handles");
        activeDiffusionTeardown(diffusion, generate, loaded, args[4]);
        System.out.println("PASS: native lifecycle smoke complete");
    }

    private static void realText(ClassLoader loader, Class<?> llm, Constructor<?> ctor,
            Method create, Method destroy, String model) throws Exception {
        long handle = (Long) create.invoke(null, false);
        Object bridge = ctor.newInstance(handle, 999);
        try {
            require((Boolean) method(llm, "nativeLoadModel", long.class, String.class).invoke(bridge, handle, model),
                    "tiny real GGUF failed to load");
            Method start = method(llm, "nativeStartGeneration", long.class, String.class, int.class,
                    int.class, int.class, int.class, int.class, float.class, int.class,
                    float.class, float.class, int.class, boolean.class, String.class);
            int started = (Integer) start.invoke(bridge, handle, "Once upon a", 1001,
                    32, 2, 2048, 128, 0.7f, 40, 0.95f, 1.1f, 0, false, null);
            require(started >= 0, "real generation not accepted: " + started);
            Class<?> carrier = loader.loadClass("com.prismai.llmhost.bridge.NativeDrainResult");
            Object out = carrier.getConstructor().newInstance();
            Method drain = method(llm, "nativeDrainDecodeAndState", long.class, int.class, int.class, carrier);
            StringBuilder text = new StringBuilder();
            int tokens = 0;
            boolean terminal = false;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline) {
                drain.invoke(bridge, handle, 1001, 128, out);
                tokens += carrier.getField("tokensCount").getInt(out);
                String overflow = (String) carrier.getField("textOverflow").get(out);
                if (!overflow.isEmpty()) text.append(overflow);
                else text.append(new String((byte[]) carrier.getField("textBuffer").get(out), 0,
                        carrier.getField("textCount").getInt(out), StandardCharsets.UTF_8));
                int s = carrier.getField("state").getInt(out);
                require(s != 5, "real text returned native error " + carrier.getField("errorCode").getInt(out));
                if ((s == 3 || s == 7) && !carrier.getField("pending").getBoolean(out)) { terminal = true; break; }
                Thread.sleep(5);
            }
            require(terminal && tokens > 0 && text.length() > 0, "real text did not complete");
            System.out.println("PASS: real GGUF text after lifecycle stress, tokens=" + tokens);
        } finally { destroy.invoke(bridge, handle); }
    }

    private static void activeDiffusionTeardown(Class<?> type, Method generate, Method loaded, String model) throws Exception {
        Method load = method(type, "nativeLoadModel", long.class, String.class, String.class,
                String.class, int.class, boolean.class, boolean.class);
        java.lang.reflect.Field handleField = type.getDeclaredField("nativeHandle");
        handleField.setAccessible(true);
        Closeable bridge = (Closeable) type.getConstructor().newInstance();
        long handle = handleField.getLong(bridge);
        try {
            require((Boolean) load.invoke(bridge, handle, model, "", "", 2, false, true), "real SDXS failed to load");
            System.out.println("Real SDXS loaded; starting active teardown test");
            AtomicReference<Object> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                entered.countDown();
                try { result.set(generate.invoke(bridge, handle, "a red cube on a white background", "", "",
                        512, 512, 8, 1f, 42L)); }
                catch (Throwable error) { failure.set(error); }
            });
            worker.start();
            entered.await();
            Thread.sleep(250);
            require(worker.isAlive(), "generation must still be active to validate teardown");
            long closeStart = System.nanoTime();
            bridge.close();
            long closeMs = (System.nanoTime() - closeStart) / 1_000_000L;
            require(closeMs < 2000, "closing active bridge blocked: " + closeMs + "ms");
            worker.join(120_000);
            require(!worker.isAlive(), "native generation did not stop after shutdown");
            if (failure.get() != null) throw new AssertionError("teardown raced native generation", failure.get());
            Object out = result.get();
            require(out != null, "cancelled generation lost its JNI result");
            require(!(Boolean) out.getClass().getMethod("getSuccess").invoke(out), "closed generation must report cancellation");
            require(!(Boolean) loaded.invoke(bridge, handle), "closed model still resolves");
            System.out.println("PASS: close during real SDXS generation, close latency=" + closeMs + "ms");
        } finally { bridge.close(); }
        // Reload in a fresh engine and render once: no stale global progress userdata.
        try (Closeable reopened = (Closeable) type.getConstructor().newInstance()) {
            long fresh = handleField.getLong(reopened);
            require((Boolean) load.invoke(reopened, fresh, model, "", "", 2, false, true), "SDXS reload failed");
            Object out = generate.invoke(reopened, fresh, "a red cube on a white background", "", "", 512, 512, 1, 1f, 42L);
            Class<?> carrier = out.getClass();
            require((Boolean) carrier.getMethod("getSuccess").invoke(out), "SDXS failed after reopen: " + carrier.getMethod("getErrorMessage").invoke(out));
            byte[] png = (byte[]) carrier.getMethod("getPngBytes").invoke(out);
            require(png != null && png.length > 8 && (png[0] & 255) == 137 && png[1] == 80, "invalid PNG after reopen");
            require(((Integer) carrier.getMethod("getWidth").invoke(out)) == 512, "wrong image width");
            System.out.println("PASS: real SDXS reopen and PNG render, bytes=" + png.length);
        }
    }

    private interface Action { void run() throws Exception; }

    private static void race(Action call, Action close) throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable body = () -> {
            started.countDown();
            try { for (int i = 0; i < 50; i++) call.run(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        };
        Thread a = new Thread(body), b = new Thread(body);
        a.start(); b.start(); started.await();
        close.run();
        a.join(10000); b.join(10000);
        require(!a.isAlive() && !b.isAlive(), "JNI race deadlocked");
        if (failure.get() != null) throw new AssertionError("JNI race failed", failure.get());
        call.run(); // Always exercise a stale handle, even on a fast scheduler.
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method m = type.getDeclaredMethod(name, args); m.setAccessible(true); return m;
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
