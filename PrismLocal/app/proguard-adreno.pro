# The separate instrumented APK references shared APIs that app-only R8 cannot
# see. Keep those entry points while retaining shrinking/optimization elsewhere.
-keep class androidx.tracing.Trace { *; }
# The runner and Kotlin tests are separate DEX programs; their calls into the
# target's shared runtime cannot follow app-only inlining/class merging.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.prismai.llmhost.storage.ModelStorageManager { public *; }
-keep class com.prismai.llmhost.storage.ModelStorageManager$** { *; }
-keep enum com.prismai.llmhost.diffusion.DiffusionBackend { *; }
