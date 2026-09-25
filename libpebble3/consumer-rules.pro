# Keep class names and all members for the packets package
-keepnames class io.rebble.libpebblecommon.packets.** { *; }
-keep class io.rebble.libpebblecommon.notification.NotificationDecision { *; }

# Ktor
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean