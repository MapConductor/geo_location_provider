#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_mapconductor_plugin_provider_geolocation_NativeBridge_concatWorld(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring input) {
    // jstring -> std::string
    const char* in_chars = env->GetStringUTFChars(input, nullptr);
    std::string s = (in_chars != nullptr) ? std::string(in_chars) : std::string();
    if (in_chars != nullptr) {
        env->ReleaseStringUTFChars(input, in_chars);
    }

    // 要件: 引数の末尾に " World!!" を結合
    s += " World!!";

    // std::string -> jstring で返す
    return env->NewStringUTF(s.c_str());
}
