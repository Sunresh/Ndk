#include <string.h>
#include <jni.h>
#include "camera.cpp"

extern "C"
{
	JNIEXPORT jstring JNICALL Java_com_mycompany_myapp_MainActivity_stringFromJNI(JNIEnv* env, jobject thiz)
	{
		return env->NewStringUTF("Hello from JNI !");
	}
	JNIEXPORT jdouble JNICALL Java_com_mycompany_myapp_MainActivity_cCircle(JNIEnv* env, jobject thiz, jdouble radius)
    {
        const double pi = 3.14159;
        return pi * radius * radius;
    }

    // Function to calculate the area of a rectangle
    JNIEXPORT jdouble JNICALL Java_com_mycompany_myapp_MainActivity_cRectangle(JNIEnv* env, jobject thiz, jdouble length, jdouble width)
    {
        return length * width;
    }
}
