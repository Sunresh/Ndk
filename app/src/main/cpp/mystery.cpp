#include <string.h>
#include <jni.h>
#include "camera.cpp"

extern "C" {

// Shorter function names
jstring stringFromJNI(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("Hello from JNI !");
}

jdouble cCircle(JNIEnv* env, jobject thiz, jdouble radius) {
    const double pi = 3.14159;
    return pi * radius * radius;
}

jdouble cRectangle(JNIEnv* env, jobject thiz, jdouble length, jdouble width) {
    return length * width;
}

// Array of native methods to be registered
static JNINativeMethod methods[] = {
    {"stringFromJNI", "()Ljava/lang/String;", (void*)stringFromJNI},
    {"cCircle", "(D)D", (void*)cCircle},
    {"cRectangle", "(DD)D", (void*)cRectangle}
};

// JNI_OnLoad function to register the native methods
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass clazz = env->FindClass("com/mycompany/myapp/MainActivity");
    if (clazz == nullptr) {
        return -1;
    }

    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        return -1;
    }

    return JNI_VERSION_1_6;
}

} // extern "C"
