#include <jni.h>
#include <android/log.h>

#define LOG_TAG "GLP-Native"

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "driveuploader JNI_OnLoad");
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetEnv failed");
        return JNI_ERR;
    }
    // ここではネイティブ関数の登録は行いません（P0では未使用）。
    return JNI_VERSION_1_6;
}
