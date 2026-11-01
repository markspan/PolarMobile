// Minimal JNI bridge to the liblsl C API: just what PolarMobile needs to publish outlets.
#include <jni.h>
#include <android/log.h>
#include <lsl_c.h>

#include <string>
#include <vector>

#define LOG_TAG "polarlsl"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JNI_FN(name) Java_io_github_markspan_polarmobile_lsl_LslNative_##name

namespace {

std::string toString(JNIEnv *env, jstring s) {
	if (s == nullptr) return {};
	const char *chars = env->GetStringUTFChars(s, nullptr);
	std::string out(chars ? chars : "");
	if (chars) env->ReleaseStringUTFChars(s, chars);
	return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_FN(libraryVersion)(JNIEnv *, jobject) {
	return lsl_library_version();
}

JNIEXPORT jstring JNICALL JNI_FN(libraryInfo)(JNIEnv *env, jobject) {
	return env->NewStringUTF(lsl_library_info());
}

JNIEXPORT jdouble JNICALL JNI_FN(localClock)(JNIEnv *, jobject) {
	return lsl_local_clock();
}

/**
 * Creates a float32 outlet. Each channel gets <label>, <unit> and <type> meta-data; the channel
 * label is additionally written as <name> for compatibility with the PolarBLE desktop streams.
 * Returns the outlet handle, or 0 on failure.
 */
JNIEXPORT jlong JNICALL JNI_FN(createOutlet)(JNIEnv *env, jobject, jstring name, jstring type,
	jint channelCount, jdouble srate, jstring sourceId, jobjectArray channelLabels, jstring unit,
	jstring channelType, jstring manufacturer, jint chunkSize, jint maxBuffered) {
	const std::string sName = toString(env, name), sType = toString(env, type),
					  sSource = toString(env, sourceId), sUnit = toString(env, unit),
					  sChType = toString(env, channelType), sManuf = toString(env, manufacturer);

	lsl_streaminfo info = lsl_create_streaminfo(
		sName.c_str(), sType.c_str(), channelCount, srate, cft_float32, sSource.c_str());
	if (!info) {
		LOGE("lsl_create_streaminfo failed for %s", sName.c_str());
		return 0;
	}

	lsl_xml_ptr desc = lsl_get_desc(info);
	if (!sManuf.empty()) lsl_append_child_value(desc, "manufacturer", sManuf.c_str());
	lsl_xml_ptr channels = lsl_append_child(desc, "channels");
	const jsize nLabels = channelLabels ? env->GetArrayLength(channelLabels) : 0;
	for (jsize i = 0; i < nLabels; ++i) {
		auto jLabel = static_cast<jstring>(env->GetObjectArrayElement(channelLabels, i));
		const std::string label = toString(env, jLabel);
		env->DeleteLocalRef(jLabel);
		lsl_xml_ptr ch = lsl_append_child(channels, "channel");
		lsl_append_child_value(ch, "label", label.c_str());
		lsl_append_child_value(ch, "name", label.c_str());
		if (!sUnit.empty()) lsl_append_child_value(ch, "unit", sUnit.c_str());
		if (!sChType.empty()) lsl_append_child_value(ch, "type", sChType.c_str());
	}
	lsl_xml_ptr acq = lsl_append_child(desc, "acquisition");
	lsl_append_child_value(acq, "software", "PolarMobile (Android)");

	lsl_outlet outlet = lsl_create_outlet(info, chunkSize, maxBuffered);
	lsl_destroy_streaminfo(info); // the outlet keeps its own copy
	if (!outlet) LOGE("lsl_create_outlet failed for %s", sName.c_str());
	return reinterpret_cast<jlong>(outlet);
}

/** Pushes `sampleCount` multiplexed samples with one LSL timestamp per sample. */
JNIEXPORT jint JNICALL JNI_FN(pushChunk)(
	JNIEnv *env, jobject, jlong handle, jfloatArray data, jdoubleArray timestamps, jint sampleCount) {
	auto outlet = reinterpret_cast<lsl_outlet>(handle);
	if (!outlet || sampleCount <= 0) return 0;
	const jsize nData = env->GetArrayLength(data);
	if (env->GetArrayLength(timestamps) < sampleCount || nData % sampleCount != 0) return -1;

	std::vector<float> buf(static_cast<size_t>(nData));
	std::vector<double> ts(static_cast<size_t>(sampleCount));
	env->GetFloatArrayRegion(data, 0, nData, buf.data());
	env->GetDoubleArrayRegion(timestamps, 0, sampleCount, ts.data());
	return lsl_push_chunk_ftnp(outlet, buf.data(), static_cast<unsigned long>(nData), ts.data(), 1);
}

JNIEXPORT jboolean JNICALL JNI_FN(haveConsumers)(JNIEnv *, jobject, jlong handle) {
	auto outlet = reinterpret_cast<lsl_outlet>(handle);
	return outlet && lsl_have_consumers(outlet) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(destroyOutlet)(JNIEnv *, jobject, jlong handle) {
	auto outlet = reinterpret_cast<lsl_outlet>(handle);
	if (outlet) lsl_destroy_outlet(outlet);
}

} // extern "C"
