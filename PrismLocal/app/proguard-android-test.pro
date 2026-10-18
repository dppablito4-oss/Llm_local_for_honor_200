# The runner discovers JUnit classes/methods by names supplied over ADB.
# These rules apply only to the instrumented test APK, not the application APK.
-keep @org.junit.runner.RunWith class com.prismai.llmhost.** { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
