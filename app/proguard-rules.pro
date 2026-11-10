# R8 rules for GPS Cam. No JNI or reflection of our own; AndroidX ships its consumer rules.

# Readable crash reports: keep file names and line numbers, rename the source file attribute.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
