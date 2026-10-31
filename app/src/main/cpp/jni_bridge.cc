// JNI glue between io.github.grailnight.gxlcam.codec.JxlNative and libjxl.
#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "jxl_codec.h"
#include "yuv.h"

namespace {

struct RgbFrame {
  uint32_t width = 0;
  uint32_t height = 0;
  std::vector<uint8_t> rgb;
};

void Throw(JNIEnv* env, const char* cls, const std::string& msg) {
  jclass c = env->FindClass(cls);
  if (c != nullptr) env->ThrowNew(c, msg.c_str());
}

void ThrowIo(JNIEnv* env, const std::string& msg) {
  Throw(env, "java/io/IOException", msg);
}

void ThrowOom(JNIEnv* env) {
  Throw(env, "java/lang/OutOfMemoryError", "native allocation failed");
}

// RAII view of a Java byte[].
class ByteArrayView {
 public:
  ByteArrayView(JNIEnv* env, jbyteArray array) : env_(env), array_(array) {
    if (array != nullptr) {
      size_ = static_cast<size_t>(env->GetArrayLength(array));
      data_ = env->GetByteArrayElements(array, nullptr);
    }
  }
  ~ByteArrayView() {
    if (data_ != nullptr) env_->ReleaseByteArrayElements(array_, data_, JNI_ABORT);
  }
  const uint8_t* data() const { return reinterpret_cast<const uint8_t*>(data_); }
  size_t size() const { return size_; }
  bool ok() const { return data_ != nullptr; }

 private:
  JNIEnv* env_;
  jbyteArray array_;
  jbyte* data_ = nullptr;
  size_t size_ = 0;
};

// RAII lock of an Android bitmap's pixels (must be RGBA_8888).
class BitmapLock {
 public:
  BitmapLock(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
    if (AndroidBitmap_getInfo(env, bitmap, &info_) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info_.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
      return;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels_) != ANDROID_BITMAP_RESULT_SUCCESS) {
      pixels_ = nullptr;
    }
  }
  ~BitmapLock() {
    if (pixels_ != nullptr) AndroidBitmap_unlockPixels(env_, bitmap_);
  }
  uint8_t* pixels() const { return static_cast<uint8_t*>(pixels_); }
  const AndroidBitmapInfo& info() const { return info_; }
  bool ok() const { return pixels_ != nullptr; }

 private:
  JNIEnv* env_;
  jobject bitmap_;
  AndroidBitmapInfo info_{};
  void* pixels_ = nullptr;
};

jbyteArray ToJava(JNIEnv* env, const std::vector<uint8_t>& bytes) {
  jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (result == nullptr) return nullptr;  // OutOfMemoryError already pending
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                          reinterpret_cast<const jbyte*>(bytes.data()));
  return result;
}

RgbFrame* FromHandle(JNIEnv* env, jlong handle) {
  auto* frame = reinterpret_cast<RgbFrame*>(handle);
  if (frame == nullptr) Throw(env, "java/lang/IllegalStateException", "frame released");
  return frame;
}

}  // namespace

#define GXL_JNI(ret, name) \
  extern "C" JNIEXPORT ret JNICALL Java_io_github_grailnight_gxlcam_codec_JxlNative_##name

GXL_JNI(jstring, libjxlVersion)(JNIEnv* env, jobject) {
  return env->NewStringUTF(gxl::LibjxlVersion().c_str());
}

GXL_JNI(jbyteArray, transcodeJpeg)(JNIEnv* env, jobject, jbyteArray jpeg, jint effort) {
  ByteArrayView in(env, jpeg);
  if (!in.ok()) return nullptr;
  std::vector<uint8_t> out;
  std::string error;
  try {
    if (!gxl::TranscodeJpeg(in.data(), in.size(), effort, 0, &out, &error)) {
      ThrowIo(env, "JPEG -> JXL: " + error);
      return nullptr;
    }
  } catch (const std::bad_alloc&) {
    ThrowOom(env);
    return nullptr;
  }
  return ToJava(env, out);
}

GXL_JNI(jlong, yuvToRgbFrame)(JNIEnv* env, jobject, jobject y_buf, jint y_row_stride,
                              jint y_pixel_stride, jobject u_buf, jobject v_buf,
                              jint uv_row_stride, jint uv_pixel_stride, jint width,
                              jint height) {
  auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y_buf));
  auto* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u_buf));
  auto* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v_buf));
  if (y == nullptr || u == nullptr || v == nullptr || width <= 0 || height <= 0) {
    Throw(env, "java/lang/IllegalArgumentException", "planes must be direct buffers");
    return 0;
  }
  // Make sure the strides cannot make us read outside the planes.
  const jlong y_cap = env->GetDirectBufferCapacity(y_buf);
  const jlong u_cap = env->GetDirectBufferCapacity(u_buf);
  const jlong v_cap = env->GetDirectBufferCapacity(v_buf);
  const jlong cw = (width + 1) / 2, ch = (height + 1) / 2;
  const jlong y_need = jlong{y_row_stride} * (height - 1) + jlong{y_pixel_stride} * (width - 1) + 1;
  const jlong uv_need = jlong{uv_row_stride} * (ch - 1) + jlong{uv_pixel_stride} * (cw - 1) + 1;
  if (y_row_stride <= 0 || y_pixel_stride <= 0 || uv_row_stride <= 0 || uv_pixel_stride <= 0 ||
      y_cap < y_need || u_cap < uv_need || v_cap < uv_need) {
    Throw(env, "java/lang/IllegalArgumentException", "YUV plane layout does not fit buffers");
    return 0;
  }
  std::unique_ptr<RgbFrame> frame;
  try {
    frame = std::make_unique<RgbFrame>();
    frame->width = static_cast<uint32_t>(width);
    frame->height = static_cast<uint32_t>(height);
    frame->rgb.resize(size_t{frame->width} * frame->height * 3);
  } catch (const std::bad_alloc&) {
    ThrowOom(env);
    return 0;
  }
  gxl::Yuv420ToRgb8(y, y_row_stride, y_pixel_stride, u, v, uv_row_stride, uv_pixel_stride,
                    frame->width, frame->height, frame->rgb.data(), 0);
  return reinterpret_cast<jlong>(frame.release());
}

GXL_JNI(jlong, bitmapToRgbFrame)(JNIEnv* env, jobject, jobject bitmap) {
  BitmapLock lock(env, bitmap);
  if (!lock.ok()) {
    Throw(env, "java/lang/IllegalArgumentException", "bitmap must be ARGB_8888");
    return 0;
  }
  const AndroidBitmapInfo& info = lock.info();
  std::unique_ptr<RgbFrame> frame;
  try {
    frame = std::make_unique<RgbFrame>();
    frame->width = info.width;
    frame->height = info.height;
    frame->rgb.resize(size_t{info.width} * info.height * 3);
  } catch (const std::bad_alloc&) {
    ThrowOom(env);
    return 0;
  }
  uint8_t* dst = frame->rgb.data();
  for (uint32_t y = 0; y < info.height; ++y) {
    const uint8_t* src = lock.pixels() + size_t{y} * info.stride;
    for (uint32_t x = 0; x < info.width; ++x, src += 4, dst += 3) {
      dst[0] = src[0];
      dst[1] = src[1];
      dst[2] = src[2];
    }
  }
  return reinterpret_cast<jlong>(frame.release());
}

GXL_JNI(void, frameThumbnail)(JNIEnv* env, jobject, jlong handle, jobject bitmap) {
  RgbFrame* frame = FromHandle(env, handle);
  if (frame == nullptr) return;
  BitmapLock lock(env, bitmap);
  if (!lock.ok()) {
    Throw(env, "java/lang/IllegalArgumentException", "bitmap must be ARGB_8888");
    return;
  }
  const AndroidBitmapInfo& info = lock.info();
  if (info.width == 0 || info.height == 0) return;
  if (info.width > frame->width || info.height > frame->height) {
    Throw(env, "java/lang/IllegalArgumentException", "thumbnail size mismatch");
    return;
  }
  const uint32_t factor = std::min(frame->width / info.width, frame->height / info.height);
  gxl::DownscaleRgbToRgba(frame->rgb.data(), frame->width, frame->height, factor,
                          lock.pixels(), info.stride, info.width, info.height);
}

GXL_JNI(jbyteArray, encodeFrame)(JNIEnv* env, jobject, jlong handle, jfloat distance,
                                 jint effort, jint orientation, jint color_space,
                                 jbyteArray exif) {
  RgbFrame* frame = FromHandle(env, handle);
  if (frame == nullptr) return nullptr;
  ByteArrayView exif_view(env, exif);
  gxl::EncodeOptions options;
  options.distance = distance;
  options.effort = effort;
  options.orientation = orientation;
  options.color = color_space == 1 ? gxl::ColorSpace::kDisplayP3 : gxl::ColorSpace::kSRGB;
  if (exif_view.ok()) {
    options.exif = exif_view.data();
    options.exif_size = exif_view.size();
  }
  std::vector<uint8_t> out;
  std::string error;
  try {
    if (!gxl::EncodeRgb8(frame->rgb.data(), frame->width, frame->height, options, &out,
                         &error)) {
      ThrowIo(env, "JXL encode: " + error);
      return nullptr;
    }
  } catch (const std::bad_alloc&) {
    ThrowOom(env);
    return nullptr;
  }
  return ToJava(env, out);
}

GXL_JNI(void, releaseFrame)(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<RgbFrame*>(handle);
}

GXL_JNI(jintArray, probe)(JNIEnv* env, jobject, jbyteArray data) {
  ByteArrayView in(env, data);
  if (!in.ok()) return nullptr;
  gxl::ImageInfo info;
  std::string error;
  if (!gxl::Probe(in.data(), in.size(), &info, &error)) {
    ThrowIo(env, error);
    return nullptr;
  }
  const jint values[7] = {
      static_cast<jint>(info.width),          static_cast<jint>(info.height),
      static_cast<jint>(info.orientation),    info.has_alpha ? 1 : 0,
      info.is_lossy ? 1 : 0,                  info.has_jpeg_reconstruction ? 1 : 0,
      static_cast<jint>(info.bits_per_sample)};
  jintArray result = env->NewIntArray(7);
  if (result != nullptr) env->SetIntArrayRegion(result, 0, 7, values);
  return result;
}

GXL_JNI(jint, decodeInto)(JNIEnv* env, jobject, jbyteArray data, jobject bitmap,
                          jint color_space) {
  ByteArrayView in(env, data);
  if (!in.ok()) return -1;
  BitmapLock lock(env, bitmap);
  if (!lock.ok()) {
    Throw(env, "java/lang/IllegalArgumentException", "bitmap must be ARGB_8888");
    return -1;
  }
  const AndroidBitmapInfo& info = lock.info();
  gxl::ColorSpace actual = gxl::ColorSpace::kSRGB;
  std::string error;
  try {
    if (!gxl::DecodeRgba8(in.data(), in.size(),
                          color_space == 1 ? gxl::ColorSpace::kDisplayP3 : gxl::ColorSpace::kSRGB,
                          lock.pixels(), info.stride, info.width, info.height, 0, &actual,
                          &error)) {
      ThrowIo(env, "JXL decode: " + error);
      return -1;
    }
  } catch (const std::bad_alloc&) {
    ThrowOom(env);
    return -1;
  }
  return static_cast<jint>(actual);
}

GXL_JNI(jbyteArray, reconstructJpeg)(JNIEnv* env, jobject, jbyteArray data) {
  ByteArrayView in(env, data);
  if (!in.ok()) return nullptr;
  std::vector<uint8_t> out;
  std::string error;
  try {
    if (!gxl::ReconstructJpeg(in.data(), in.size(), &out, &error)) {
      ThrowIo(env, error);
      return nullptr;
    }
  } catch (const std::bad_alloc&) {
    ThrowOom(env);
    return nullptr;
  }
  return ToJava(env, out);
}
