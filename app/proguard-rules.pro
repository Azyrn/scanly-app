# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep line numbers and hide the original source file name so crash stack
# traces stay meaningful after obfuscation (retrace-able via mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Debug and verbose logs are for development only. Several carry what the user scanned (text,
# barcodes, product names), and release logcat is readable by anyone with adb.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ---------------------------------------------------------------------------
# ML Kit (text recognition, barcode scanning, document scanner)
#
# ML Kit registers its internal components through
# `MlKitComponentDiscoveryService`, whose ComponentRegistrar implementations
# are referenced ONLY as string values in the merged AndroidManifest
# meta-data. R8 full mode (android.enableR8.fullMode=true) cannot see those
# string references, strips the registrars, and the component container then
# returns null -- causing a NullPointerException inside
# TextRecognition.getClient(...) the first time an ML Kit client is built.
# Keep the registrars and ML Kit internals so on-device vision works in
# release builds.
# (The named registrars — CommonComponentRegistrar, BarcodeRegistrar, etc. —
# are already covered by the two wildcard rules below.)
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**

# ---------------------------------------------------------------------------
# kotlinx.serialization (Retrofit APIs: Gemini / OpenAI-compat / Claude, plus
# the product-lookup engines). Under R8 full mode the generated $$serializer
# classes and @Serializable members can be stripped/renamed, causing runtime
# SerializationExceptions when an AI request or product lookup runs.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
# keepclasseswithmembers keeps the $$serializer classes AND their members,
# subsuming the narrower keepclassmembers variant.
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# Keep enum values referenced by generated serializers (enum serialization
# uses valueOf/values reflectively in some code paths).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# ONNX Runtime resolves these from JNI; R8 full mode strips them otherwise
# (UnsatisfiedLinkError / NoSuchMethodError at the first OCR run).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---------------------------------------------------------------------------
# PDF editor
#
# libscanlypdf.so resolves these by their fully-qualified JNI names, which R8
# cannot see. Renaming the class or any native method yields UnsatisfiedLinkError
# on the first qpdf call.
-keep class com.skeler.scanely.core.pdfeditor.engine.Qpdf { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# PdfiumAndroidKt calls back into these from native code, and PdfWriteCallback is
# invoked by pdfium during saveAsCopy.
-keep class io.legere.pdfiumandroid.** { *; }
-keep class io.legere.pdfiumandroid.api.** { *; }
-dontwarn io.legere.pdfiumandroid.**

# Bouncy Castle registers algorithms via reflection on class names built at
# runtime, so the SPI implementations have no statically visible references.
# Stripping them makes CMS signature verification fail with NoSuchAlgorithmException.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
# BC ships optional JDK-only hooks that never resolve on Android.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# PDFBox drives the signing path only. It builds COS object and filter class
# names as strings, so the parsers behind them have no statically visible
# reference and R8 full mode strips them -- incremental save then fails on a
# document R8 has quietly emptied of the filters needed to read it.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
# PDFBox is a desktop library ported to Android; it still references the AWT
# and ImageIO classes the port never removed, none of which are ever reached.
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
