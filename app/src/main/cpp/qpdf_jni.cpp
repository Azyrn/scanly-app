// JNI bridge to libqpdf.
//
// Almost everything routes through QPDFJob, which parses the same argv the qpdf CLI does. That
// keeps this file tiny and means new capabilities are a Kotlin argv change rather than new
// native code. Only metadata stripping needs the raw object API, because no CLI flag expresses
// it.

#include <jni.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <unordered_set>
#include <string>
#include <vector>

#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFJob.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <qpdf/QPDFUsage.hh>
#include <qpdf/QPDFWriter.hh>

namespace {

constexpr jint kOk = 0;
constexpr jint kErrUsage = -1;
constexpr jint kErrFailed = -2;
constexpr jint kErrPassword = -3;

// Reported verbatim to the user when an operation fails, so it must stay the message qpdf
// produced rather than a generic string.
thread_local std::string g_last_error;

std::string jstringToUtf8(JNIEnv* env, jstring value)
{
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

bool looksLikePasswordFailure(const std::string& message)
{
    return message.find("password") != std::string::npos ||
        message.find("invalid password") != std::string::npos;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_Qpdf_nativeRun(
    JNIEnv* env, jclass, jobjectArray argv)
{
    g_last_error.clear();

    const jsize count = env->GetArrayLength(argv);
    std::vector<std::string> owned;
    owned.reserve(static_cast<size_t>(count) + 1);
    owned.emplace_back("qpdf");
    for (jsize i = 0; i < count; ++i) {
        auto element = static_cast<jstring>(env->GetObjectArrayElement(argv, i));
        owned.push_back(jstringToUtf8(env, element));
        env->DeleteLocalRef(element);
    }

    std::vector<char const*> args;
    args.reserve(owned.size() + 1);
    for (const auto& value : owned) {
        args.push_back(value.c_str());
    }
    args.push_back(nullptr);

    // qpdf writes progress and warnings to these; capturing them keeps it off Android's stdout,
    // which is discarded anyway, and gives us the text to surface on failure.
    std::ostringstream out;
    std::ostringstream err;

    try {
        QPDFJob job;
        job.setOutputStreams(&out, &err);
        job.initializeFromArgv(args.data());
        job.run();
        // qpdf_exit_warning means the output was written and only the *input* had recoverable
        // damage, which is common enough in the wild that treating it as failure would refuse to
        // save a large share of real documents.
        const int exitCode = job.getExitCode();
        if (exitCode != qpdf_exit_success && exitCode != qpdf_exit_warning) {
            g_last_error = err.str();
            return kErrFailed;
        }
        return kOk;
    } catch (const QPDFUsage& e) {
        g_last_error = e.what();
        return kErrUsage;
    } catch (const std::exception& e) {
        g_last_error = e.what();
        if (g_last_error.empty()) {
            g_last_error = err.str();
        }
        return looksLikePasswordFailure(g_last_error) ? kErrPassword : kErrFailed;
    }
}

/**
 * Clears document identity that survives a normal rewrite: the Info dictionary, document-level
 * XMP, and the per-page /Metadata and /PieceInfo entries that authoring tools leave behind.
 * Deliberately not implemented as `--empty --pages`, which would also discard outlines, form
 * fields and attachments the user never asked to lose.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_Qpdf_nativeStripMetadata(
    JNIEnv* env, jclass, jstring inPath, jstring outPath, jstring password, jboolean stripXmp)
{
    g_last_error.clear();

    const std::string in = jstringToUtf8(env, inPath);
    const std::string out = jstringToUtf8(env, outPath);
    const std::string pw = jstringToUtf8(env, password);

    try {
        QPDF pdf;
        pdf.processFile(in.c_str(), pw.empty() ? nullptr : pw.c_str());

        pdf.getTrailer().removeKey("/Info");

        QPDFObjectHandle root = pdf.getRoot();
        if (stripXmp == JNI_TRUE) {
            root.removeKey("/Metadata");
        }
        root.removeKey("/PieceInfo");

        QPDFPageDocumentHelper pages(pdf);
        for (auto& page : pages.getAllPages()) {
            QPDFObjectHandle handle = page.getObjectHandle();
            handle.removeKey("/PieceInfo");
            if (stripXmp == JNI_TRUE) {
                handle.removeKey("/Metadata");
            }
        }

        QPDFWriter writer(pdf, out.c_str());
        // A random file id is itself a correlatable identifier; a deterministic one derived from
        // content is not.
        writer.setDeterministicID(true);
        writer.setObjectStreamMode(qpdf_o_generate);
        writer.write();
        return kOk;
    } catch (const std::exception& e) {
        g_last_error = e.what();
        return looksLikePasswordFailure(g_last_error) ? kErrPassword : kErrFailed;
    }
}

/**
 * Sets /CropBox on the named pages. No qpdf CLI flag expresses this, and rewriting the page
 * content to physically clip would rasterise vector art — a crop box is the lossless way to
 * change what a page shows.
 *
 * [boxes] is a flat array of 4 floats per page index, already in PDF user space
 * (llx, lly, urx, ury, origin bottom-left).
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_Qpdf_nativeSetCropBoxes(
    JNIEnv* env,
    jclass,
    jstring inPath,
    jstring outPath,
    jstring password,
    jintArray pageIndices,
    jfloatArray boxes)
{
    g_last_error.clear();

    const std::string in = jstringToUtf8(env, inPath);
    const std::string out = jstringToUtf8(env, outPath);
    const std::string pw = jstringToUtf8(env, password);

    const jsize pageCount = env->GetArrayLength(pageIndices);
    if (env->GetArrayLength(boxes) != pageCount * 4) {
        g_last_error = "crop box array length does not match page count";
        return kErrUsage;
    }

    std::vector<jint> indices(static_cast<size_t>(pageCount));
    std::vector<jfloat> rects(static_cast<size_t>(pageCount) * 4);
    if (pageCount > 0) {
        env->GetIntArrayRegion(pageIndices, 0, pageCount, indices.data());
        env->GetFloatArrayRegion(boxes, 0, pageCount * 4, rects.data());
    }

    try {
        QPDF pdf;
        pdf.processFile(in.c_str(), pw.empty() ? nullptr : pw.c_str());

        QPDFPageDocumentHelper helper(pdf);
        auto pages = helper.getAllPages();

        for (jsize i = 0; i < pageCount; ++i) {
            const auto index = static_cast<size_t>(indices[static_cast<size_t>(i)]);
            if (index >= pages.size()) {
                continue;
            }
            QPDFObjectHandle box = QPDFObjectHandle::newArray();
            for (int c = 0; c < 4; ++c) {
                box.appendItem(
                    QPDFObjectHandle::newReal(rects[static_cast<size_t>(i) * 4 + c], 4));
            }
            QPDFObjectHandle page = pages[index].getObjectHandle();
            page.replaceKey("/CropBox", box);
            // A CropBox outside the MediaBox is undefined; viewers disagree on how to resolve it,
            // so widen the MediaBox to contain it rather than leave that to chance.
            if (!page.hasKey("/MediaBox")) {
                page.replaceKey("/MediaBox", box.shallowCopy());
            }
        }

        QPDFWriter writer(pdf, out.c_str());
        writer.setObjectStreamMode(qpdf_o_generate);
        writer.write();
        return kOk;
    } catch (const std::exception& e) {
        g_last_error = e.what();
        return looksLikePasswordFailure(g_last_error) ? kErrPassword : kErrFailed;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_Qpdf_nativeEncryptionState(
    JNIEnv* env, jclass, jstring path, jstring password)
{
    g_last_error.clear();

    const std::string in = jstringToUtf8(env, path);
    const std::string pw = jstringToUtf8(env, password);

    try {
        QPDF pdf;
        pdf.processFile(in.c_str(), pw.empty() ? nullptr : pw.c_str());
        return pdf.isEncrypted() ? 1 : 0;
    } catch (const std::exception& e) {
        g_last_error = e.what();
        return looksLikePasswordFailure(g_last_error) ? kErrPassword : kErrFailed;
    }
}

namespace {

std::string jsonEscape(const std::string& value)
{
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c : value) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (static_cast<unsigned char>(c) < 0x20) {
                char buf[7];
                snprintf(buf, sizeof(buf), "\\u%04x", c);
                out += buf;
            } else {
                out += c;
            }
        }
    }
    return out;
}

std::string toHex(const std::string& raw)
{
    static const char* digits = "0123456789abcdef";
    std::string out;
    out.reserve(raw.size() * 2);
    for (const char c : raw) {
        const auto byte = static_cast<unsigned char>(c);
        out += digits[byte >> 4];
        out += digits[byte & 0x0F];
    }
    return out;
}

/**
 * A dictionary value as plain text.
 *
 * qpdf returns names *with* their leading slash — `/Sig`, not `Sig`. Comparing the raw result
 * against the bare name silently matches nothing, so the slash is stripped here, once, rather
 * than at each call site where forgetting it is invisible.
 */
std::string stringKey(QPDFObjectHandle dict, const char* key)
{
    QPDFObjectHandle value = dict.getKey(key);
    if (value.isString()) {
        return value.getUTF8Value();
    }
    if (value.isName()) {
        std::string name = value.getName();
        return name.compare(0, 1, "/") == 0 ? name.substr(1) : name;
    }
    return "";
}

/**
 * A field's type, which the PDF spec allows to be inherited from an ancestor.
 *
 * Government forms routinely put /FT on a parent and the widget on a /Kids entry, so reading only
 * the node in hand finds nothing on exactly the documents this feature exists for.
 */
std::string fieldType(QPDFObjectHandle field)
{
    QPDFObjectHandle node = field;
    for (int depth = 0; depth < 8 && node.isDictionary(); ++depth) {
        const std::string type = stringKey(node, "/FT");
        if (!type.empty()) {
            return type;
        }
        node = node.getKey("/Parent");
    }
    return "";
}

} // namespace

/**
 * Extracts every embedded signature's raw CMS blob and the byte ranges it covers, as JSON.
 *
 * The verification itself happens in Kotlin with BouncyCastle; this only locates the pieces,
 * because /Contents lives inside the file's object structure and cannot be found reliably by
 * scanning bytes once object streams or cross-reference streams are involved.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_Qpdf_nativeExtractSignatures(
    JNIEnv* env, jclass, jstring path, jstring password)
{
    g_last_error.clear();

    const std::string in = jstringToUtf8(env, path);
    const std::string pw = jstringToUtf8(env, password);

    try {
        QPDF pdf;
        pdf.processFile(in.c_str(), pw.empty() ? nullptr : pw.c_str());

        QPDFObjectHandle acroForm = pdf.getRoot().getKey("/AcroForm");
        if (!acroForm.isDictionary()) {
            return env->NewStringUTF("[]");
        }
        QPDFObjectHandle fields = acroForm.getKey("/Fields");
        if (!fields.isArray()) {
            return env->NewStringUTF("[]");
        }

        std::string json = "[";
        bool first = true;

        // Signature fields may be nested under /Kids, so walk the tree rather than the top level.
        std::vector<QPDFObjectHandle> queue;
        for (int i = 0; i < fields.getArrayNItems(); ++i) {
            queue.push_back(fields.getArrayItem(i));
        }

        while (!queue.empty()) {
            QPDFObjectHandle field = queue.back();
            queue.pop_back();
            if (!field.isDictionary()) {
                continue;
            }

            QPDFObjectHandle kids = field.getKey("/Kids");
            if (kids.isArray()) {
                for (int i = 0; i < kids.getArrayNItems(); ++i) {
                    queue.push_back(kids.getArrayItem(i));
                }
            }

            if (fieldType(field) != "Sig") {
                continue;
            }
            QPDFObjectHandle value = field.getKey("/V");
            if (!value.isDictionary()) {
                continue;
            }

            QPDFObjectHandle byteRange = value.getKey("/ByteRange");
            QPDFObjectHandle contents = value.getKey("/Contents");
            if (!byteRange.isArray() || !contents.isString()) {
                continue;
            }

            std::string ranges;
            for (int i = 0; i < byteRange.getArrayNItems(); ++i) {
                if (i > 0) {
                    ranges += ",";
                }
                ranges += std::to_string(byteRange.getArrayItem(i).getIntValue());
            }

            if (!first) {
                json += ",";
            }
            first = false;
            json += "{\"byteRange\":[" + ranges + "]";
            json += ",\"contents\":\"" + toHex(contents.getStringValue()) + "\"";
            json += ",\"subFilter\":\"" + jsonEscape(stringKey(value, "/SubFilter")) + "\"";
            json += ",\"name\":\"" + jsonEscape(stringKey(value, "/Name")) + "\"";
            json += ",\"reason\":\"" + jsonEscape(stringKey(value, "/Reason")) + "\"";
            json += ",\"location\":\"" + jsonEscape(stringKey(value, "/Location")) + "\"";
            json += ",\"date\":\"" + jsonEscape(stringKey(value, "/M")) + "\"";
            json += ",\"field\":\"" + jsonEscape(stringKey(field, "/T")) + "\"}";
        }

        json += "]";
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        g_last_error = e.what();
        return nullptr;
    }
}

namespace {

// Written with stdio rather than iostreams so a failed write keeps its errno: a full disk must
// reach Kotlin as "No space left on device", which is what turns it into a LowStorage message.
void writeBytes(const std::string& path, const unsigned char* data, size_t size)
{
    FILE* file = fopen(path.c_str(), "wb");
    if (file == nullptr) {
        throw std::runtime_error(std::string("open failed: ") + strerror(errno));
    }
    const size_t written = size == 0 ? 0 : fwrite(data, 1, size, file);
    const int writeErrno = errno;
    const bool closed = fclose(file) == 0;
    const int closeErrno = errno;
    if (written != size || !closed) {
        remove(path.c_str());
        throw std::runtime_error(
            std::string("write failed: ") + strerror(written != size ? writeErrno : closeErrno));
    }
}

std::vector<std::string> filterNames(QPDFObjectHandle dict)
{
    std::vector<std::string> names;
    QPDFObjectHandle filter = dict.getKey("/Filter");
    if (filter.isName()) {
        names.push_back(filter.getName());
    } else if (filter.isArray()) {
        for (int i = 0; i < filter.getArrayNItems(); ++i) {
            QPDFObjectHandle item = filter.getArrayItem(i);
            names.push_back(item.isName() ? item.getName() : "?");
        }
    }
    return names;
}

bool isImageMask(QPDFObjectHandle dict)
{
    QPDFObjectHandle mask = dict.getKey("/ImageMask");
    return mask.isBool() && mask.getBoolValue();
}

/** Components for the colour spaces a plain PNG can carry unchanged; 0 for anything else. */
int plainComponents(QPDFObjectHandle colorSpace)
{
    if (colorSpace.isName()) {
        const std::string name = colorSpace.getName();
        if (name == "/DeviceRGB") return 3;
        if (name == "/DeviceGray") return 1;
        return 0;
    }
    // An ICC profile only refines how RGB or grey values look; the samples themselves are the
    // same layout, and most scanners and phones tag every image this way.
    if (colorSpace.isArray() && colorSpace.getArrayNItems() == 2 &&
        colorSpace.getArrayItem(0).isName() && colorSpace.getArrayItem(0).getName() == "/ICCBased") {
        QPDFObjectHandle profile = colorSpace.getArrayItem(1);
        if (profile.isStream()) {
            QPDFObjectHandle n = profile.getDict().getKey("/N");
            if (n.isInteger() && (n.getIntValue() == 1 || n.getIntValue() == 3)) {
                return static_cast<int>(n.getIntValue());
            }
        }
    }
    return 0;
}

/** 0 = samples as stored, 1 = every component inverted, -1 = a /Decode we don't reproduce. */
int decodeMode(QPDFObjectHandle dict, int components)
{
    QPDFObjectHandle decode = dict.getKey("/Decode");
    if (!decode.isArray()) {
        return 0;
    }
    if (decode.getArrayNItems() != components * 2) {
        return -1;
    }
    bool plain = true;
    bool inverted = true;
    for (int i = 0; i < components; ++i) {
        QPDFObjectHandle lo = decode.getArrayItem(i * 2);
        QPDFObjectHandle hi = decode.getArrayItem(i * 2 + 1);
        if (!lo.isNumber() || !hi.isNumber()) {
            return -1;
        }
        const double l = lo.getNumericValue();
        const double h = hi.getNumericValue();
        plain = plain && l == 0.0 && h == 1.0;
        inverted = inverted && l == 1.0 && h == 0.0;
    }
    return plain ? 0 : (inverted ? 1 : -1);
}

} // namespace

/**
 * Saves the image XObjects used on pages [firstPage, endPage) into [outDir], as JSON describing
 * each file. JPEG images are copied byte for byte — decoding and re-encoding would only lose
 * quality. Other images are written as raw 8-bit samples for Kotlin to wrap as PNG, so this
 * file never needs a PNG encoder; formats a plain PNG can't carry (CMYK, indexed, 1-bit, JPEG
 * 2000, fax) are counted in "skipped" instead.
 *
 * Works in page chunks so the caller can cancel and report progress between calls. [seen] holds
 * the ids already handled by earlier chunks, so an image shared by many pages (a letterhead logo)
 * is saved once; "ids" returns every id this call handled, exported or skipped.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_Qpdf_nativeExtractImages(
    JNIEnv* env,
    jclass,
    jstring path,
    jstring password,
    jstring outDir,
    jint firstPage,
    jint endPage,
    jlongArray seen,
    jlong maxPixels)
{
    g_last_error.clear();

    const std::string in = jstringToUtf8(env, path);
    const std::string pw = jstringToUtf8(env, password);
    const std::string dir = jstringToUtf8(env, outDir);

    std::unordered_set<int64_t> handled;
    const jsize seenCount = env->GetArrayLength(seen);
    if (seenCount > 0) {
        std::vector<jlong> ids(static_cast<size_t>(seenCount));
        env->GetLongArrayRegion(seen, 0, seenCount, ids.data());
        handled.insert(ids.begin(), ids.end());
    }

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.processFile(in.c_str(), pw.empty() ? nullptr : pw.c_str());

        auto pages = QPDFPageDocumentHelper(pdf).getAllPages();
        const auto end = std::min(static_cast<size_t>(std::max(endPage, 0)), pages.size());
        const auto begin = std::min(static_cast<size_t>(std::max(firstPage, 0)), end);

        std::string images;
        std::string newIds;
        int skipped = 0;

        auto exportImage = [&](QPDFObjectHandle& image) {
            const QPDFObjGen og = image.getObjGen();
            const int64_t id = (static_cast<int64_t>(og.getObj()) << 16) | (og.getGen() & 0xFFFF);
            if (!handled.insert(id).second) {
                return;
            }
            if (!newIds.empty()) newIds += ",";
            newIds += std::to_string(id);

            QPDFObjectHandle dict = image.getDict();
            const std::vector<std::string> filters = filterNames(dict);
            const QPDFObjectHandle width = dict.getKey("/Width");
            const QPDFObjectHandle height = dict.getKey("/Height");
            const long long w = width.isInteger() ? width.getIntValue() : 0;
            const long long h = height.isInteger() ? height.getIntValue() : 0;
            if (w <= 0 || h <= 0 || isImageMask(dict)) {
                ++skipped;
                return;
            }

            std::string entry;
            if (filters.size() == 1 && filters[0] == "/DCTDecode") {
                std::shared_ptr<Buffer> raw = image.getRawStreamData();
                const std::string file = dir + "/x-" + std::to_string(id) + ".jpg";
                writeBytes(file, raw->getBuffer(), raw->getSize());
                entry = "{\"kind\":\"jpeg\",\"path\":\"" + jsonEscape(file) + "\"}";
            } else {
                for (const auto& name : filters) {
                    // Lossy or bilevel codecs qpdf can't decode (or would decode lossily).
                    if (name == "/DCTDecode" || name == "/JPXDecode" || name == "/CCITTFaxDecode" ||
                        name == "/JBIG2Decode" || name == "?") {
                        ++skipped;
                        return;
                    }
                }
                const QPDFObjectHandle bpc = dict.getKey("/BitsPerComponent");
                const int components = plainComponents(dict.getKey("/ColorSpace"));
                const int mode = components == 0 ? -1 : decodeMode(dict, components);
                if (!bpc.isInteger() || bpc.getIntValue() != 8 || mode < 0 ||
                    w * h > static_cast<long long>(maxPixels)) {
                    ++skipped;
                    return;
                }
                std::shared_ptr<Buffer> data;
                try {
                    data = image.getStreamData(qpdf_dl_specialized);
                } catch (const std::exception&) {
                    // A damaged stream loses this one image, not the whole export.
                    ++skipped;
                    return;
                }
                const size_t needed = static_cast<size_t>(w * h * components);
                if (data->getSize() < needed) {
                    ++skipped;
                    return;
                }
                unsigned char* samples = data->getBuffer();
                if (mode == 1) {
                    for (size_t i = 0; i < needed; ++i) {
                        samples[i] = static_cast<unsigned char>(255 - samples[i]);
                    }
                }
                const std::string file = dir + "/x-" + std::to_string(id) + ".raw";
                writeBytes(file, samples, needed);
                entry = "{\"kind\":\"raw\",\"path\":\"" + jsonEscape(file) + "\",\"w\":" +
                    std::to_string(w) + ",\"h\":" + std::to_string(h) + ",\"comps\":" +
                    std::to_string(components) + "}";
            }
            if (!images.empty()) images += ",";
            images += entry;
        };

        for (size_t i = begin; i < end; ++i) {
            pages[i].forEachImage(
                true,
                [&](QPDFObjectHandle& obj, QPDFObjectHandle&, std::string const&) {
                    exportImage(obj);
                });
        }

        const std::string json = "{\"images\":[" + images + "],\"ids\":[" + newIds +
            "],\"skipped\":" + std::to_string(skipped) + "}";
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        g_last_error = e.what();
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_skeler_scanely_core_pdfeditor_engine_Qpdf_nativeLastError(JNIEnv* env, jclass)
{
    return env->NewStringUTF(g_last_error.c_str());
}
