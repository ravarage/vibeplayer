# Proguard rules for vibeplayer
# Room's generated implementation and runtime look up the database entities and
# DAOs by their model types. Preserve their model class and member names.
-keep class com.vibeplayer.data.db.** { *; }

# DataStore uses statically referenced preferences and settings types; preserving
# these avoids breaking persisted enum names when the app is minified.
-keep class com.vibeplayer.data.prefs.** { *; }

# Compose call sites are statically rewritten by the Compose compiler. The
# Compose artifacts provide their own consumer rules; no broad Compose keep is needed.
