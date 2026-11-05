# ML Kit GenAI (proofreading, rewriting) reaches AICore through reflection and Play services
# internals that R8 would strip; the same rules MyApps-AI ships for these artifacts.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
