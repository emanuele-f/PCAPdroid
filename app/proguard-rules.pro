-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
-dontobfuscate

# some classes are required by the native code, keep them all for now
-keep class com.emanuelef.remote_capture.** { *; }
-keep class com.pcapdroid.mitm.** { *; }
-keep class com.maxmind.db.** { *; }