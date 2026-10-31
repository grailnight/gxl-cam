// Host-side tests for the native codec layer (runs on Linux/macOS, not Android).
// Build: see native-tests/CMakeLists.txt. Usage: codec_test [file.jpg ...]
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "yuv.h"
#ifndef GXL_YUV_ONLY
#include "jxl_codec.h"
#endif

static int failures = 0;
#define CHECK(c)                                                   \
  do {                                                             \
    if (!(c)) {                                                    \
      std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c);      \
      ++failures;                                                  \
    }                                                              \
  } while (0)

static double Psnr(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
  double se = 0;
  for (size_t i = 0; i < a.size(); ++i) {
    const double d = double(a[i]) - b[i];
    se += d * d;
  }
  const double mse = se / a.size();
  return mse == 0 ? 99 : 10 * std::log10(255.0 * 255.0 / mse);
}

static uint8_t C(double v) { return v < 0 ? 0 : v > 255 ? 255 : uint8_t(std::lround(v)); }

int main(int argc, char** argv) {
  (void)argc;
  (void)argv;
  // Odd dimensions exercise the chroma edge handling.
  const uint32_t W = 641, H = 479;
  std::vector<uint8_t> rgb(W * H * 3);
  for (uint32_t y = 0; y < H; ++y) {
    for (uint32_t x = 0; x < W; ++x) {
      uint8_t* p = &rgb[(y * W + x) * 3];
      p[0] = C(128 + 100 * std::sin(x / 40.0));
      p[1] = C(255.0 * y / H);
      p[2] = C(128 + 90 * std::cos((x + y) / 55.0));
    }
  }

  // ---- RGB -> YUV420 (NV21-like layout, padded rows) -> RGB ---------------
  const size_t yrs = W + 31, cw = (W + 1) / 2, ch = (H + 1) / 2, uvrs = cw * 2 + 17;
  std::vector<uint8_t> Y(yrs * H), VU(uvrs * ch + 1);
  std::vector<double> cb(W * H), cr(W * H);
  for (uint32_t i = 0; i < W * H; ++i) {
    const double r = rgb[i * 3], g = rgb[i * 3 + 1], b = rgb[i * 3 + 2];
    Y[(i / W) * yrs + i % W] = C(0.299 * r + 0.587 * g + 0.114 * b);
    cb[i] = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
    cr[i] = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
  }
  for (size_t cy = 0; cy < ch; ++cy) {
    for (size_t cx = 0; cx < cw; ++cx) {
      double sb = 0, sr = 0;
      int n = 0;
      for (int dy = 0; dy < 2; ++dy) {
        for (int dx = 0; dx < 2; ++dx) {
          const size_t x = cx * 2 + dx, y = cy * 2 + dy;
          if (x >= W || y >= H) continue;
          sb += cb[y * W + x];
          sr += cr[y * W + x];
          ++n;
        }
      }
      VU[cy * uvrs + cx * 2] = C(sr / n);      // V first (NV21)
      VU[cy * uvrs + cx * 2 + 1] = C(sb / n);  // then U
    }
  }
  std::vector<uint8_t> back(W * H * 3), back1(W * H * 3);
  gxl::Yuv420ToRgb8(Y.data(), yrs, 1, VU.data() + 1, VU.data(), uvrs, 2, W, H, back.data(), 4);
  gxl::Yuv420ToRgb8(Y.data(), yrs, 1, VU.data() + 1, VU.data(), uvrs, 2, W, H, back1.data(), 1);
  const double yuv_psnr = Psnr(rgb, back);
  std::printf("YUV420 -> RGB roundtrip PSNR %.2f dB\n", yuv_psnr);
  CHECK(yuv_psnr > 38);
  CHECK(back == back1);  // threading must not change the result

  // Neutral grey must stay exactly neutral.
  {
    std::vector<uint8_t> y(16 * 16, 77), uv(8 * 8 * 2, 128), out(16 * 16 * 3);
    gxl::Yuv420ToRgb8(y.data(), 16, 1, uv.data(), uv.data() + 1, 16, 2, 16, 16, out.data(), 1);
    CHECK(std::all_of(out.begin(), out.end(), [](uint8_t v) { return v == 77; }));
  }
  // Planar (I420) layout with pixel stride 1.
  {
    std::vector<uint8_t> y(4 * 4, 200), u(2 * 2, 128), v(2 * 2, 255), out(4 * 4 * 3);
    gxl::Yuv420ToRgb8(y.data(), 4, 1, u.data(), v.data(), 2, 1, 4, 4, out.data(), 1);
    // Cr = +127 -> R saturates, G drops, B unchanged.
    CHECK(out[0] == 255 && out[1] == C(200 - 0.714136 * 127) && out[2] == 200);
  }
  // Downscale RGB -> RGBA thumbnail.
  {
    std::vector<uint8_t> thumb((W / 8) * (H / 8) * 4);
    gxl::DownscaleRgbToRgba(rgb.data(), W, H, 8, thumb.data(), (W / 8) * 4, W / 8, H / 8);
    CHECK(thumb[3] == 255);
    double avg = 0;
    for (int dy = 0; dy < 8; ++dy) for (int dx = 0; dx < 8; ++dx) avg += rgb[(dy * W + dx) * 3];
    CHECK(std::abs(thumb[0] - avg / 64) <= 1);
  }

#ifndef GXL_YUV_ONLY
  std::printf("libjxl %s\n", gxl::LibjxlVersion().c_str());
  std::vector<uint8_t> jxl;
  std::string err;
  gxl::ImageInfo info;
  gxl::ColorSpace actual;

  // ---- lossless roundtrip -------------------------------------------------
  gxl::EncodeOptions o;
  o.distance = 0;
  o.effort = 3;
  CHECK(gxl::EncodeRgb8(rgb.data(), W, H, o, &jxl, &err));
  std::printf("lossless e3: %zu bytes\n", jxl.size());
  CHECK(gxl::Probe(jxl.data(), jxl.size(), &info, &err));
  CHECK(info.width == W && info.height == H && !info.is_lossy && !info.has_jpeg_reconstruction);
  std::vector<uint8_t> rgba(W * H * 4);
  CHECK(gxl::DecodeRgba8(jxl.data(), jxl.size(), gxl::ColorSpace::kSRGB, rgba.data(), W * 4, W,
                         H, 0, &actual, &err));
  bool exact = true;
  for (size_t i = 0; i < W * H; ++i) {
    for (int c = 0; c < 3; ++c) exact &= rgba[i * 4 + c] == rgb[i * 3 + c];
    exact &= rgba[i * 4 + 3] == 255;
  }
  CHECK(exact);
  CHECK(actual == gxl::ColorSpace::kSRGB);
  // Decoding into a padded stride (Android bitmaps may have one).
  const size_t stride = W * 4 + 64;
  std::vector<uint8_t> padded(stride * H);
  CHECK(gxl::DecodeRgba8(jxl.data(), jxl.size(), gxl::ColorSpace::kSRGB, padded.data(), stride, W,
                         H, 0, &actual, &err));
  bool exact2 = true;
  for (uint32_t y = 0; y < H; ++y) exact2 &= std::memcmp(&padded[y * stride], &rgba[y * W * 4], W * 4) == 0;
  CHECK(exact2);

  // ---- lossy + orientation 6 + EXIF box -----------------------------------
  const uint8_t tiff[] = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0,
                          0,   0,   6,  0, 0, 0, 0, 0, 0, 0};
  o.distance = 1.0f;
  o.effort = 7;
  o.orientation = 6;
  o.exif = tiff;
  o.exif_size = sizeof(tiff);
  CHECK(gxl::EncodeRgb8(rgb.data(), W, H, o, &jxl, &err));
  std::printf("lossy d1 e7: %zu bytes (%.2f bpp)\n", jxl.size(), jxl.size() * 8.0 / (W * H));
  CHECK(gxl::Probe(jxl.data(), jxl.size(), &info, &err));
  CHECK(info.width == H && info.height == W && info.orientation == 6 && info.is_lossy);
  const char exif_tag[] = "Exif";
  CHECK(std::search(jxl.begin(), jxl.end(), exif_tag, exif_tag + 4) != jxl.end());
  std::vector<uint8_t> rot(W * H * 4);
  CHECK(gxl::DecodeRgba8(jxl.data(), jxl.size(), gxl::ColorSpace::kSRGB, rot.data(), H * 4, H, W,
                         0, &actual, &err));
  // Orientation 6 = rotate 90° clockwise; undo it to compare with the source.
  std::vector<uint8_t> unrot(W * H * 3);
  for (uint32_t yy = 0; yy < W; ++yy) {
    for (uint32_t xx = 0; xx < H; ++xx) {
      const uint32_t sx = yy, sy = H - 1 - xx;
      for (int c = 0; c < 3; ++c) unrot[(sy * W + sx) * 3 + c] = rot[(yy * H + xx) * 4 + c];
    }
  }
  const double lossy_psnr = Psnr(rgb, unrot);
  std::printf("lossy PSNR %.2f dB\n", lossy_psnr);
  CHECK(lossy_psnr > 38);
  std::vector<uint8_t> half((H / 2) * (W / 2) * 4);
  CHECK(gxl::DecodeRgba8(jxl.data(), jxl.size(), gxl::ColorSpace::kSRGB, half.data(), (H / 2) * 4,
                         H / 2, W / 2, 0, &actual, &err));

  // ---- Display P3 -------------------------------------------------------
  o.color = gxl::ColorSpace::kDisplayP3;
  o.exif = nullptr;
  o.exif_size = 0;
  o.orientation = 1;
  CHECK(gxl::EncodeRgb8(rgb.data(), W, H, o, &jxl, &err));
  CHECK(gxl::DecodeRgba8(jxl.data(), jxl.size(), gxl::ColorSpace::kDisplayP3, rgba.data(), W * 4,
                         W, H, 0, &actual, &err));
  CHECK(actual == gxl::ColorSpace::kDisplayP3);
  std::vector<uint8_t> rgb_p3(W * H * 3);
  for (size_t i = 0; i < W * H; ++i) for (int c = 0; c < 3; ++c) rgb_p3[i * 3 + c] = rgba[i * 4 + c];
  std::printf("P3 -> P3 PSNR %.2f dB\n", Psnr(rgb, rgb_p3));
  CHECK(Psnr(rgb, rgb_p3) > 38);

  // ---- lossless JPEG recompression ----------------------------------------
  for (int a = 1; a < argc; ++a) {
    std::ifstream f(argv[a], std::ios::binary);
    std::vector<uint8_t> jpeg((std::istreambuf_iterator<char>(f)), {});
    std::vector<uint8_t> tj, rec;
    const bool ok = gxl::TranscodeJpeg(jpeg.data(), jpeg.size(), 7, 0, &tj, &err);
    CHECK(ok);
    if (!ok) {
      std::printf("  %s: %s\n", argv[a], err.c_str());
      continue;
    }
    CHECK(gxl::Probe(tj.data(), tj.size(), &info, &err));
    CHECK(info.has_jpeg_reconstruction && !info.is_lossy);
    CHECK(gxl::ReconstructJpeg(tj.data(), tj.size(), &rec, &err));
    CHECK(rec == jpeg);
    std::printf("%s: JPEG %zu -> JXL %zu bytes (%.1f%% smaller), %ux%u orientation %u, %s\n",
                argv[a], jpeg.size(), tj.size(), 100.0 * (1.0 - double(tj.size()) / jpeg.size()),
                info.width, info.height, info.orientation,
                rec == jpeg ? "reconstruction bit-exact" : "reconstruction MISMATCH");
    if (std::string(argv[a]).find("orient6") != std::string::npos) {
      // EXIF orientation 6 must be carried into the codestream; 800x600 stored.
      CHECK(info.orientation == 6 && info.width == 600 && info.height == 800);
    } else {
      CHECK(info.orientation == 1 && info.width == 800 && info.height == 600);
    }
    std::vector<uint8_t> px(size_t(info.width) * info.height * 4);
    CHECK(gxl::DecodeRgba8(tj.data(), tj.size(), gxl::ColorSpace::kDisplayP3, px.data(),
                           info.width * 4, info.width, info.height, 0, &actual, &err));
    CHECK(actual == gxl::ColorSpace::kDisplayP3);
  }
  std::vector<uint8_t> rec;
  CHECK(!gxl::ReconstructJpeg(jxl.data(), jxl.size(), &rec, &err));  // not a recompressed JPEG
  const uint8_t junk[] = {1, 2, 3, 4, 5};
  CHECK(!gxl::Probe(junk, sizeof(junk), &info, &err));
#endif

  std::printf(failures ? "FAILED (%d)\n" : "ALL OK\n", failures);
  return failures ? 1 : 0;
}
