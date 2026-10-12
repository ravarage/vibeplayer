package com.vibeplayer.ui.player

import java.io.File

/** Video files of one folder in natural name order ("ep 2" before "ep 10"). Call off the main thread. */
object FolderPlaylist {
    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "flv", "wmv", "mpg", "mpeg", "3gp", "ogv"
    )

    fun list(path: String): List<String> {
        val dir = File(path).parentFile ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in VIDEO_EXTENSIONS }
            ?: return emptyList()
        return files.map { it.absolutePath }.sortedWith(NaturalOrder)
    }

    fun next(path: String): String? = neighbour(path, 1)

    fun previous(path: String): String? = neighbour(path, -1)

    private fun neighbour(path: String, step: Int): String? {
        if (!path.startsWith("/")) return null // content:// and fd paths have no folder
        val files = list(path)
        val index = files.indexOf(File(path).absolutePath)
        if (index < 0) return null
        return files.getOrNull(index + step)
    }

    /** Case-insensitive compare where digit runs are compared as numbers. */
    internal object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ei = i
                    while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j
                    while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0')
                    val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ei
                    j = ej
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
