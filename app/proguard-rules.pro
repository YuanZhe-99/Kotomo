# ML Kit GenAI (proofreading, rewriting) reaches AICore through reflection and Play services
# internals that R8 would strip; the same rules MyApps-AI ships for these artifacts.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ML Kit digital ink brings WorkManager 2.7 with Room 2.2, whose own rules keep the generated
# database class but not its constructor; R8 full mode then removes it and WorkManager's start-up
# initializer fails before any screen opens ("Failed to create an instance of WorkDatabase").
-keep class * extends androidx.room.RoomDatabase { <init>(); }
