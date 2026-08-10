# Media3 ships its own consumer rules, so the player needs nothing here.

# kotlinx.serialization keeps the generated serializers on the companion.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit reads generic signatures off the service interfaces.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep,allowobfuscation interface retrofit2.** { *; }
