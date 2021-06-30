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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# we are open source and do not need to obfuscate anything, but we want to remove unused classes and members
-keepnames class ** { *; }

# for loggers
-keepattributes *Annotation*

# for BC
-keep class org.bouncycastle.jce.provider.**,org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.** extends org.bouncycastle.jcajce.provider.asymmetric.util.BaseKeyFactorySpi { *;}
-keep class org.bouncycastle.** extends java.security.KeyFactorySpi { *;}

# for netty
-keepattributes Signature,InnerClasses
-keepclasseswithmembers class io.netty.** {
    *;
}

# keep native calls
-keepclasseswithmembernames class * {
    native <methods>;
}

# print removed and kept code
#-printusage usage.txt
#-printseeds seeds.txt