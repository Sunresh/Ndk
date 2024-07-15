#include <string.h>
#include <jni.h>
#include "camera.cpp"

#define JAVA_CLASS com_mycompany_myapp_MainActivity

#define JAVA_FUNC(func_name) Java_##JAVA_CLASS##_##func_name

extern "C"
{
    JNIEXPORT jstring JNICALL JAVA_FUNC(stringFromJNI)(JNIEnv* env, jobject thiz)
    {
        return env->NewStringUTF("Hello from JNI !");
    }

    JNIEXPORT jdouble JNICALL JAVA_FUNC(cCircle)(JNIEnv* env, jobject thiz, jdouble radius)
    {
        const double pi = 3.14159;
        return pi * radius * radius;
    }

    // Function to calculate the area of a rectangle
    JNIEXPORT jdouble JNICALL JAVA_FUNC(cRectangle)(JNIEnv* env, jobject thiz, jdouble length, jdouble width)
    {
        return 0.5 * length * width;
    }
}
