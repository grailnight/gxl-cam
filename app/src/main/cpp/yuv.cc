#include "yuv.h"

#include <algorithm>
#include <thread>
#include <vector>

namespace gxl {
namespace {

inline uint8_t Clamp255(int v) {
  return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

// Fixed-point (Q12) JFIF coefficients.
constexpr int kCrToR = 5743;  // 1.402
constexpr int kCbToG = 1410;  // 0.344136
constexpr int kCrToG = 2925;  // 0.714136
constexpr int kCbToB = 7258;  // 1.772

void ConvertRows(const uint8_t* y_plane, size_t y_row_stride,
                 size_t y_pixel_stride, const uint8_t* u_plane,
                 const uint8_t* v_plane, size_t uv_row_stride,
                 size_t uv_pixel_stride, uint32_t width, uint32_t height,
                 uint8_t* out, uint32_t row_begin, uint32_t row_end) {
  const uint32_t cw = (width + 1) / 2;
  const uint32_t ch = (height + 1) / 2;
  // Horizontally upsampled chroma for the two chroma rows involved (x4 scale).
  std::vector<int> cb_near(width), cr_near(width), cb_far(width), cr_far(width);

  auto upsample_row = [&](uint32_t crow, int* cb, int* cr) {
    const uint8_t* urow = u_plane + crow * uv_row_stride;
    const uint8_t* vrow = v_plane + crow * uv_row_stride;
    for (uint32_t x = 0; x < width; ++x) {
      const uint32_t c0 = x / 2;
      uint32_t c1;
      if (x & 1) {
        c1 = std::min(c0 + 1, cw - 1);
      } else {
        c1 = c0 == 0 ? 0 : c0 - 1;
      }
      cb[x] = 3 * urow[c0 * uv_pixel_stride] + urow[c1 * uv_pixel_stride];
      cr[x] = 3 * vrow[c0 * uv_pixel_stride] + vrow[c1 * uv_pixel_stride];
    }
  };

  for (uint32_t row = row_begin; row < row_end; ++row) {
    const uint32_t c0 = row / 2;
    uint32_t c1;
    if (row & 1) {
      c1 = std::min(c0 + 1, ch - 1);
    } else {
      c1 = c0 == 0 ? 0 : c0 - 1;
    }
    upsample_row(c0, cb_near.data(), cr_near.data());
    upsample_row(c1, cb_far.data(), cr_far.data());

    const uint8_t* yrow = y_plane + row * y_row_stride;
    uint8_t* dst = out + size_t{row} * width * 3;
    for (uint32_t x = 0; x < width; ++x) {
      // Chroma values at x16 scale, centered on 128*16 = 2048.
      const int cb = 3 * cb_near[x] + cb_far[x] - 2048;
      const int cr = 3 * cr_near[x] + cr_far[x] - 2048;
      const int luma = yrow[x * y_pixel_stride];
      // (c * K) is Q12 * x16 = Q16; round and shift.
      dst[0] = Clamp255(luma + ((cr * kCrToR + 32768) >> 16));
      dst[1] = Clamp255(luma - ((cb * kCbToG + cr * kCrToG + 32768) >> 16));
      dst[2] = Clamp255(luma + ((cb * kCbToB + 32768) >> 16));
      dst += 3;
    }
  }
}

}  // namespace

void Yuv420ToRgb8(const uint8_t* y, size_t y_row_stride, size_t y_pixel_stride,
                  const uint8_t* u, const uint8_t* v, size_t uv_row_stride,
                  size_t uv_pixel_stride, uint32_t width, uint32_t height,
                  uint8_t* out, size_t threads) {
  if (threads == 0) threads = std::max(1u, std::thread::hardware_concurrency());
  threads = std::min<size_t>(threads, std::max<uint32_t>(1, height / 64));
  if (threads <= 1) {
    ConvertRows(y, y_row_stride, y_pixel_stride, u, v, uv_row_stride,
                uv_pixel_stride, width, height, out, 0, height);
    return;
  }
  std::vector<std::thread> workers;
  const uint32_t chunk = (height + threads - 1) / threads;
  for (size_t t = 0; t < threads; ++t) {
    const uint32_t begin = static_cast<uint32_t>(t * chunk);
    const uint32_t end = std::min(height, begin + chunk);
    if (begin >= end) break;
    workers.emplace_back(ConvertRows, y, y_row_stride, y_pixel_stride, u, v,
                         uv_row_stride, uv_pixel_stride, width, height, out,
                         begin, end);
  }
  for (auto& w : workers) w.join();
}

void DownscaleRgbToRgba(const uint8_t* rgb, uint32_t width, uint32_t height,
                        uint32_t factor, uint8_t* out, size_t out_stride,
                        uint32_t out_width, uint32_t out_height) {
  (void)height;
  const uint32_t n = factor * factor;
  for (uint32_t oy = 0; oy < out_height; ++oy) {
    uint8_t* dst = out + oy * out_stride;
    for (uint32_t ox = 0; ox < out_width; ++ox) {
      uint32_t acc[3] = {0, 0, 0};
      for (uint32_t dy = 0; dy < factor; ++dy) {
        const uint8_t* src =
            rgb + (size_t{oy * factor + dy} * width + size_t{ox} * factor) * 3;
        for (uint32_t dx = 0; dx < factor; ++dx, src += 3) {
          acc[0] += src[0];
          acc[1] += src[1];
          acc[2] += src[2];
        }
      }
      dst[ox * 4 + 0] = static_cast<uint8_t>((acc[0] + n / 2) / n);
      dst[ox * 4 + 1] = static_cast<uint8_t>((acc[1] + n / 2) / n);
      dst[ox * 4 + 2] = static_cast<uint8_t>((acc[2] + n / 2) / n);
      dst[ox * 4 + 3] = 255;
    }
  }
}

}  // namespace gxl
