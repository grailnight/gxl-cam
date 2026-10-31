# JNI entry points are looked up by name from native code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class io.github.grailnight.gxlcam.codec.JxlNative { *; }
