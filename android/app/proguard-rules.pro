# Everything is referenced from the manifest or Compose, which R8 already keeps.

# WorkManager creates its Room database reflectively (WorkDatabase_Impl.<init>()); without this,
# R8 strips the constructor and the release build crashes at startup in WorkManagerInitializer.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
