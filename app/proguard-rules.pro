# --- Apache MINA SSHD ---
# MINA builds its algorithms (ciphers, KEX, signatures, key parsers) by name and reflection, and registers
# security providers dynamically; shrinking any of it breaks connections at runtime.
-keep class org.apache.sshd.** { *; }
-keepclassmembers class org.apache.sshd.** { *; }
# Desktop-JDK classes MINA mentions only in exception handling; they don't exist on Android.
-dontwarn javax.management.**
-dontwarn javax.security.auth.login.**
-dontwarn org.apache.sshd.**
# MINA logs through SLF4J (bound to slf4j-nop).
-dontwarn org.slf4j.**

# --- BouncyCastle ---
# JCA providers are looked up by algorithm name; R8 can't see those references.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Tink ---
# Tink ships its own consumer rules; these cover the optional dependencies it references.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# --- Vendored Termux terminal ---
# JNI.java's native methods are bound by name from libtermux.so.
-keep class com.termux.terminal.JNI { native <methods>; }
