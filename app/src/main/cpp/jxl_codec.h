// Thin, JNI-free wrapper around libjxl used by the camera app.
// Kept independent of JNI so it can be unit-tested on a desktop host.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace gxl {

enum class ColorSpace : int {
  kSRGB = 0,
  kDisplayP3 = 1,  // P3 primaries, D65, sRGB transfer curve
};

struct EncodeOptions {
  // Butteraugli distance. 0 = mathematically lossless, 1.0 = visually lossless.
  float distance = 1.0f;
  // libjxl effort 1 (fastest) .. 10 (slowest).
  int effort = 7;
  // EXIF/JXL orientation 1..8.
  int orientation = 1;
  ColorSpace color = ColorSpace::kSRGB;
  // Raw TIFF block ("II*\0" / "MM\0*" ...). Stored in an "Exif" box. Optional.
  const uint8_t* exif = nullptr;
  size_t exif_size = 0;
  // Worker threads, 0 = number of CPU cores.
  size_t threads = 0;
};

// Encodes interleaved 8-bit RGB pixels (rows tightly packed: stride = 3 * w).
bool EncodeRgb8(const uint8_t* rgb, uint32_t width, uint32_t height,
                const EncodeOptions& options, std::vector<uint8_t>* out,
                std::string* error);

// Losslessly recompresses a JPEG file into JPEG XL. The original JPEG can be
// reconstructed bit-exactly from the result (see ReconstructJpeg). EXIF/XMP
// are preserved and the EXIF orientation is carried into the codestream.
bool TranscodeJpeg(const uint8_t* jpeg, size_t jpeg_size, int effort,
                   size_t threads, std::vector<uint8_t>* out,
                   std::string* error);

struct ImageInfo {
  uint32_t width = 0;   // after applying orientation
  uint32_t height = 0;  // after applying orientation
  uint32_t orientation = 1;
  bool has_alpha = false;
  bool is_lossy = false;              // XYB encoded (VarDCT)
  bool has_jpeg_reconstruction = false;  // "jbrd" box present
  uint32_t bits_per_sample = 8;
};

bool Probe(const uint8_t* data, size_t size, ImageInfo* info,
           std::string* error);

// Decodes to 8-bit RGBA, alpha premultiplied (the memory layout of Android's
// ARGB_8888 bitmaps), with orientation applied. Pixels are converted to
// `target` color space when possible; `*actual` receives the color space the
// pixels are really in. If out_width/out_height are smaller than the image,
// the image is box-downscaled by an integer factor to exactly fit them.
bool DecodeRgba8(const uint8_t* data, size_t size, ColorSpace target,
                 uint8_t* out, size_t out_stride, uint32_t out_width,
                 uint32_t out_height, size_t threads, ColorSpace* actual,
                 std::string* error);

// Rebuilds the original JPEG file from a losslessly recompressed JPEG XL.
bool ReconstructJpeg(const uint8_t* data, size_t size, std::vector<uint8_t>* out,
                     std::string* error);

std::string LibjxlVersion();

}  // namespace gxl
