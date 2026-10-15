# HDR Output Spike Research: Android GLES vs Vulkan

**Status**: Research & Documentation Spike Complete  
**Date**: 2026-10-15  
**Target Platform**: Android 17 (API 37), POCO X5 Pro (`redwood`, Adreno 642L GPU, 10-bit 120Hz AMOLED)  
**Related Tasks**: P10-08, P6-07 (Vulkan renderer), P1-06 (Playback modes)

---

## 1. Executive Summary & Recommendation Matrix

We evaluated whether `mpv` (v0.41.0) and `libplacebo` (v7.360.1) can output High Dynamic Range (HDR) video (PQ / ST 2084, HLG, and extended-range / scRGB) on Android using OpenGL ES (EGL) or Vulkan (`VkAndroidSurfaceKHR`).

All findings are based directly on source code analysis of `mpv-android` dependencies under `native/mpv-android/buildscripts/deps`:
- `mpv/video/out/opengl/context_android.c`
- `mpv/video/out/opengl/egl_helpers.c`
- `mpv/video/out/vulkan/context_android.c`
- `mpv/video/out/vo_gpu_next.c`
- `mpv/video/out/gpu/` (`vo_gpu`)
- `libplacebo/src/opengl/swapchain.c`
- `libplacebo/src/vulkan/context.c`
- `libplacebo/src/vulkan/swapchain.c`
- `libplacebo/src/swapchain.c`

### Recommendation Summary

| Output Path | HDR PQ (ST 2084) | HDR HLG | Extended-range / scRGB | Verdict |
|---|---|---|---|---|
| **GLES (`vo=gpu` or `vo=gpu-next`)** | **NO-GO** | **NO-GO** | **NO-GO** | **NO-GO**: EGL surface creation passes `NULL` attributes; zero colorspace extensions in mpv; libplacebo OpenGL swapchain hardcodes SDR monitor and ignores colorspace hints. |
| **Vulkan (`vo=gpu`)** | **NO-GO** | **NO-GO** | **NO-GO** | **NO-GO**: Legacy `vo_gpu` has no swapchain colorspace negotiation logic on Vulkan (only D3D11 on Windows). |
| **Vulkan (`vo=gpu-next`)** | **CONDITIONAL GO** | **CONDITIONAL GO** | **NO-GO** | **CONDITIONAL GO**: libplacebo and mpv implement full swapchain colorspace selection and HDR10 metadata via `VK_EXT_swapchain_colorspace` and `VK_EXT_hdr_metadata`. Extended-range is unhandled (`// TODO`). Driver and app-side prerequisites apply. |

---

## 2. OpenGL ES / EGL Analysis

### 2.1 EGL Surface Creation in `mpv`
In `mpv/video/out/opengl/context_android.c`:
- **Lines 73–75**:
  ```c
  EGLint format;
  eglGetConfigAttrib(p->egl_display, config, EGL_NATIVE_VISUAL_ID, &format);
  ANativeWindow_setBuffersGeometry(native_window, 0, 0, format);
  ```
  The native window buffer geometry format is locked to the visual ID from `config` (typically 8-bit `WINDOW_FORMAT_RGBA_8888`).
- **Lines 77–78**:
  ```c
  p->egl_surface = eglCreateWindowSurface(p->egl_display, config,
                                  (EGLNativeWindowType)native_window, NULL);
  ```
  `eglCreateWindowSurface` is called with `attrib_list = NULL`.
- To output HDR over EGL on Android, the extension `EGL_EXT_gl_colorspace_bt2020_pq` or `EGL_EXT_gl_colorspace_bt2020_hlg` must be requested by passing `EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT` in `attrib_list`. In the current mpv source, `attrib_list` is hardcoded to `NULL`.
- There is **no mention** of `EGL_EXT_gl_colorspace_bt2020_pq`, `EGL_EXT_gl_colorspace_bt2020_hlg`, or `ANativeWindow_setBuffersDataSpace` anywhere in `context_android.c` or `egl_helpers.c`.

### 2.2 libplacebo OpenGL Swapchain Limitations
In `libplacebo/src/opengl/swapchain.c`:
- **Line 196**:
  ```c
  .color_space = pl_color_space_monitor,
  ```
  The swapchain framebuffer color space is hardcoded to `pl_color_space_monitor` (standard SDR sRGB / BT.709).
- **Lines 271–278**:
  ```c
  static const struct pl_sw_fns opengl_swapchain = {
      .destroy      = gl_sw_destroy,
      .latency      = gl_sw_latency,
      .resize       = gl_sw_resize,
      .start_frame  = gl_sw_start_frame,
      .submit_frame = gl_sw_submit_frame,
      .swap_buffers = gl_sw_swap_buffers,
  };
  ```
  The `opengl_swapchain` vtable does **not** implement `.colorspace_hint` (`.colorspace_hint = NULL`).

In `libplacebo/src/swapchain.c`:
- **Lines 57–62**:
  ```c
  void pl_swapchain_colorspace_hint(pl_swapchain sw, const struct pl_color_space *csp)
  {
      const struct pl_sw_fns *impl = PL_PRIV(sw);
      if (!impl->colorspace_hint)
          return;
  ```
  Because `impl->colorspace_hint` is `NULL`, any colorspace hint sent by `vo_gpu_next` immediately early-returns as a no-op.

### 2.3 GLES Conclusion
**Definite NO-GO**. Neither `mpv`'s Android EGL context nor `libplacebo`'s OpenGL swapchain possesses any mechanism to request or switch an EGL surface to an HDR colorspace or 10-bit transfer curve.

---

## 3. Vulkan Analysis (`mpv` + `libplacebo`)

### 3.1 Android Vulkan Surface Initialization
In `mpv/video/out/vulkan/context_android.c`:
- **Lines 48–63**:
  ```c
  if (!mpvk_init(vk, ctx, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME))
      goto fail;

  VkAndroidSurfaceCreateInfoKHR info = {
       .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
       .window = vo_android_native_window(ctx->vo)
  };

  VkInstance inst = vk->vkinst->instance;
  VkResult res = vkCreateAndroidSurfaceKHR(inst, &info, NULL, &vk->surface);
  ```
  mpv creates a standard `VkAndroidSurfaceKHR` bound to the `ANativeWindow`.
- **Line 65**:
  ```c
  if (!ra_vk_ctx_init(ctx, vk, params, VK_PRESENT_MODE_FIFO_KHR))
      goto fail;
  ```
  Swapchain creation, surface format negotiation, and rendering are completely delegated to `libplacebo`'s Vulkan swapchain implementation.

### 3.2 Extension Probing in `libplacebo`
In `libplacebo/src/vulkan/context.c`:
- **Lines 54–60**:
  ```c
  static const char *vk_instance_extensions[] = {
      VK_KHR_SURFACE_EXTENSION_NAME,
      VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME,
      VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME,
      VK_KHR_SURFACE_MAINTENANCE_1_EXTENSION_NAME,
      VK_EXT_SURFACE_MAINTENANCE_1_EXTENSION_NAME,
  };
  ```
  `VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME` (`"VK_EXT_swapchain_colorspace"`) is listed in the optional instance extension table.
- **Lines 718–727**:
  ```c
  for (int i = 0; i < PL_ARRAY_SIZE(vk_instance_extensions); i++) {
      const char *ext = vk_instance_extensions[i];
      for (int n = 0; n < num_exts_avail; n++) {
          if (strcmp(ext, exts_avail[n].extensionName) == 0) {
              PL_ARRAY_APPEND(tmp, exts, ext);
              break;
          }
      }
  }
  ```
  `libplacebo` automatically requests and enables `VK_EXT_swapchain_colorspace` during instance creation whenever the device's Vulkan driver exposes it.
- **Lines 152–156**:
  ```c
  }, {
      .name = VK_EXT_HDR_METADATA_EXTENSION_NAME,
      .funs = (const struct vk_fun[]) {
          PL_VK_DEV_FUN(SetHdrMetadataEXT),
          {0}
      },
  ```
  `libplacebo` also probes for `VK_EXT_hdr_metadata` and dynamically loads `SetHdrMetadataEXT` for pushing mastering display metadata.

### 3.3 Color Space Mapping in `libplacebo`
In `libplacebo/src/vulkan/swapchain.c`:
- **Lines 66–145** (`map_color_space`):
  ```c
  case VK_COLOR_SPACE_HDR10_ST2084_EXT:
      *out = (struct pl_color_space) {
          .primaries = PL_COLOR_PRIM_BT_2020,
          .transfer  = PL_COLOR_TRC_PQ,
      };
      return true;
  case VK_COLOR_SPACE_HDR10_HLG_EXT:
      *out = (struct pl_color_space) {
          .primaries = PL_COLOR_PRIM_BT_2020,
          .transfer  = PL_COLOR_TRC_HLG,
      };
      return true;
  case VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT:
  case VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT:
      // TODO
      return false;
  ```
  - **PQ (ST 2084)** maps to `PL_COLOR_PRIM_BT_2020` and `PL_COLOR_TRC_PQ`.
  - **HLG** maps to `PL_COLOR_PRIM_BT_2020` and `PL_COLOR_TRC_HLG`.
  - **Extended-range sRGB (scRGB)** is explicitly unhandled (`// TODO`, returns `false`).

### 3.4 Dynamic Surface Format Selection & Swapchain Recreation
In `libplacebo/src/vulkan/swapchain.c`:
- **Lines 176–271** (`pick_surf_format`):
  The format scoring system heavily prioritizes HDR when the video frame hints HDR:
  ```c
  bool prefer_hdr = pl_color_transfer_is_hdr(hint->transfer);
  ...
  // 10-bit depth preferred
  case 10:
      if (pl_color_transfer_is_hdr(space.transfer))
          score += 30;
  ...
  if (pl_color_transfer_is_hdr(space.transfer) == prefer_hdr)
      score += 10000;
  if (space.transfer == hint->transfer)
      score += 20000;
  ```
- **Lines 295–306**:
  ```c
  VkSurfaceFormatKHR new_sfmt = p->formats.elem[best_id];
  if (p->protoInfo.imageFormat != new_sfmt.format ||
      p->protoInfo.imageColorSpace != new_sfmt.colorSpace)
  {
      PL_INFO(vk, "Picked surface configuration %d: %s + %s", best_id,
              vk_fmt_name(new_sfmt.format),
              vk_csp_name(new_sfmt.colorSpace));

      p->protoInfo.imageFormat = new_sfmt.format;
      p->protoInfo.imageColorSpace = new_sfmt.colorSpace;
      p->needs_recreate = true;
  }
  ```
  When an HDR video is detected, `libplacebo` re-scores the surface formats, selects the HDR surface format (`VK_COLOR_SPACE_HDR10_ST2084_EXT` with e.g. `VK_FORMAT_A2R10G10B10_UNORM_PACK32`), and sets `needs_recreate = true`.
- **Lines 340–350** (`set_hdr_metadata`):
  When `SetHdrMetadataEXT` is present and the active swapchain is HDR, `libplacebo` sends primary color coordinates, white point, min/max luminance, maxCLL, and maxFALL directly to the Vulkan display driver.
- **Lines 1042–1069** (`vk_sw_colorspace_hint`):
  `vulkan_swapchain` provides `.colorspace_hint = vk_sw_colorspace_hint`, fully implementing colorspace hints.

### 3.5 mpv Integration (`vo_gpu_next`)
In `mpv/video/out/vo_gpu_next.c`:
- **Lines 216–230**:
  ```c
  {"target-colorspace-hint", OPT_CHOICE(target_hint, {"auto", -1}, {"no", 0}, {"yes", 1})},
  ...
  .target_hint = -1, // default auto
  ```
  `--target-colorspace-hint` defaults to `auto` (-1).
- **Lines 1233–1234**:
  ```c
  if (!pass_colorspace)
      pl_swapchain_colorspace_hint(p->sw, &hint);
  ```
  On every frame, `vo_gpu_next` sends the video colorspace and HDR mastering metadata to `libplacebo`'s swapchain.

---

## 4. Android OS & App-Side Requirements

Even though `libplacebo` and `mpv` have Vulkan HDR swapchain machinery, Android's window compositor (SurfaceFlinger) and window manager impose requirements:

### 4.1 Window HDR Mode (`Window.setColorMode`)
- **API**: `activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR` (API 26+)
- **Effect**: Signals WindowManager and SurfaceFlinger that the window contains HDR content. Without this, SurfaceFlinger may clamp or tone-map 10-bit surfaces down to SDR sRGB before compositing to display.

### 4.2 SurfaceView Buffer Format (`SurfaceHolder.setFormat`)
- **API**: `surfaceView.holder.setFormat(PixelFormat.RGBA_1010102)` (or `PixelFormat.TRANSLUCENT` / `UNKNOWN`)
- **Effect**: Configures the underlying `ANativeWindow` buffer queue to 10-bit depth (A2B10G10R10 or A2R10G10B10).
- Note: On Android, `ANativeWindow_setBuffersGeometry(native_window, 0, 0, format)` is called in `mpv`'s `context_android.c:84`. If the SurfaceView format is not set to 10-bit on the Java side before Vulkan queries surface formats, `vkGetPhysicalDeviceSurfaceFormatsKHR` might report only 8-bit formats.

### 4.3 HDR Headroom API (`setDesiredHdrHeadroom`)
- **API**: `SurfaceControl.Transaction.setDesiredHdrHeadroom(surfaceControl, headroom)` (API 34+) or `Window.setDesiredHdrHeadroom(float)` (API 35+ / API 37)
- **Effect**: Informs the display controller how many stops of HDR headroom (peak luminance ratio relative to SDR white) the video needs. This allows Android 14+ to adaptively boost display brightness on AMOLED panels without blinding the user on SDR UI elements.

### 4.4 SurfaceView Dataspace
- Android's Vulkan loader (`libvulkan.so`) automatically invokes `native_window_set_buffers_data_space` when `vkCreateSwapchainKHR` is called with an HDR color space:
  - `VK_COLOR_SPACE_HDR10_ST2084_EXT` -> `HAL_DATASPACE_BT2020_PQ` (`0x10040000`)
  - `VK_COLOR_SPACE_HDR10_HLG_EXT` -> `HAL_DATASPACE_BT2020_HLG` (`0x10050000`)
- *(Unverified on device without adb access: requires testing whether Qualcomm's driver cleanly sets this on redwood)*.

---

## 5. Qualcomm Adreno 642L Driver Status & Known Risks

1. **`VK_EXT_swapchain_colorspace` Exposure**:
   - `libplacebo` enables `VK_EXT_swapchain_colorspace` only if present in `vkEnumerateInstanceExtensionProperties`.
   - *(Unverified on device)*: Qualcomm's proprietary Vulkan driver for Adreno 642L on Android 14/15/17 usually advertises `VK_EXT_swapchain_colorspace`, but some vendor BSPs omit it from the instance extension query or only support it on certain formats.
2. **Swapchain Re-creation Stability**:
   - `libplacebo` recreates the swapchain on-the-fly (`needs_recreate = true`) when switching between SDR and HDR clips.
   - On some mobile Vulkan drivers, destroying and recreating `VkSwapchainKHR` on an `ANativeWindow` without detaching can cause an EGL/SurfaceFlinger buffer queue stall or crash. (VibePlayer's B-08 watchdog mitigates stalls, but stability needs device confirmation).
3. **`SetHdrMetadataEXT` Driver Support**:
   - `VK_EXT_hdr_metadata` is optional. If missing, `libplacebo` silently ignores metadata calls (lines 314–315), which is benign (the display will use the static PQ curve).

---

## 6. Concrete Device Test Plan (When Device Is Available)

*Do not run these commands while NO PHONE constraint is active.*

### Phase 1: Driver Extension & Format Probe
Probe whether the Adreno 642L driver on `redwood` supports the requisite instance extensions and surface formats.
```bash
# 1. Check if VK_EXT_swapchain_colorspace is exposed
adb shell "dumpsys SurfaceFlinger --dispsync" | grep -i hdr
adb shell "cmd gpu vkjson" | grep -i "VK_EXT_swapchain_colorspace"

# 2. Check Display HDR capabilities on redwood
adb shell dumpsys display | grep -i -E "mHdrCapabilities|hdr"
```
*Expected*: `mHdrCapabilities` lists HDR10, HLG; `vkjson` lists `VK_EXT_swapchain_colorspace`.

### Phase 2: App Configuration
1. In `app/src/main/java/com/vibeplayer/ui/player/PlayerActivity.kt`:
   - Set `window.colorMode = ActivityInfo.COLOR_MODE_HDR`
   - Set `surfaceView.holder.setFormat(PixelFormat.RGBA_1010102)`
2. In `PlayerEngine.kt` / `PlaybackMode.kt`:
   - Ensure renderer is Vulkan (`gpu-api=vulkan`, `gpu-context=androidvk`, `vo=gpu-next`)
   - Ensure `target-colorspace-hint=yes`

### Phase 3: Playback & Logcat Verification
Play HDR10 test clip `/sdcard/Movies/vibeplayer-test/hevc10_4k.mp4` (or dedicated HDR PQ / HLG test clips):
```bash
adb logcat -s mpv:V SurfaceFlinger:D | grep -E "Picked surface configuration|colorspace|dataspace|HDR"
```
*Expected Log Lines*:
- `[vo/gpu-next/libplacebo] Picked surface configuration <id>: ... + VK_COLOR_SPACE_HDR10_ST2084_EXT`
- SurfaceFlinger buffer layer dataspace reported as `BT2020_PQ` (`0x10040000`).

### Phase 4: Visual & Display Verification
- **Visual Check**: Specular highlights (e.g. sun, lamps, reflections) should display higher peak luminance without clipping or blowing out mid-tones; colors should appear vibrant and not washed-out (washed-out appearance indicates PQ data sent to an SDR sRGB surface).
- **OSD / UI Isolation**: Check that Compose controls remain at standard SDR white and are not oversaturated or glaring.
- **SDR Playback**: Reopen an 8-bit SDR video (`ichiban.mp4`) to confirm smooth swapchain transition back to sRGB SDR without crash or color distortions.

---

## 7. Explicit List of Unverified Items

In strict adherence to the project rules, the following items were not directly observed in the source trees and require device verification:
1. **Adreno 642L Extension List**: Whether the specific proprietary Vulkan driver version installed on `redwood` enables `VK_EXT_swapchain_colorspace` for 32-bit/64-bit app processes.
2. **Qualcomm 10-bit Surface Formats**: Whether `vkGetPhysicalDeviceSurfaceFormatsKHR` returns `VK_FORMAT_A2R10G10B10_UNORM_PACK32` or `VK_FORMAT_A2B10G10R10_UNORM_PACK32` when paired with `VK_COLOR_SPACE_HDR10_ST2084_EXT` on Android 17.
3. **ANativeWindow Format Collision**: In `context_android.c:84`, `ANativeWindow_setBuffersGeometry(native_window, w, h, current_format)` is called during reconfig. Does calling this with a format obtained from `ANativeWindow_getFormat` override or break Vulkan's internal 10-bit buffer allocation?
4. **Android 17 Headroom Dimming Curve**: How Android 17's WindowManager scales SDR controls over an HDR SurfaceView on `redwood` when `Window.setColorMode(COLOR_MODE_HDR)` is enabled.
