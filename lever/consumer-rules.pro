# The SDK's own model classes are kotlinx.serialization @Serializable types whose
# generated serializers reference members reflectively at their declaration site.
# kotlinx-serialization ships the general rules; these pin the SDK's own shapes so
# a consumer's aggressive R8 configuration cannot strip the cache and wire codecs.
-keepclassmembers class dev.forcetower.lever.**$$serializer { *; }
-keepclasseswithmembers class dev.forcetower.lever.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The public exception hierarchy is matched by type at call sites.
-keep public class dev.forcetower.lever.LeverException { *; }
-keep public class dev.forcetower.lever.LeverException$* { *; }
