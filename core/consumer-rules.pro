# uniffi-биндинги SDK ходят в нативный код через JNA, а JNA находит классы и поля по именам.
# Без этих правил релизная сборка падает на первом вызове SDK, а debug работает — поэтому
# проверять нужно релизную (docs/PROMPT.md, 8.5).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
