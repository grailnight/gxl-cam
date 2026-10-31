#pragma once

#include <cstddef>
#include <cstdint>

namespace gxl {

// Converts an Android YUV_420_888 camera frame (JFIF: BT.601 full range,
// chroma sited between luma samples) to interleaved 8-bit RGB.
// Chroma is upsampled with the same triangle filter libjpeg uses
// ("fancy upsampling") so no blockiness is introduced.
// `out` must hold width * height * 3 bytes.
void Yuv420ToRgb8(const uint8_t* y, size_t y_row_stride, size_t y_pixel_stride,
                  const uint8_t* u, const uint8_t* v, size_t uv_row_stride,
                  size_t uv_pixel_stride, uint32_t width, uint32_t height,
                  uint8_t* out, size_t threads);

// Box-downscales interleaved RGB8 by an integer factor into an RGBA8 buffer
// (Android ARGB_8888 memory layout, opaque).
void DownscaleRgbToRgba(const uint8_t* rgb, uint32_t width, uint32_t height,
                        uint32_t factor, uint8_t* out, size_t out_stride,
                        uint32_t out_width, uint32_t out_height);

}  // namespace gxl
