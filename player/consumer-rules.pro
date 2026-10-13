# mpv_jni.c binds every MpvNative external method by its exported JNI symbol.
-keep class com.vibeplayer.player.MpvNative { *; }

# JNI_OnLoad finds this nested interface by name and looks up both callback methods.
-keep interface com.vibeplayer.player.MpvNative$EventCallback { *; }

# extractThumbnail() constructs this class using the (III[B)V constructor signature.
-keep class com.vibeplayer.player.ThumbnailFrame {
    public <init>(int, int, int, byte[]);
}

# General protection for native declarations if more JNI bridge classes are added later.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
