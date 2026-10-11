package com.vibeplayer.player

enum class EfficientPath {
    PATH_A, // mediacodec_embed (direct MediaCodec to surface)
    PATH_B  // gpu + mediacodec zero-copy (default)
}

enum class PlaybackMode {
    EFFICIENT,
    QUALITY
}

enum class Renderer {
    GLES,
    VULKAN
}

data class PlaybackConfig(
    val mode: PlaybackMode = PlaybackMode.EFFICIENT,
    val efficientPath: EfficientPath = EfficientPath.PATH_B,
    val renderer: Renderer = Renderer.GLES
) {
    val vo: String
        get() = when (mode) {
            PlaybackMode.EFFICIENT -> when (efficientPath) {
                EfficientPath.PATH_A -> "mediacodec_embed"
                EfficientPath.PATH_B -> "gpu"
            }
            PlaybackMode.QUALITY -> "gpu-next"
        }

    fun getOptionTable(): Map<String, String> {
        val options = mutableMapOf<String, String>()
        options["vo"] = "null"
        options["force-window"] = "no"
        options["ao"] = "audiotrack"
        options["keep-open"] = "yes"
        options["vd-lavc-threads"] = "0"
        options["hwdec-codecs"] = "h264,hevc,vp9"

        when (mode) {
            PlaybackMode.EFFICIENT -> {
                when (efficientPath) {
                    EfficientPath.PATH_A -> {
                        options["hwdec"] = "mediacodec"
                    }
                    EfficientPath.PATH_B -> {
                        options["gpu-context"] = "android"
                        options["hwdec"] = "mediacodec"
                        options["profile"] = "fast"
                    }
                }
            }
            PlaybackMode.QUALITY -> {
                options["hwdec"] = "mediacodec-copy"
                when (renderer) {
                    Renderer.GLES -> {
                        options["gpu-api"] = "opengl"
                        options["gpu-context"] = "android"
                    }
                    Renderer.VULKAN -> {
                        options["gpu-api"] = "vulkan"
                        options["gpu-context"] = "androidvk"
                    }
                }
            }
        }
        return options
    }
}
