# JNI entry points are resolved by name from libpolarlsl.so.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class io.github.markspan.polarmobile.lsl.LslNative { *; }
