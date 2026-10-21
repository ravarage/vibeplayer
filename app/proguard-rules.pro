# Proguard rules for vibeplayer
# Room's generated implementation and runtime look up the database entities and
# DAOs by their model types. Preserve their model class and member names.
-keep class com.vibeplayer.data.db.** { *; }

# DataStore uses statically referenced preferences and settings types; preserving
# these avoids breaking persisted enum names when the app is minified.
-keep class com.vibeplayer.data.prefs.** { *; }

# Compose call sites are statically rewritten by the Compose compiler. The
# Compose artifacts provide their own consumer rules; no broad Compose keep is needed.

# P13 network sources.
# JSch loads its ciphers, key exchanges and MACs by class name from its config.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
# smbj: mbassador dispatches events to @Handler methods by reflection; BouncyCastle algorithms
# (MD4 for NTLM, AES-CMAC for SMB 3 signing) are looked up by name.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
# Room entity outside data.db (P13)
-keep class com.vibeplayer.net.NetSource { *; }
