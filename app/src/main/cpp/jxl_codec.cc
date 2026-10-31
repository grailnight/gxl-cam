#include "jxl_codec.h"

#include <jxl/cms.h>
#include <jxl/codestream_header.h>
#include <jxl/color_encoding.h>
#include <jxl/decode.h>
#include <jxl/decode_cxx.h>
#include <jxl/encode.h>
#include <jxl/encode_cxx.h>
#include <jxl/thread_parallel_runner.h>
#include <jxl/thread_parallel_runner_cxx.h>
#include <jxl/version.h>

#include <algorithm>
#include <cstring>

namespace gxl {
namespace {

size_t ResolveThreads(size_t threads) {
  return threads != 0 ? threads : JxlThreadParallelRunnerDefaultNumWorkerThreads();
}

JxlColorEncoding MakeColorEncoding(ColorSpace cs) {
  JxlColorEncoding ce;
  JxlColorEncodingSetToSRGB(&ce, /*is_gray=*/JXL_FALSE);
  if (cs == ColorSpace::kDisplayP3) {
    ce.primaries = JXL_PRIMARIES_P3;
    ce.white_point = JXL_WHITE_POINT_D65;
    ce.transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
  }
  return ce;
}

// Drains the encoder output into `out`.
bool ProcessOutput(JxlEncoder* enc, std::vector<uint8_t>* out,
                   std::string* error) {
  out->resize(1 << 20);
  uint8_t* next_out = out->data();
  size_t avail_out = out->size();
  JxlEncoderStatus status;
  while ((status = JxlEncoderProcessOutput(enc, &next_out, &avail_out)) ==
         JXL_ENC_NEED_MORE_OUTPUT) {
    const size_t offset = next_out - out->data();
    out->resize(out->size() * 2);
    next_out = out->data() + offset;
    avail_out = out->size() - offset;
  }
  out->resize(next_out - out->data());
  if (status != JXL_ENC_SUCCESS) {
    *error = "JxlEncoderProcessOutput failed, error " +
             std::to_string(static_cast<int>(JxlEncoderGetError(enc)));
    return false;
  }
  return true;
}

#define GXL_CHECK_ENC(call, what)                                         \
  do {                                                                    \
    if ((call) != JXL_ENC_SUCCESS) {                                      \
      *error = std::string(what) + " failed, error " +                    \
               std::to_string(static_cast<int>(JxlEncoderGetError(enc))); \
      return false;                                                       \
    }                                                                     \
  } while (0)

// Walks ISO BMFF boxes of a JXL container looking for a box type.
bool HasContainerBox(const uint8_t* data, size_t size, const char type[4]) {
  static const uint8_t kSig[12] = {0, 0, 0, 0x0C, 'J', 'X', 'L', ' ',
                                   0x0D, 0x0A, 0x87, 0x0A};
  if (size < 12 || std::memcmp(data, kSig, 12) != 0) return false;
  size_t pos = 0;
  while (pos + 8 <= size) {
    uint64_t box_size = (uint64_t{data[pos]} << 24) | (data[pos + 1] << 16) |
                        (data[pos + 2] << 8) | data[pos + 3];
    size_t header = 8;
    if (box_size == 1) {
      if (pos + 16 > size) return false;
      box_size = 0;
      for (int i = 0; i < 8; ++i) box_size = (box_size << 8) | data[pos + 8 + i];
      header = 16;
    }
    if (std::memcmp(data + pos + 4, type, 4) == 0) return true;
    if (box_size == 0) return false;  // box extends to end of file
    if (box_size < header || box_size > size - pos) return false;
    pos += static_cast<size_t>(box_size);
  }
  return false;
}

}  // namespace

std::string LibjxlVersion() {
  const uint32_t v = JxlDecoderVersion();
  return std::to_string(v / 1000000) + "." + std::to_string((v / 1000) % 1000) +
         "." + std::to_string(v % 1000);
}

bool EncodeRgb8(const uint8_t* rgb, uint32_t width, uint32_t height,
                const EncodeOptions& options, std::vector<uint8_t>* out,
                std::string* error) {
  JxlEncoderPtr enc_ptr = JxlEncoderMake(nullptr);
  JxlEncoder* enc = enc_ptr.get();
  JxlThreadParallelRunnerPtr runner =
      JxlThreadParallelRunnerMake(nullptr, ResolveThreads(options.threads));
  GXL_CHECK_ENC(
      JxlEncoderSetParallelRunner(enc, JxlThreadParallelRunner, runner.get()),
      "JxlEncoderSetParallelRunner");

  const bool lossless = options.distance <= 0.0f;

  JxlBasicInfo info;
  JxlEncoderInitBasicInfo(&info);
  info.xsize = width;
  info.ysize = height;
  info.bits_per_sample = 8;
  info.exponent_bits_per_sample = 0;
  info.num_color_channels = 3;
  info.alpha_bits = 0;
  info.num_extra_channels = 0;
  info.orientation = static_cast<JxlOrientation>(
      std::clamp(options.orientation, 1, 8));
  info.uses_original_profile = lossless ? JXL_TRUE : JXL_FALSE;
  GXL_CHECK_ENC(JxlEncoderSetBasicInfo(enc, &info), "JxlEncoderSetBasicInfo");

  const JxlColorEncoding ce = MakeColorEncoding(options.color);
  GXL_CHECK_ENC(JxlEncoderSetColorEncoding(enc, &ce),
                "JxlEncoderSetColorEncoding");

  if (options.exif != nullptr && options.exif_size > 0) {
    // The Exif box starts with a 4-byte big-endian offset to the TIFF header.
    std::vector<uint8_t> box(4 + options.exif_size, 0);
    std::memcpy(box.data() + 4, options.exif, options.exif_size);
    GXL_CHECK_ENC(JxlEncoderUseBoxes(enc), "JxlEncoderUseBoxes");
    GXL_CHECK_ENC(JxlEncoderAddBox(enc, "Exif", box.data(), box.size(),
                                   /*compress_box=*/JXL_FALSE),
                  "JxlEncoderAddBox(Exif)");
    JxlEncoderCloseBoxes(enc);
  }

  JxlEncoderFrameSettings* fs = JxlEncoderFrameSettingsCreate(enc, nullptr);
  GXL_CHECK_ENC(JxlEncoderFrameSettingsSetOption(
                    fs, JXL_ENC_FRAME_SETTING_EFFORT,
                    std::clamp(options.effort, 1, 10)),
                "effort");
  if (lossless) {
    GXL_CHECK_ENC(JxlEncoderSetFrameLossless(fs, JXL_TRUE), "lossless");
  } else {
    GXL_CHECK_ENC(JxlEncoderSetFrameDistance(fs, options.distance), "distance");
  }

  const JxlPixelFormat format = {3, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
  GXL_CHECK_ENC(JxlEncoderAddImageFrame(fs, &format, rgb,
                                        size_t{width} * height * 3),
                "JxlEncoderAddImageFrame");
  JxlEncoderCloseInput(enc);
  return ProcessOutput(enc, out, error);
}

bool TranscodeJpeg(const uint8_t* jpeg, size_t jpeg_size, int effort,
                   size_t threads, std::vector<uint8_t>* out,
                   std::string* error) {
  JxlEncoderPtr enc_ptr = JxlEncoderMake(nullptr);
  JxlEncoder* enc = enc_ptr.get();
  JxlThreadParallelRunnerPtr runner =
      JxlThreadParallelRunnerMake(nullptr, ResolveThreads(threads));
  GXL_CHECK_ENC(
      JxlEncoderSetParallelRunner(enc, JxlThreadParallelRunner, runner.get()),
      "JxlEncoderSetParallelRunner");
  // Keep what is needed to rebuild the exact original JPEG file.
  GXL_CHECK_ENC(JxlEncoderStoreJPEGMetadata(enc, JXL_TRUE),
                "JxlEncoderStoreJPEGMetadata");

  JxlEncoderFrameSettings* fs = JxlEncoderFrameSettingsCreate(enc, nullptr);
  GXL_CHECK_ENC(JxlEncoderFrameSettingsSetOption(
                    fs, JXL_ENC_FRAME_SETTING_EFFORT, std::clamp(effort, 1, 10)),
                "effort");
  GXL_CHECK_ENC(JxlEncoderAddJPEGFrame(fs, jpeg, jpeg_size),
                "JxlEncoderAddJPEGFrame");
  JxlEncoderCloseInput(enc);
  return ProcessOutput(enc, out, error);
}

bool Probe(const uint8_t* data, size_t size, ImageInfo* out_info,
           std::string* error) {
  const JxlSignature sig = JxlSignatureCheck(data, size);
  if (sig != JXL_SIG_CODESTREAM && sig != JXL_SIG_CONTAINER) {
    *error = "not a JPEG XL file";
    return false;
  }
  JxlDecoderPtr dec = JxlDecoderMake(nullptr);
  // Keep orientation so the basic info reports the real orientation and the
  // stored (un-oriented) size; the oriented size is computed below.
  if (JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO) !=
          JXL_DEC_SUCCESS ||
      JxlDecoderSetKeepOrientation(dec.get(), JXL_TRUE) != JXL_DEC_SUCCESS ||
      JxlDecoderSetInput(dec.get(), data, size) != JXL_DEC_SUCCESS) {
    *error = "decoder setup failed";
    return false;
  }
  JxlDecoderCloseInput(dec.get());
  if (JxlDecoderProcessInput(dec.get()) != JXL_DEC_BASIC_INFO) {
    *error = "could not read JPEG XL header";
    return false;
  }
  JxlBasicInfo info;
  if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS) {
    *error = "JxlDecoderGetBasicInfo failed";
    return false;
  }
  const bool transposed = info.orientation > JXL_ORIENT_ROTATE_180;
  out_info->width = transposed ? info.ysize : info.xsize;
  out_info->height = transposed ? info.xsize : info.ysize;
  out_info->orientation = info.orientation;
  out_info->has_alpha = info.alpha_bits > 0;
  out_info->is_lossy = info.uses_original_profile == JXL_FALSE;
  out_info->bits_per_sample = info.bits_per_sample;
  out_info->has_jpeg_reconstruction = HasContainerBox(data, size, "jbrd");
  return true;
}

bool DecodeRgba8(const uint8_t* data, size_t size, ColorSpace target,
                 uint8_t* out, size_t out_stride, uint32_t out_width,
                 uint32_t out_height, size_t threads, ColorSpace* actual,
                 std::string* error) {
  JxlDecoderPtr dec = JxlDecoderMake(nullptr);
  JxlThreadParallelRunnerPtr runner =
      JxlThreadParallelRunnerMake(nullptr, ResolveThreads(threads));
  if (JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO |
                                               JXL_DEC_COLOR_ENCODING |
                                               JXL_DEC_FULL_IMAGE) !=
          JXL_DEC_SUCCESS ||
      JxlDecoderSetParallelRunner(dec.get(), JxlThreadParallelRunner,
                                  runner.get()) != JXL_DEC_SUCCESS ||
      JxlDecoderSetCms(dec.get(), *JxlGetDefaultCms()) != JXL_DEC_SUCCESS ||
      JxlDecoderSetInput(dec.get(), data, size) != JXL_DEC_SUCCESS) {
    *error = "decoder setup failed";
    return false;
  }
  JxlDecoderCloseInput(dec.get());

  JxlBasicInfo info{};
  uint32_t img_w = 0, img_h = 0, factor = 1;
  bool direct = true;         // decode straight into `out`
  std::vector<uint8_t> full;  // only used when downscaling
  *actual = ColorSpace::kSRGB;

  for (;;) {
    switch (JxlDecoderProcessInput(dec.get())) {
      case JXL_DEC_BASIC_INFO: {
        if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS) {
          *error = "JxlDecoderGetBasicInfo failed";
          return false;
        }
        // Orientation is applied by the decoder (keep_orientation is off), so
        // libjxl already reports the oriented size here.
        img_w = info.xsize;
        img_h = info.ysize;
        if (out_width == 0 || out_height == 0 || out_width > img_w ||
            out_height > img_h || out_stride < size_t{out_width} * 4) {
          *error = "bad output size";
          return false;
        }
        direct = out_width == img_w && out_height == img_h;
        factor = std::min(img_w / out_width, img_h / out_height);
        break;
      }
      case JXL_DEC_COLOR_ENCODING: {
        const JxlColorEncoding ce = MakeColorEncoding(target);
        if (JxlDecoderSetOutputColorProfile(dec.get(), &ce, nullptr, 0) ==
            JXL_DEC_SUCCESS) {
          *actual = target;
        }
        break;
      }
      case JXL_DEC_NEED_IMAGE_OUT_BUFFER: {
        JxlDecoderStatus st;
        if (direct) {
          // align == stride makes libjxl write rows exactly `out_stride` apart.
          const JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN,
                                         out_stride};
          st = JxlDecoderSetImageOutBuffer(dec.get(), &format, out,
                                           out_stride * img_h);
        } else {
          const JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
          full.resize(size_t{img_w} * img_h * 4);
          st = JxlDecoderSetImageOutBuffer(dec.get(), &format, full.data(),
                                           full.size());
        }
        if (st != JXL_DEC_SUCCESS) {
          *error = "JxlDecoderSetImageOutBuffer failed";
          return false;
        }
        break;
      }
      case JXL_DEC_FULL_IMAGE: {
        // First frame is complete; that is all we need.
        if (!direct) {
          const uint32_t n = factor * factor;
          for (uint32_t oy = 0; oy < out_height; ++oy) {
            uint8_t* dst = out + oy * out_stride;
            for (uint32_t ox = 0; ox < out_width; ++ox) {
              uint32_t acc[4] = {0, 0, 0, 0};
              for (uint32_t dy = 0; dy < factor; ++dy) {
                const uint8_t* src =
                    full.data() +
                    (size_t{oy * factor + dy} * img_w + size_t{ox} * factor) * 4;
                for (uint32_t dx = 0; dx < factor; ++dx, src += 4) {
                  acc[0] += src[0];
                  acc[1] += src[1];
                  acc[2] += src[2];
                  acc[3] += src[3];
                }
              }
              for (int c = 0; c < 4; ++c) {
                dst[ox * 4 + c] = static_cast<uint8_t>((acc[c] + n / 2) / n);
              }
            }
          }
        }
        if (info.alpha_bits > 0) {
          // Android bitmaps are premultiplied.
          for (uint32_t y = 0; y < out_height; ++y) {
            uint8_t* p = out + y * out_stride;
            for (uint32_t x = 0; x < out_width; ++x, p += 4) {
              const uint32_t a = p[3];
              if (a == 255) continue;
              p[0] = static_cast<uint8_t>((p[0] * a + 127) / 255);
              p[1] = static_cast<uint8_t>((p[1] * a + 127) / 255);
              p[2] = static_cast<uint8_t>((p[2] * a + 127) / 255);
            }
          }
        }
        return true;
      }
      case JXL_DEC_SUCCESS:
        *error = "JPEG XL file contains no image";
        return false;
      case JXL_DEC_NEED_MORE_INPUT:
        *error = "truncated JPEG XL file";
        return false;
      default:
        *error = "JPEG XL decoding failed";
        return false;
    }
  }
}

bool ReconstructJpeg(const uint8_t* data, size_t size, std::vector<uint8_t>* out,
                     std::string* error) {
  JxlDecoderPtr dec = JxlDecoderMake(nullptr);
  if (JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_JPEG_RECONSTRUCTION |
                                               JXL_DEC_FULL_IMAGE) !=
          JXL_DEC_SUCCESS ||
      JxlDecoderSetInput(dec.get(), data, size) != JXL_DEC_SUCCESS) {
    *error = "decoder setup failed";
    return false;
  }
  JxlDecoderCloseInput(dec.get());
  bool have_jpeg = false;
  out->clear();
  for (;;) {
    const JxlDecoderStatus status = JxlDecoderProcessInput(dec.get());
    if (status == JXL_DEC_JPEG_RECONSTRUCTION) {
      have_jpeg = true;
      out->resize(std::max<size_t>(size * 2, 1 << 20));
      JxlDecoderSetJPEGBuffer(dec.get(), out->data(), out->size());
    } else if (status == JXL_DEC_JPEG_NEED_MORE_OUTPUT) {
      const size_t used = out->size() - JxlDecoderReleaseJPEGBuffer(dec.get());
      out->resize(out->size() * 2);
      JxlDecoderSetJPEGBuffer(dec.get(), out->data() + used, out->size() - used);
    } else if (status == JXL_DEC_FULL_IMAGE) {
      if (!have_jpeg) {
        *error = "file does not contain JPEG reconstruction data";
        return false;
      }
      const size_t remaining = JxlDecoderReleaseJPEGBuffer(dec.get());
      out->resize(out->size() - remaining);
      return true;
    } else if (status == JXL_DEC_SUCCESS) {
      if (!have_jpeg) {
        *error = "file does not contain JPEG reconstruction data";
        return false;
      }
      return true;
    } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
      *error = "file does not contain JPEG reconstruction data";
      return false;
    } else {
      *error = "JPEG reconstruction failed";
      return false;
    }
  }
}

}  // namespace gxl
