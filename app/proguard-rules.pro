# Keep crypto classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.crypto.tink.** { *; }

# Keep JSch
-keep class com.jcraft.jsch.** { *; }

# JSch optional dependencies not available on Android
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.naming.**
-dontwarn com.sun.jna.**

# Keep termlib classes — native JNI renderer accesses fields by name
-keep class org.connectbot.terminal.** { *; }

# Keep mosh transport + generated protobuf classes.
# The pure-Kotlin transport reflects on protobuf field names like `width_`.
# If R8 renames those fields, Mosh connects but never establishes a usable
# terminal session in release builds.
-keep class sh.haven.mosh.** { *; }

# Keep smbj (reflection-based protocol handling)
-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }
-dontwarn javax.el.**

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
