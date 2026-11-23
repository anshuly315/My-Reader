# Project-specific R8 rules. Libraries ship their own consumer rules.

# ML Kit (translate and language-id) reaches into its own options/model classes by reflection; R8's
# optimization renames those fields and leaves nulls behind (a NullPointerException in the translate
# flow). Keep its classes whole so translation works in the minified release build (owner, 2026-11-07).
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# WorkManager makes its input mergers and workers by reflection. R8 removed OverwritingInputMerger's no-argument
# constructor, so every Glance widget session (a WorkManager job) failed and the home-screen widgets stayed on
# "Loading" in release builds (0.7.0-beta.5, 2026-11-12; debug builds are not shrunk, so the emulator never showed it).
-keep class * extends androidx.work.InputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class androidx.glance.appwidget.** extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class com.flowfolio.widget.** { *; }

# ---- Added for My Reader (2026-11-23) ----
# On-device AI (LiteRT-LM): its native library calls back into these classes by name.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
# PDF tools (PDFBox for Android): fonts and filters are found by name at run time; optional JPEG 2000 support is absent.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# Crash reports read back to the line (CI keeps mapping.txt beside each build).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
