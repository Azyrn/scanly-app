// The parts of pdfium's public C API that io.legere:pdfiumandroid doesn't expose: per-character
// font name, flags, weight, fill colour and text matrix, and rendering a page with its text
// removed. The functions are looked up in the libpdfium.so that the wrapper already ships and has
// loaded, so nothing is linked twice and no new library is added.
//
// pdfium is not thread-safe. Every entry point here must be called while holding the wrapper's
// global lock (PdfiumCoreU.lock), which PdfiumLayer.kt does; the wrapper has also initialised the
// library by then, so FPDF_InitLibrary is never called here.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char* kTag = "PdfiumLayer";

typedef void* Doc;
typedef void* Page;
typedef void* TextPage;
typedef void* PageObj;
typedef void* Bitmap;
typedef void* Font;

struct Matrix { float a, b, c, d, e, f; };
struct RectF { float left, top, right, bottom; };
struct ImageMetadata {
    unsigned int width, height;
    float horizontal_dpi, vertical_dpi;
    unsigned int bits_per_pixel;
    int colorspace;
    int marked_content_id;
};

constexpr int kObjText = 1;
constexpr int kObjPath = 2;
constexpr int kObjImage = 3;
constexpr int kObjForm = 5;
constexpr int kBitmapBgra = 4;
constexpr int kRenderAnnot = 0x01;
constexpr int kRenderReverseByteOrder = 0x10;
constexpr int kSegmentBezier = 1;
constexpr int kSegmentMove = 2;

// Ruling lines, as in scripts/fidelity/hostdump.py (RULE_*): keep the two in step.
constexpr float kRuleMaxThickPt = 3.0f;   // a filled rectangle thinner than this is a line; a thicker one is shading
constexpr float kRuleMinLengthPt = 4.0f;
constexpr float kRuleAxisPt = 0.5f;       // a line this far off horizontal or vertical still counts as one
constexpr size_t kRuleMaxPerPage = 4000;
constexpr int kFormDepth = 8;

typedef void* PathSegment;

// The path calls, bound apart from the rest: a pdfium without them reads pages as before, without rules.
struct PathApi {
    int (*CountSegments)(PageObj);
    PathSegment (*GetPathSegment)(PageObj, int);
    int (*SegmentGetPoint)(PathSegment, float*, float*);
    int (*SegmentGetType)(PathSegment);
    int (*SegmentGetClose)(PathSegment);
    int (*GetDrawMode)(PageObj, int*, int*);
    int (*GetStrokeWidth)(PageObj, float*);
    bool ready = false;
};

struct Api {
    Doc (*LoadDocument)(const char*, const char*);
    void (*CloseDocument)(Doc);
    int (*GetPageCount)(Doc);
    Page (*LoadPage)(Doc, int);
    void (*ClosePage)(Page);
    float (*GetPageWidthF)(Page);
    float (*GetPageHeightF)(Page);
    TextPage (*TextLoadPage)(Page);
    void (*TextClosePage)(TextPage);
    int (*TextCountChars)(TextPage);
    unsigned int (*TextGetUnicode)(TextPage, int);
    double (*TextGetFontSize)(TextPage, int);
    unsigned long (*TextGetFontInfo)(TextPage, int, void*, unsigned long, int*);
    int (*TextGetFontWeight)(TextPage, int);
    int (*TextGetFillColor)(TextPage, int, unsigned int*, unsigned int*, unsigned int*, unsigned int*);
    int (*TextGetLooseCharBox)(TextPage, int, RectF*);
    int (*TextGetCharBox)(TextPage, int, double*, double*, double*, double*);
    int (*TextGetCharOrigin)(TextPage, int, double*, double*);
    int (*TextGetMatrix)(TextPage, int, Matrix*);
    int (*TextIsGenerated)(TextPage, int);
    // Optional: removed from newer pdfium (FPDFTextObj_GetTextRenderMode on the char's object instead).
    int (*TextGetTextRenderMode)(TextPage, int) = nullptr;
    // Optional: newer pdfium only. A character's own text object: its font and render mode.
    PageObj (*TextGetTextObject)(TextPage, int) = nullptr;
    int (*TextObjGetTextRenderMode)(PageObj) = nullptr;
    // Optional: the marked-content id a page object is drawn under, a tagged PDF's link to its structure tree.
    int (*PageObjGetMarkedContentID)(PageObj) = nullptr;
    int (*PageCountObjects)(Page);
    PageObj (*PageGetObject)(Page, int);
    int (*PageObjGetType)(PageObj);
    int (*PageObjGetBounds)(PageObj, float*, float*, float*, float*);
    int (*PageRemoveObject)(Page, PageObj);
    void (*PageObjDestroy)(PageObj);
    int (*FormObjCountObjects)(PageObj);
    PageObj (*FormObjGetObject)(PageObj, unsigned long);
    Bitmap (*BitmapCreateEx)(int, int, int, void*, int);
    int (*BitmapFillRect)(Bitmap, int, int, int, int, unsigned long);
    void (*BitmapDestroy)(Bitmap);
    void (*RenderPageBitmap)(Bitmap, Page, int, int, int, int, int, int);
    int (*PageObjGetMatrix)(PageObj, Matrix*);
    Bitmap (*ImageObjGetBitmap)(PageObj);
    Bitmap (*ImageObjGetRenderedBitmap)(Doc, Page, PageObj);
    int (*PageObjHasTransparency)(PageObj);
    int (*ImageObjGetImageMetadata)(PageObj, Page, ImageMetadata*);
    int (*ImageObjGetImagePixelSize)(PageObj, unsigned int*, unsigned int*);
    int (*ImageObjGetImageFilterCount)(PageObj);
    unsigned long (*ImageObjGetImageFilter)(PageObj, int, void*, unsigned long);
    unsigned long (*ImageObjGetImageDataRaw)(PageObj, void*, unsigned long);
    int (*BitmapGetWidth)(Bitmap);
    int (*BitmapGetHeight)(Bitmap);
    int (*BitmapGetStride)(Bitmap);
    int (*BitmapGetFormat)(Bitmap);
    void* (*BitmapGetBuffer)(Bitmap);
    bool ready = false;
};

template <typename T>
bool bind(void* lib, const char* name, T& slot) {
    slot = reinterpret_cast<T>(dlsym(lib, name));
    if (!slot) __android_log_print(ANDROID_LOG_ERROR, kTag, "pdfium has no %s", name);
    return slot != nullptr;
}

Api& api() {
    static Api instance = [] {
        Api a{};
        void* lib = dlopen("libpdfium.so", RTLD_NOW | RTLD_NOLOAD);
        if (!lib) lib = dlopen("libpdfium.so", RTLD_NOW);
        if (!lib) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "libpdfium.so not loadable: %s", dlerror());
            return a;
        }
        bool ok = true;
        ok &= bind(lib, "FPDF_LoadDocument", a.LoadDocument);
        ok &= bind(lib, "FPDF_CloseDocument", a.CloseDocument);
        ok &= bind(lib, "FPDF_GetPageCount", a.GetPageCount);
        ok &= bind(lib, "FPDF_LoadPage", a.LoadPage);
        ok &= bind(lib, "FPDF_ClosePage", a.ClosePage);
        ok &= bind(lib, "FPDF_GetPageWidthF", a.GetPageWidthF);
        ok &= bind(lib, "FPDF_GetPageHeightF", a.GetPageHeightF);
        ok &= bind(lib, "FPDFText_LoadPage", a.TextLoadPage);
        ok &= bind(lib, "FPDFText_ClosePage", a.TextClosePage);
        ok &= bind(lib, "FPDFText_CountChars", a.TextCountChars);
        ok &= bind(lib, "FPDFText_GetUnicode", a.TextGetUnicode);
        ok &= bind(lib, "FPDFText_GetFontSize", a.TextGetFontSize);
        ok &= bind(lib, "FPDFText_GetFontInfo", a.TextGetFontInfo);
        ok &= bind(lib, "FPDFText_GetFontWeight", a.TextGetFontWeight);
        ok &= bind(lib, "FPDFText_GetFillColor", a.TextGetFillColor);
        ok &= bind(lib, "FPDFText_GetLooseCharBox", a.TextGetLooseCharBox);
        ok &= bind(lib, "FPDFText_GetCharBox", a.TextGetCharBox);
        ok &= bind(lib, "FPDFText_GetCharOrigin", a.TextGetCharOrigin);
        ok &= bind(lib, "FPDFText_GetMatrix", a.TextGetMatrix);
        ok &= bind(lib, "FPDFText_IsGenerated", a.TextIsGenerated);
        bind(lib, "FPDFText_GetTextRenderMode", a.TextGetTextRenderMode);
        bind(lib, "FPDFText_GetTextObject", a.TextGetTextObject);
        bind(lib, "FPDFTextObj_GetTextRenderMode", a.TextObjGetTextRenderMode);
        bind(lib, "FPDFPageObj_GetMarkedContentID", a.PageObjGetMarkedContentID);
        ok &= a.TextGetTextRenderMode != nullptr || (a.TextGetTextObject && a.TextObjGetTextRenderMode);
        ok &= bind(lib, "FPDFPage_CountObjects", a.PageCountObjects);
        ok &= bind(lib, "FPDFPage_GetObject", a.PageGetObject);
        ok &= bind(lib, "FPDFPageObj_GetType", a.PageObjGetType);
        ok &= bind(lib, "FPDFPageObj_GetBounds", a.PageObjGetBounds);
        ok &= bind(lib, "FPDFPage_RemoveObject", a.PageRemoveObject);
        ok &= bind(lib, "FPDFPageObj_Destroy", a.PageObjDestroy);
        ok &= bind(lib, "FPDFFormObj_CountObjects", a.FormObjCountObjects);
        ok &= bind(lib, "FPDFFormObj_GetObject", a.FormObjGetObject);
        ok &= bind(lib, "FPDFBitmap_CreateEx", a.BitmapCreateEx);
        ok &= bind(lib, "FPDFBitmap_FillRect", a.BitmapFillRect);
        ok &= bind(lib, "FPDFBitmap_Destroy", a.BitmapDestroy);
        ok &= bind(lib, "FPDF_RenderPageBitmap", a.RenderPageBitmap);
        ok &= bind(lib, "FPDFPageObj_GetMatrix", a.PageObjGetMatrix);
        ok &= bind(lib, "FPDFImageObj_GetBitmap", a.ImageObjGetBitmap);
        ok &= bind(lib, "FPDFImageObj_GetRenderedBitmap", a.ImageObjGetRenderedBitmap);
        ok &= bind(lib, "FPDFPageObj_HasTransparency", a.PageObjHasTransparency);
        ok &= bind(lib, "FPDFImageObj_GetImageMetadata", a.ImageObjGetImageMetadata);
        bind(lib, "FPDFImageObj_GetImagePixelSize", a.ImageObjGetImagePixelSize);
        ok &= bind(lib, "FPDFImageObj_GetImageFilterCount", a.ImageObjGetImageFilterCount);
        ok &= bind(lib, "FPDFImageObj_GetImageFilter", a.ImageObjGetImageFilter);
        ok &= bind(lib, "FPDFImageObj_GetImageDataRaw", a.ImageObjGetImageDataRaw);
        ok &= bind(lib, "FPDFBitmap_GetWidth", a.BitmapGetWidth);
        ok &= bind(lib, "FPDFBitmap_GetHeight", a.BitmapGetHeight);
        ok &= bind(lib, "FPDFBitmap_GetStride", a.BitmapGetStride);
        ok &= bind(lib, "FPDFBitmap_GetFormat", a.BitmapGetFormat);
        ok &= bind(lib, "FPDFBitmap_GetBuffer", a.BitmapGetBuffer);
        a.ready = ok;
        return a;
    }();
    return instance;
}

// The font calls, bound apart as well: without them no font program is read and text stays as pdfium gives it.
struct FontApi {
    Font (*TextObjGetFont)(PageObj);
    int (*GetFontData)(Font, uint8_t*, size_t, size_t*);
    int (*GetIsEmbedded)(Font);
    bool ready = false;
};

FontApi& fontApi() {
    static FontApi instance = [] {
        FontApi f{};
        void* lib = dlopen("libpdfium.so", RTLD_NOW | RTLD_NOLOAD);
        if (!lib) return f;
        bool ok = true;
        ok &= bind(lib, "FPDFTextObj_GetFont", f.TextObjGetFont);
        ok &= bind(lib, "FPDFFont_GetFontData", f.GetFontData);
        ok &= bind(lib, "FPDFFont_GetIsEmbedded", f.GetIsEmbedded);
        f.ready = ok;
        return f;
    }();
    return instance;
}

bool isArabic(unsigned int code) {
    return (code >= 0x600 && code <= 0x6FF) || (code >= 0x750 && code <= 0x77F) || (code >= 0x8A0 && code <= 0x8FF) ||
           (code >= 0xFB50 && code <= 0xFDFF) || (code >= 0xFE70 && code <= 0xFEFF);
}

PathApi& pathApi() {
    static PathApi instance = [] {
        PathApi p{};
        void* lib = dlopen("libpdfium.so", RTLD_NOW | RTLD_NOLOAD);
        if (!lib) return p;
        bool ok = true;
        ok &= bind(lib, "FPDFPath_CountSegments", p.CountSegments);
        ok &= bind(lib, "FPDFPath_GetPathSegment", p.GetPathSegment);
        ok &= bind(lib, "FPDFPathSegment_GetPoint", p.SegmentGetPoint);
        ok &= bind(lib, "FPDFPathSegment_GetType", p.SegmentGetType);
        ok &= bind(lib, "FPDFPathSegment_GetClose", p.SegmentGetClose);
        ok &= bind(lib, "FPDFPath_GetDrawMode", p.GetDrawMode);
        ok &= bind(lib, "FPDFPageObj_GetStrokeWidth", p.GetStrokeWidth);
        p.ready = ok;
        return p;
    }();
    return instance;
}

struct Rect { float left, bottom, right, top; };
struct Point { float x, y; };
struct TextRun { Rect box; Font font; };

/** The page's text objects with their fonts, those inside form XObjects included. */
void collectRuns(Api& a, FontApi& f, Page page, PageObj form, int depth, std::vector<TextRun>& out) {
    int count = form ? a.FormObjCountObjects(form) : a.PageCountObjects(page);
    for (int i = 0; i < count; ++i) {
        PageObj obj = form ? a.FormObjGetObject(form, i) : a.PageGetObject(page, i);
        int type = a.PageObjGetType(obj);
        Rect r{};
        if (type == kObjText && a.PageObjGetBounds(obj, &r.left, &r.bottom, &r.right, &r.top)) {
            Font font = f.TextObjGetFont(obj);
            if (font) out.push_back(TextRun{r, font});
        } else if (type == kObjForm && depth < kFormDepth) {
            collectRuns(a, f, page, obj, depth + 1, out);
        }
    }
}

/** Character [i]'s render mode, from the text page or, in newer pdfium, from its text object. */
int renderMode(Api& a, TextPage text, int i) {
    if (a.TextGetTextRenderMode) return a.TextGetTextRenderMode(text, i);
    PageObj obj = a.TextGetTextObject(text, i);
    return obj ? a.TextObjGetTextRenderMode(obj) : 0;
}

/**
 * The font of the smallest text object that holds the point, or null. pdfium has no call from a
 * character to its font; this stands in on a pdfium without FPDFText_GetTextObject, which gives a
 * character's own object.
 */
Font fontAt(const std::vector<TextRun>& runs, float x, float y) {
    constexpr float kSlack = 0.5f;
    Font best = nullptr;
    float bestArea = 0;
    for (const TextRun& run : runs) {
        const Rect& r = run.box;
        if (x < r.left - kSlack || x > r.right + kSlack || y < r.bottom - kSlack || y > r.top + kSlack) continue;
        float area = (r.right - r.left) * (r.top - r.bottom);
        if (!best || area < bestArea) {
            best = run.font;
            bestArea = area;
        }
    }
    return best;
}

/** The matrix that applies m, then n (PDF order: a point goes through the object's matrix first). */
Matrix concat(const Matrix& m, const Matrix& n) {
    return Matrix{m.a * n.a + m.b * n.c, m.a * n.b + m.b * n.d, m.c * n.a + m.d * n.c,
                  m.c * n.b + m.d * n.d, m.e * n.a + m.f * n.c + n.e, m.e * n.b + m.f * n.d + n.f};
}

Matrix objectMatrix(Api& a, PageObj obj) {
    Matrix m{1, 0, 0, 1, 0, 0};
    a.PageObjGetMatrix(obj, &m);
    return m;
}

/**
 * Appends the path's ruling lines to [out] as left, bottom, right, top in page space: every
 * horizontal or vertical stroke as the rectangle its line width covers, and every thin filled
 * rectangle (how Word draws cell borders).
 */
void pathRules(Api& a, PathApi& p, PageObj obj, const Matrix& outer, std::vector<float>& out) {
    int mode = 0, stroke = 0;
    if (!p.GetDrawMode(obj, &mode, &stroke) || (mode == 0 && !stroke)) return;
    Matrix m = concat(objectMatrix(a, obj), outer);
    float scale = std::sqrt(std::fabs(m.a * m.d - m.b * m.c));
    float width = 0;
    float half = (stroke && p.GetStrokeWidth(obj, &width)) ? width * scale / 2 : 0.0f;
    struct Subpath { std::vector<Point> points; bool curved = false; };
    std::vector<Subpath> subpaths;
    Subpath current;
    int count = p.CountSegments(obj);
    for (int i = 0; i < count; ++i) {
        PathSegment seg = p.GetPathSegment(obj, i);
        float x = 0, y = 0;
        p.SegmentGetPoint(seg, &x, &y);
        Point point{x * m.a + y * m.c + m.e, x * m.b + y * m.d + m.f};
        int kind = p.SegmentGetType(seg);
        if (kind == kSegmentMove) {
            if (!current.points.empty()) subpaths.push_back(current);
            current = Subpath{};
            current.points.push_back(point);
        } else {
            current.points.push_back(point);
            current.curved = current.curved || kind == kSegmentBezier;
        }
        if (p.SegmentGetClose(seg) && !current.points.empty()) current.points.push_back(current.points.front());
    }
    if (!current.points.empty()) subpaths.push_back(current);
    for (const Subpath& sub : subpaths) {
        if (sub.curved || sub.points.size() < 2) continue;
        if (stroke) {
            for (size_t i = 0; i + 1 < sub.points.size(); ++i) {
                const Point& from = sub.points[i];
                const Point& to = sub.points[i + 1];
                float dx = std::fabs(to.x - from.x), dy = std::fabs(to.y - from.y);
                if ((dx <= kRuleAxisPt) != (dy <= kRuleAxisPt) && std::max(dx, dy) >= kRuleMinLengthPt) {
                    out.insert(out.end(), {std::min(from.x, to.x) - half, std::min(from.y, to.y) - half,
                                           std::max(from.x, to.x) + half, std::max(from.y, to.y) + half});
                }
            }
        } else {
            float l = sub.points[0].x, r = l, b = sub.points[0].y, t = b;
            for (const Point& q : sub.points) {
                l = std::min(l, q.x);
                r = std::max(r, q.x);
                b = std::min(b, q.y);
                t = std::max(t, q.y);
            }
            bool boxed = true;
            for (const Point& q : sub.points) {
                boxed = boxed && std::min(std::fabs(q.x - l), std::fabs(q.x - r)) <= kRuleAxisPt &&
                        std::min(std::fabs(q.y - b), std::fabs(q.y - t)) <= kRuleAxisPt;
            }
            if (boxed && std::min(r - l, t - b) <= kRuleMaxThickPt && std::max(r - l, t - b) >= kRuleMinLengthPt) {
                out.insert(out.end(), {l, b, r, t});
            }
        }
    }
}

/** The rules of [count] objects (a page's, or a form's through [form]), forms entered up to kFormDepth deep. */
void collectRules(Api& a, PathApi& p, Page page, PageObj form, const Matrix& outer, int depth, std::vector<float>& out) {
    int count = form ? a.FormObjCountObjects(form) : a.PageCountObjects(page);
    for (int i = 0; i < count && out.size() < kRuleMaxPerPage * 4; ++i) {
        PageObj obj = form ? a.FormObjGetObject(form, i) : a.PageGetObject(page, i);
        int type = a.PageObjGetType(obj);
        if (type == kObjPath) {
            pathRules(a, p, obj, outer, out);
        } else if (type == kObjForm && depth < kFormDepth) {
            collectRules(a, p, page, obj, concat(objectMatrix(a, obj), outer), depth + 1, out);
        }
    }
}

bool formHasText(Api& a, PageObj form, int depth) {
    if (depth > 8) return false;
    int count = a.FormObjCountObjects(form);
    for (int i = 0; i < count; ++i) {
        PageObj child = a.FormObjGetObject(form, i);
        int type = a.PageObjGetType(child);
        if (type == kObjText) return true;
        if (type == kObjForm && formHasText(a, child, depth + 1)) return true;
    }
    return false;
}

bool inside(const std::vector<Rect>& rects, float x, float y) {
    for (const Rect& r : rects) {
        if (x >= r.left && x <= r.right && y >= r.bottom && y <= r.top) return true;
    }
    return false;
}

/** True when the image object draws with transparency (a soft mask), judged from its rendered pixels. */
bool hasSoftMask(Api& a, Doc doc, Page page, PageObj obj) {
    Bitmap rendered = a.ImageObjGetRenderedBitmap(doc, page, obj);
    if (!rendered) return false;
    bool masked = false;
    if (a.BitmapGetFormat(rendered) == kBitmapBgra) {
        int w = a.BitmapGetWidth(rendered), h = a.BitmapGetHeight(rendered), stride = a.BitmapGetStride(rendered);
        auto* src = static_cast<const uint8_t*>(a.BitmapGetBuffer(rendered));
        // The rendered edge is anti-aliased, so only the interior says anything about a mask.
        int inset = std::min(2, std::min(w, h) / 2);
        for (int y = inset; src && y < h - inset && !masked; ++y) {
            const uint8_t* row = src + static_cast<size_t>(y) * stride;
            for (int x = inset; x < w - inset; ++x) {
                if (row[x * 4 + 3] != 255) {
                    masked = true;
                    break;
                }
            }
        }
    }
    a.BitmapDestroy(rendered);
    return masked;
}

/**
 * A Java string from bytes that should be UTF-8 but may not be. pdfium hands font names over as
 * the raw bytes of the PDF name (often Shift-JIS or GBK in East Asian documents), and
 * NewStringUTF requires valid Modified UTF-8: CheckJNI aborts the process on anything else.
 * Invalid bytes become U+FFFD. Null, with OutOfMemoryError pending, when the string can't be made.
 */
jstring newStringLenient(JNIEnv* env, const std::string& bytes) {
    std::vector<jchar> out;
    out.reserve(bytes.size());
    const auto* p = reinterpret_cast<const unsigned char*>(bytes.data());
    const size_t n = bytes.size();
    size_t i = 0;
    while (i < n) {
        const unsigned char c = p[i];
        int extra = 0;
        uint32_t cp = 0;
        uint32_t min = 0;
        if (c < 0x80) {
            out.push_back(c);
            ++i;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            extra = 1; cp = c & 0x1F; min = 0x80;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2; cp = c & 0x0F; min = 0x800;
        } else if ((c & 0xF8) == 0xF0) {
            extra = 3; cp = c & 0x07; min = 0x10000;
        } else {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }
        bool valid = i + extra < n;
        for (int k = 1; valid && k <= extra; ++k) {
            const unsigned char next = p[i + k];
            if ((next & 0xC0) != 0x80) {
                valid = false;
            } else {
                cp = (cp << 6) | (next & 0x3F);
            }
        }
        if (!valid || cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(cp));
        }
        i += static_cast<size_t>(extra) + 1;
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

}  // namespace

// Layout of one character record in the float array nativeReadPage returns (see PdfiumLayer.kt).
constexpr int kFields = 24;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativeAvailable(JNIEnv*, jclass) {
    return api().ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativeOpen(
        JNIEnv* env, jclass, jstring path, jstring password) {
    Api& a = api();
    if (!a.ready) return 0;
    const char* p = env->GetStringUTFChars(path, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    Doc doc = a.LoadDocument(p, pw[0] ? pw : nullptr);
    env->ReleaseStringUTFChars(path, p);
    env->ReleaseStringUTFChars(password, pw);
    return reinterpret_cast<jlong>(doc);
}

extern "C" JNIEXPORT void JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativeClose(JNIEnv*, jclass, jlong doc) {
    if (doc) api().CloseDocument(reinterpret_cast<Doc>(doc));
}

/**
 * Returns {float[] header + records, String[] font names} for one page, or null when the page
 * can't be loaded. Header: width, height, char count, font count. Each record (kFields floats):
 * unicode, origin x, origin y, loose box left, bottom, right, top, font size, matrix a, b, c, d,
 * font index, font flags, weight, fill colour (ARGB bits), flags (1 generated, 2 inside a
 * form XObject, 4 invisible), render mode, ink box left, bottom, right, top (the glyph's drawn
 * extent: for joined scripts the loose box overlaps its neighbours), then the index of the character's
 * font program in the third element, or -1. Coordinates are PDF user space (origin bottom-left).
 * The third element is byte[][]: the embedded font programs of the fonts that draw Arabic text.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativeReadPage(
        JNIEnv* env, jclass, jlong docHandle, jint index) {
    Api& a = api();
    Doc doc = reinterpret_cast<Doc>(docHandle);
    if (!a.ready || !doc) return nullptr;
    Page page = a.LoadPage(doc, index);
    if (!page) return nullptr;
    TextPage text = a.TextLoadPage(page);
    if (!text) {
        a.ClosePage(page);
        return nullptr;
    }

    // Text that lives inside a form XObject can't be removed from the background render with this
    // pdfium build (no FPDFFormObj_RemoveObject), so those characters are flagged and stay part
    // of the picture: inside a form that holds text, and outside every top-level text object.
    std::vector<Rect> textRects, formRects;
    int objects = a.PageCountObjects(page);
    for (int i = 0; i < objects; ++i) {
        PageObj obj = a.PageGetObject(page, i);
        int type = a.PageObjGetType(obj);
        Rect r{};
        if (type == kObjText && a.PageObjGetBounds(obj, &r.left, &r.bottom, &r.right, &r.top)) {
            textRects.push_back(r);
        } else if (type == kObjForm && formHasText(a, obj, 0) &&
                   a.PageObjGetBounds(obj, &r.left, &r.bottom, &r.right, &r.top)) {
            formRects.push_back(r);
        }
    }

    int count = a.TextCountChars(text);
    if (count < 0) count = 0;
    std::vector<float> out(4 + static_cast<size_t>(count) * kFields);
    out[0] = a.GetPageWidthF(page);
    out[1] = a.GetPageHeightF(page);
    out[2] = static_cast<float>(count);
    std::vector<std::string> fonts;
    std::unordered_map<std::string, int> fontIds;
    std::vector<char> name(256);
    for (int i = 0; i < count; ++i) {
        float* rec = &out[4 + static_cast<size_t>(i) * kFields];
        rec[0] = static_cast<float>(a.TextGetUnicode(text, i));
        double ox = 0, oy = 0;
        a.TextGetCharOrigin(text, i, &ox, &oy);
        rec[1] = static_cast<float>(ox);
        rec[2] = static_cast<float>(oy);
        // The loose box spans the glyph's advance width and the font's ascent to descent: the
        // extent a word processor lays the character out in, unlike the tight ink box.
        RectF box{0, 0, 0, 0};
        a.TextGetLooseCharBox(text, i, &box);
        rec[3] = box.left;
        rec[4] = box.bottom;
        rec[5] = box.right;
        rec[6] = box.top;
        rec[7] = static_cast<float>(a.TextGetFontSize(text, i));
        Matrix m{1, 0, 0, 1, 0, 0};
        a.TextGetMatrix(text, i, &m);
        rec[8] = m.a;
        rec[9] = m.b;
        rec[10] = m.c;
        rec[11] = m.d;
        int flags = 0;
        unsigned long length = a.TextGetFontInfo(text, i, name.data(), name.size(), &flags);
        if (length > name.size()) {
            name.resize(length);
            length = a.TextGetFontInfo(text, i, name.data(), name.size(), &flags);
        }
        std::string font = length > 0 ? std::string(name.data()) : std::string();
        auto found = fontIds.find(font);
        int fontId;
        if (found == fontIds.end()) {
            fontId = static_cast<int>(fonts.size());
            fontIds.emplace(font, fontId);
            fonts.push_back(font);
        } else {
            fontId = found->second;
        }
        rec[12] = static_cast<float>(fontId);
        rec[13] = static_cast<float>(flags);
        rec[14] = static_cast<float>(a.TextGetFontWeight(text, i));
        unsigned int cr = 0, cg = 0, cb = 0, ca = 255;
        a.TextGetFillColor(text, i, &cr, &cg, &cb, &ca);
        uint32_t argb = (ca << 24) | (cr << 16) | (cg << 8) | cb;
        float colorBits;
        static_assert(sizeof(colorBits) == sizeof(argb), "float must be 32-bit");
        std::memcpy(&colorBits, &argb, sizeof(argb));
        rec[15] = colorBits;
        int mode = renderMode(a, text, i);
        int charFlags = a.TextIsGenerated(text, i) == 1 ? 1 : 0;
        float cx = (rec[3] + rec[5]) / 2, cy = (rec[4] + rec[6]) / 2;
        if (!formRects.empty() && inside(formRects, cx, cy) && !inside(textRects, cx, cy)) charFlags |= 2;
        if (mode == 3) charFlags |= 4;
        rec[16] = static_cast<float>(charFlags);
        rec[17] = static_cast<float>(mode);
        double inkLeft = 0, inkRight = 0, inkBottom = 0, inkTop = 0;
        if (a.TextGetCharBox(text, i, &inkLeft, &inkRight, &inkBottom, &inkTop) != 1) {
            inkLeft = rec[3];
            inkRight = rec[5];
            inkBottom = rec[4];
            inkTop = rec[6];
        }
        rec[18] = static_cast<float>(inkLeft);
        rec[19] = static_cast<float>(inkBottom);
        rec[20] = static_cast<float>(inkRight);
        rec[21] = static_cast<float>(inkTop);
        rec[22] = -1.0f;
        // 23: the marked-content id of the character's text object, -1 when it has none or pdfium cannot say.
        PageObj drawn = (a.TextGetTextObject && a.PageObjGetMarkedContentID) ? a.TextGetTextObject(text, i) : nullptr;
        rec[23] = drawn ? static_cast<float>(a.PageObjGetMarkedContentID(drawn)) : -1.0f;
    }
    out[3] = static_cast<float>(fonts.size());

    // Font programs: a character's font is its text object's; only fonts that draw Arabic are read.
    std::vector<std::vector<uint8_t>> programs;
    FontApi& f = fontApi();
    if (f.ready && count > 0) {
        std::vector<TextRun> runs;
        collectRuns(a, f, page, nullptr, 0, runs);
        std::vector<Font> charFonts(static_cast<size_t>(count), nullptr);
        std::unordered_map<Font, int> programIds;
        for (int i = 0; i < count; ++i) {
            float* rec = &out[4 + static_cast<size_t>(i) * kFields];
            if (static_cast<int>(rec[16]) & 1) continue;
            PageObj own = a.TextGetTextObject ? a.TextGetTextObject(text, i) : nullptr;
            Font font = own ? f.TextObjGetFont(own) : fontAt(runs, rec[1], rec[2]);
            charFonts[i] = font;
            if (!font || programIds.count(font) || !isArabic(static_cast<unsigned int>(rec[0]))) continue;
            programIds[font] = -1;
            size_t length = 0;
            if (!f.GetIsEmbedded(font) || !f.GetFontData(font, nullptr, 0, &length) || length == 0) continue;
            std::vector<uint8_t> data(length);
            if (!f.GetFontData(font, data.data(), data.size(), &length)) continue;
            programIds[font] = static_cast<int>(programs.size());
            programs.push_back(std::move(data));
        }
        for (int i = 0; i < count; ++i) {
            auto found = charFonts[i] ? programIds.find(charFonts[i]) : programIds.end();
            if (found != programIds.end()) out[4 + static_cast<size_t>(i) * kFields + 22] = static_cast<float>(found->second);
        }
    }
    a.TextClosePage(text);
    a.ClosePage(page);

    // Every allocation below can fail on a huge page (about 100 bytes a character) or a large font
    // program. A failed New* returns null with OutOfMemoryError pending, and no further JNI call
    // is allowed then (passing the null on aborts the VM): return at once and let Kotlin see the
    // OutOfMemoryError.
    jclass objectClass = env->FindClass("java/lang/Object");
    if (!objectClass) return nullptr;
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    if (!result) return nullptr;
    jfloatArray floats = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (!floats) return nullptr;
    env->SetFloatArrayRegion(floats, 0, static_cast<jsize>(out.size()), out.data());
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray names = env->NewObjectArray(static_cast<jsize>(fonts.size()), stringClass, nullptr);
    if (!names) return nullptr;
    for (size_t i = 0; i < fonts.size(); ++i) {
        jstring s = newStringLenient(env, fonts[i]);
        if (!s) return nullptr;
        env->SetObjectArrayElement(names, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    env->SetObjectArrayElement(result, 0, floats);
    env->SetObjectArrayElement(result, 1, names);
    jclass bytesClass = env->FindClass("[B");
    if (!bytesClass) return nullptr;
    jobjectArray fontData = env->NewObjectArray(static_cast<jsize>(programs.size()), bytesClass, nullptr);
    if (!fontData) return nullptr;
    for (size_t i = 0; i < programs.size(); ++i) {
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(programs[i].size()));
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(programs[i].size()), reinterpret_cast<const jbyte*>(programs[i].data()));
        env->SetObjectArrayElement(fontData, static_cast<jsize>(i), bytes);
        env->DeleteLocalRef(bytes);
    }
    env->SetObjectArrayElement(result, 2, fontData);
    return result;
}

/**
 * The page's ruling lines, four floats each: left, bottom, right, top in PDF user space. Empty
 * when the page has none or this pdfium has no path calls; null when the page can't be loaded.
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativePageRules(
        JNIEnv* env, jclass, jlong docHandle, jint index) {
    Api& a = api();
    PathApi& p = pathApi();
    Doc doc = reinterpret_cast<Doc>(docHandle);
    if (!a.ready || !doc) return nullptr;
    std::vector<float> out;
    if (p.ready) {
        Page page = a.LoadPage(doc, index);
        if (!page) return nullptr;
        collectRules(a, p, page, nullptr, Matrix{1, 0, 0, 1, 0, 0}, 0, out);
        a.ClosePage(page);
        if (out.size() > kRuleMaxPerPage * 4) out.resize(kRuleMaxPerPage * 4);
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/**
 * Renders page [index] into [bitmap] (ARGB_8888, any size; the page is scaled to fill it) on
 * white, after removing its top-level text objects and the image objects whose top-level indices
 * are in [removeImages] (those were extracted as pictures of their own). The removal changes the
 * in-memory page of this document handle only, so read the page's text before calling this;
 * nothing is written back.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativeRenderBackground(
        JNIEnv* env, jclass, jlong docHandle, jint index, jobject bitmap, jintArray removeImages) {
    Api& a = api();
    Doc doc = reinterpret_cast<Doc>(docHandle);
    if (!a.ready || !doc) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    Page page = a.LoadPage(doc, index);
    if (!page) return JNI_FALSE;
    std::vector<jint> images;
    if (removeImages) {
        jsize n = env->GetArrayLength(removeImages);
        images.resize(static_cast<size_t>(n));
        env->GetIntArrayRegion(removeImages, 0, n, images.data());
    }
    for (int i = a.PageCountObjects(page) - 1; i >= 0; --i) {
        PageObj obj = a.PageGetObject(page, i);
        int type = a.PageObjGetType(obj);
        bool listed = type == kObjImage && std::find(images.begin(), images.end(), i) != images.end();
        if (type == kObjText || listed) {
            if (a.PageRemoveObject(page, obj)) a.PageObjDestroy(obj);
        }
    }
    // No FPDFPage_GenerateContent: pdfium renders from the page's object list, so the removal
    // already shows, and regenerating the content stream cost ~0.9 s a page on the phone.

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        a.ClosePage(page);
        return JNI_FALSE;
    }
    Bitmap target = a.BitmapCreateEx(static_cast<int>(info.width), static_cast<int>(info.height),
                                     kBitmapBgra, pixels, static_cast<int>(info.stride));
    jboolean ok = JNI_FALSE;
    if (target) {
        a.BitmapFillRect(target, 0, 0, static_cast<int>(info.width), static_cast<int>(info.height), 0xFFFFFFFF);
        // Reverse byte order makes pdfium write RGBA, which is ARGB_8888's layout in memory.
        a.RenderPageBitmap(target, page, 0, 0, static_cast<int>(info.width), static_cast<int>(info.height), 0,
                           kRenderAnnot | kRenderReverseByteOrder);
        a.BitmapDestroy(target);
        ok = JNI_TRUE;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    a.ClosePage(page);
    return ok;
}

/**
 * The page's top-level images that can become pictures of their own (upright and opaque): for
 * each, {object index, left, bottom, right, top, jpeg} where jpeg is 1 when the object's own bytes
 * are a plain RGB or grey JPEG that can be copied as is, else 0. Bounds are PDF user space.
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativePageImages(
        JNIEnv* env, jclass, jlong docHandle, jint index) {
    Api& a = api();
    Doc doc = reinterpret_cast<Doc>(docHandle);
    if (!a.ready || !doc) return nullptr;
    Page page = a.LoadPage(doc, index);
    if (!page) return nullptr;
    std::vector<float> out;
    int objects = a.PageCountObjects(page);
    std::vector<char> filter(64);
    for (int i = 0; i < objects; ++i) {
        PageObj obj = a.PageGetObject(page, i);
        if (a.PageObjGetType(obj) != kObjImage) continue;
        Rect r{};
        if (!a.PageObjGetBounds(obj, &r.left, &r.bottom, &r.right, &r.top)) continue;
        // Only an upright, opaque image is copied out as a picture; a rotated, flipped or
        // transparent one stays in the background render, which draws it exactly.
        Matrix m{};
        if (!a.PageObjGetMatrix(obj, &m) || std::fabs(m.b) > 1e-4f || std::fabs(m.c) > 1e-4f || m.a <= 0 || m.d <= 0 ||
            a.PageObjHasTransparency(obj)) {
            continue;
        }
        // FPDFPageObj_HasTransparency misses a soft mask on the image itself, and GetBitmap then
        // returns the colour data without it (a chart on a transparent ground came out black): the
        // rendered bitmap shows any transparency, and such an image stays in the background render.
        if (hasSoftMask(a, doc, page, obj)) continue;
        bool jpeg = false;
        ImageMetadata meta{};
        if (a.ImageObjGetImageFilterCount(obj) == 1 && a.ImageObjGetImageMetadata(obj, page, &meta)) {
            unsigned long n = a.ImageObjGetImageFilter(obj, 0, filter.data(), filter.size());
            // Colour spaces: 1 DeviceGray, 2 DeviceRGB. An image with an alpha mask has 32 bpp here.
            jpeg = n > 0 && std::string(filter.data()) == "DCTDecode" &&
                   (meta.colorspace == 1 || meta.colorspace == 2) && meta.bits_per_pixel <= 24;
        }
        out.insert(out.end(), {static_cast<float>(i), r.left, r.bottom, r.right, r.top, jpeg ? 1.0f : 0.0f});
    }
    a.ClosePage(page);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/** The raw (still encoded) bytes of image object [objectIndex] on page [index], or null. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativeImageRaw(
        JNIEnv* env, jclass, jlong docHandle, jint index, jint objectIndex) {
    Api& a = api();
    Doc doc = reinterpret_cast<Doc>(docHandle);
    if (!a.ready || !doc) return nullptr;
    Page page = a.LoadPage(doc, index);
    if (!page) return nullptr;
    jbyteArray result = nullptr;
    PageObj obj = a.PageGetObject(page, objectIndex);
    if (obj && a.PageObjGetType(obj) == kObjImage) {
        unsigned long size = a.ImageObjGetImageDataRaw(obj, nullptr, 0);
        if (size > 0 && size < (1UL << 30)) {
            std::vector<jbyte> data(size);
            a.ImageObjGetImageDataRaw(obj, data.data(), size);
            result = env->NewByteArray(static_cast<jsize>(size));
            // Null with OutOfMemoryError pending: hand that to Kotlin rather than call JNI with it.
            if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), data.data());
        }
    }
    a.ClosePage(page);
    return result;
}

/**
 * Image object [objectIndex]'s decoded pixels at their own resolution, as a new ARGB_8888 Bitmap,
 * or null. Images over [maxPixels] are refused.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_PdfiumLayer_nativeImageBitmap(
        JNIEnv* env, jclass, jlong docHandle, jint index, jint objectIndex, jint maxPixels) {
    Api& a = api();
    Doc doc = reinterpret_cast<Doc>(docHandle);
    if (!a.ready || !doc) return nullptr;
    Page page = a.LoadPage(doc, index);
    if (!page) return nullptr;
    jobject result = nullptr;
    PageObj obj = a.PageGetObject(page, objectIndex);
    // The image's own pixels at its own resolution (the rendered variant rasterises at one pixel
    // per point, far below a scan's resolution). Only upright opaque images get here.
    bool image = obj && a.PageObjGetType(obj) == kObjImage;
    // Size first, from the image dictionary: decoding a 6000 x 6000 scan only to refuse it costs
    // about 140 MB of native memory, and a crafted huge image can exhaust it.
    if (image) {
        unsigned int dictWidth = 0, dictHeight = 0;
        bool sized = false;
        if (a.ImageObjGetImagePixelSize) {
            sized = a.ImageObjGetImagePixelSize(obj, &dictWidth, &dictHeight) != 0;
        } else {
            ImageMetadata meta{};
            // Without the page only the dictionary's size is read, not the colour data.
            sized = a.ImageObjGetImageMetadata(obj, nullptr, &meta) != 0;
            dictWidth = meta.width;
            dictHeight = meta.height;
        }
        if (sized && static_cast<long long>(dictWidth) * dictHeight > maxPixels) image = false;
    }
    Bitmap rendered = image ? a.ImageObjGetBitmap(obj) : nullptr;
    if (rendered) {
        int w = a.BitmapGetWidth(rendered), h = a.BitmapGetHeight(rendered);
        int stride = a.BitmapGetStride(rendered), format = a.BitmapGetFormat(rendered);
        auto* src = static_cast<const uint8_t*>(a.BitmapGetBuffer(rendered));
        if (w > 0 && h > 0 && static_cast<long long>(w) * h <= maxPixels && src) {
            jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
            jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
            jfieldID argb = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
            jobject config = env->GetStaticObjectField(configClass, argb);
            jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap",
                                                      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
            jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, w, h, config);
            void* pixels = nullptr;
            AndroidBitmapInfo info;
            if (bitmap && !env->ExceptionCheck() && AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
                AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
                // pdfium formats: 1 grey, 2 BGR, 3 BGRx, 4 BGRA. Android wants RGBA bytes; Bitmap
                // pixels are premultiplied, so colour is scaled by alpha on the way in.
                for (int y = 0; y < h; ++y) {
                    const uint8_t* row = src + static_cast<size_t>(y) * stride;
                    auto* dst = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
                    for (int x = 0; x < w; ++x) {
                        uint8_t r, g, b, alpha = 255;
                        if (format == 1) {
                            r = g = b = row[x];
                        } else {
                            int bpp = format == 2 ? 3 : 4;
                            const uint8_t* px = row + x * bpp;
                            b = px[0]; g = px[1]; r = px[2];
                            if (format == 4) alpha = px[3];
                        }
                        dst[x * 4 + 0] = static_cast<uint8_t>(r * alpha / 255);
                        dst[x * 4 + 1] = static_cast<uint8_t>(g * alpha / 255);
                        dst[x * 4 + 2] = static_cast<uint8_t>(b * alpha / 255);
                        dst[x * 4 + 3] = alpha;
                    }
                }
                AndroidBitmap_unlockPixels(env, bitmap);
                result = bitmap;
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                result = nullptr;
            }
        }
        a.BitmapDestroy(rendered);
    }
    a.ClosePage(page);
    return result;
}
