#include <jni.h>
#include <string>
#include <cmath>
#include <sstream>
#include <iomanip>

// Constant values
constexpr double PI = 3.14159265358979323846;

// Helper function to format double to string with 2 decimal places
std::string formatDouble(double value) {
    std::stringstream ss;
    ss << std::fixed << std::setprecision(2) << value;
    return ss.str();
}

extern "C" {

/**
 * Returns a greeting message from native code
 */
JNIEXPORT jstring JNICALL
Java_com_mycompany_myapp_MainActivity_stringFromJNI(JNIEnv* env, jobject /* thiz */) {
    std::string message = "Hello from C++ NDK! 🚀";
    return env->NewStringUTF(message.c_str());
}

/**
 * Calculates the area of a circle: π * r²
 */
JNIEXPORT jdouble JNICALL
Java_com_mycompany_myapp_MainActivity_cCircle(JNIEnv* /* env */, jobject /* thiz */, jdouble radius) {
    if (radius < 0) {
        return 0.0; // Return 0 for invalid radius
    }
    return PI * radius * radius;
}

/**
 * Calculates the area of a rectangle: length * width
 */
JNIEXPORT jdouble JNICALL
Java_com_mycompany_myapp_MainActivity_cRectangle(JNIEnv* /* env */, jobject /* thiz */,
                                                  jdouble length, jdouble width) {
    if (length < 0 || width < 0) {
        return 0.0; // Return 0 for invalid dimensions
    }
    return length * width;
}

/**
 * Calculate circumference of a circle: 2 * π * r
 */
JNIEXPORT jdouble JNICALL
Java_com_mycompany_myapp_MainActivity_cCircleCircumference(JNIEnv* /* env */, jobject /* thiz */,
                                                            jdouble radius) {
    if (radius < 0) return 0.0;
    return 2 * PI * radius;
}

/**
 * Calculate area of a triangle: 0.5 * base * height
 */
JNIEXPORT jdouble JNICALL
Java_com_mycompany_myapp_MainActivity_cTriangle(JNIEnv* /* env */, jobject /* thiz */,
                                                 jdouble base, jdouble height) {
    if (base < 0 || height < 0) return 0.0;
    return 0.5 * base * height;
}

/**
 * Calculate area of a square: side * side
 */
JNIEXPORT jdouble JNICALL
Java_com_mycompany_myapp_MainActivity_cSquare(JNIEnv* /* env */, jobject /* thiz */, jdouble side) {
    if (side < 0) return 0.0;
    return side * side;
}

}