# Shizuku instantiates this Binder by its component class name in a separate process.
-keep class com.quintz.wifi.shizuku.WifiProfileService { public <init>(...); *; }
-keep class com.quintz.wifi.shizuku.IWifiProfileService$Stub { *; }
-keep class com.quintz.wifi.shizuku.IWifiProfileService$Stub$Proxy { *; }
# The legacy process API is accessed reflectively until the dependency removes it.
-keepclassmembers class rikka.shizuku.Shizuku { public static *** newProcess(...); }

# Optional compile-time annotations referenced by the bundled Tink classes. These are
# annotation types only; do not suppress missing security/runtime implementation classes.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
