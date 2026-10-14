package com.vibeplayer.player

/**
 * How the current file is opened again on a new mpv handle (reconfigure, Vulkan/GLES fallback,
 * B-08 software reload). A plain path or URL is reused. An `fdclose://N` path is not: mpv closed N
 * with the old handle (or still holds it in an abandoned one), so a fresh descriptor has to be
 * resolved from the file's identity (its content:// URI) through the app's resolver.
 */
object ReloadPolicy {
    sealed class Plan {
        /** Load the same path again. */
        object Same : Plan()
        /** Resolve a fresh descriptor for [identity] off the command thread, then load it. */
        data class Reopen(val identity: String) : Plan()
        /** Cannot be opened again without the user reopening the file. */
        object Impossible : Plan()
    }

    const val FD_SCHEME = "fdclose://"

    fun plan(path: String, identity: String?, canReopen: Boolean): Plan = when {
        !path.startsWith(FD_SCHEME) -> Plan.Same
        canReopen && identity != null && identity.startsWith("content://") -> Plan.Reopen(identity)
        else -> Plan.Impossible
    }

    /** The descriptor number of an `fdclose://N` path, or null for any other path. */
    fun fdOf(path: String): Int? =
        if (path.startsWith(FD_SCHEME)) path.removePrefix(FD_SCHEME).toIntOrNull()?.takeIf { it >= 0 } else null
}
