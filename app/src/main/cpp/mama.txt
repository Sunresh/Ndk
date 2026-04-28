#include <jni.h>
#include <opencv2/opencv.hpp>
#include <android/bitmap.h>
#include <cmath>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <android/native_window_jni.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Hybrid", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Hybrid", __VA_ARGS__)

using namespace cv;

// ================== GLOBAL STATE ==================
static float rotationAngle = 0;
static Mat frame(500, 500, CV_8UC3);
static bool firstFrame = true;

// OpenGL ES globals
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLSurface surface = EGL_NO_SURFACE;
static EGLContext context = EGL_NO_CONTEXT;
static GLuint program = 0;
static GLuint vbo = 0;
static GLuint textureId = 0;
static int screenWidth = 500;
static int screenHeight = 500;
static bool isOpenGLInitialized = false;

// ================== HELPER: Mat to Bitmap ==================
jobject matToBitmap(JNIEnv* env, Mat& img) {
    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    if (bitmapCls == nullptr) return nullptr;

    jmethodID createBitmap = env->GetStaticMethodID(
            bitmapCls, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );

    jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
    jmethodID valueOf = env->GetStaticMethodID(
            configCls, "valueOf",
            "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;"
    );

    jobject config = env->CallStaticObjectMethod(
            configCls, valueOf, env->NewStringUTF("ARGB_8888")
    );

    jobject bitmap = env->CallStaticObjectMethod(
            bitmapCls, createBitmap, img.cols, img.rows, config
    );

    if (bitmap == nullptr) return nullptr;

    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) {
        return nullptr;
    }

    Mat tmp(img.rows, img.cols, CV_8UC4, pixels);
    cvtColor(img, tmp, COLOR_BGR2RGBA);

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

// ================== 3D TRANSFORMATIONS ==================
void rotate3D(float &x, float &y, float &z, float angleX, float angleY, float angleZ) {
    float x1 = x * cos(angleY) + z * sin(angleY);
    float z1 = -x * sin(angleY) + z * cos(angleY);
    float y1 = y * cos(angleX) - z1 * sin(angleX);
    float z2 = y * sin(angleX) + z1 * cos(angleX);
    float x2 = x1 * cos(angleZ) - y1 * sin(angleZ);
    float y2 = x1 * sin(angleZ) + y1 * cos(angleZ);
    x = x2;
    y = y2;
    z = z2;
}

Point project(float x, float y, float z, float focalLength = 300) {
    float perspective = focalLength / (z + 5);
    return Point(250 + x * perspective, 250 + y * perspective);
}

// ================== OPENCV TO OPENGL TEXTURE ==================
GLuint matToTexture(const Mat& img) {
    GLuint texture;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);

    Mat rgba;
    if (img.channels() == 3) {
        cvtColor(img, rgba, COLOR_BGR2RGBA);
    } else if (img.channels() == 1) {
        cvtColor(img, rgba, COLOR_GRAY2RGBA);
    } else {
        rgba = img;
    }

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, rgba.cols, rgba.rows, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, rgba.data);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    return texture;
}

// ================== OPENGL SHADERS ==================
const char* vertexShaderSource =
    "attribute vec4 aPosition;"
    "attribute vec2 aTexCoord;"
    "varying vec2 vTexCoord;"
    "void main() {"
    "    gl_Position = aPosition;"
    "    vTexCoord = aTexCoord;"
    "}";

const char* fragmentShaderSource =
    "precision mediump float;"
    "varying vec2 vTexCoord;"
    "uniform sampler2D uTexture;"
    "uniform float uTime;"
    "void main() {"
    "    vec4 color = texture2D(uTexture, vTexCoord);"
    "    color.rgb += sin(uTime) * 0.15;"
    "    gl_FragColor = color;"
    "}";

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = (char*)malloc(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            LOGE("Shader compilation error: %s", infoLog);
            free(infoLog);
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint createProgram() {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource);

    if (vertexShader == 0 || fragmentShader == 0) {
        LOGE("Failed to compile shaders");
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);

    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = (char*)malloc(infoLen);
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog);
            LOGE("Program linking error: %s", infoLog);
            free(infoLog);
        }
        glDeleteProgram(program);
        return 0;
    }

    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    return program;
}

// ================== OPENCV PROCESSING FUNCTIONS ==================
Mat processWithOpenCV(Mat& input) {
    Mat result = input.clone();

    // Add Gaussian blur for glow effect
    Mat blurred;
    GaussianBlur(result, blurred, Size(3, 3), 0);

    // Add edge detection overlay
    Mat edges, edgesColor;
    cvtColor(result, edges, COLOR_BGR2GRAY);
    Canny(edges, edges, 50, 150);
    cvtColor(edges, edgesColor, COLOR_GRAY2BGR);

    // Blend original with edges
    addWeighted(blurred, 0.8, edgesColor, 0.2, 0, result);

    return result;
}

Mat drawCubeWithOpenCV(float angle) {
    Mat img = Mat::zeros(500, 500, CV_8UC3);

    // Background gradient
    for (int i = 0; i < 500; i++) {
        line(img, Point(0, i), Point(500, i), Scalar(10, 10, 30 + i/5), 1);
    }

    float cube[8][3] = {
        {-1.2,-1.2,-1.2}, { 1.2,-1.2,-1.2}, { 1.2, 1.2,-1.2}, {-1.2, 1.2,-1.2},
        {-1.2,-1.2, 1.2}, { 1.2,-1.2, 1.2}, { 1.2, 1.2, 1.2}, {-1.2, 1.2, 1.2}
    };

    float rotated[8][3];
    for (int i = 0; i < 8; i++) {
        rotated[i][0] = cube[i][0];
        rotated[i][1] = cube[i][1];
        rotated[i][2] = cube[i][2];
        rotate3D(rotated[i][0], rotated[i][1], rotated[i][2], angle, angle * 0.8, angle * 0.5);
    }

    Point pts[8];
    for (int i = 0; i < 8; i++) {
        pts[i] = project(rotated[i][0], rotated[i][1], rotated[i][2], 280);
    }

    int edges[][2] = {
        {0,1}, {1,2}, {2,3}, {3,0}, {4,5}, {5,6}, {6,7}, {7,4}, {0,4}, {1,5}, {2,6}, {3,7}
    };

    // Color cycling
    int r = 100 + (int)(155 * (sin(angle) + 1) / 2);
    int g = 100 + (int)(155 * (sin(angle + 2) + 1) / 2);
    int b = 100 + (int)(155 * (sin(angle + 4) + 1) / 2);

    for (auto &edge : edges) {
        Scalar color = Scalar(b, g, r);
        line(img, pts[edge[0]], pts[edge[1]], color, 3);
    }

    for (int i = 0; i < 8; i++) {
        circle(img, pts[i], 7, Scalar(0, 255, 255), -1);
        circle(img, pts[i], 11, Scalar(0, 255, 255), 1);
    }

    // Add text
    putText(img, "OpenCV + OpenGL ES", Point(130, 40), FONT_HERSHEY_SIMPLEX, 0.7, Scalar(255, 255, 255), 2);

    return img;
}

// ================== OPENGL RENDERING ==================
void initOpenGL(ANativeWindow* window) {
    LOGI("Initializing OpenGL...");

    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return;
    }

    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) {
        LOGE("eglInitialize failed");
        return;
    }

    LOGI("EGL version: %d.%d", major, minor);

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(display, attribs, &config, 1, &numConfigs)) {
        LOGE("eglChooseConfig failed");
        return;
    }

    surface = eglCreateWindowSurface(display, config, window, nullptr);
    if (surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return;
    }

    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return;
    }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        LOGE("eglMakeCurrent failed");
        return;
    }

    glViewport(0, 0, screenWidth, screenHeight);

    program = createProgram();
    if (program == 0) {
        LOGE("Failed to create shader program");
        return;
    }
    glUseProgram(program);

    // Setup vertices for full-screen quad
    GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 0.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 0.0f, 1.0f, 0.0f,
    };

    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    GLint posAttrib = glGetAttribLocation(program, "aPosition");
    glEnableVertexAttribArray(posAttrib);
    glVertexAttribPointer(posAttrib, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(GLfloat), 0);

    GLint texAttrib = glGetAttribLocation(program, "aTexCoord");
    glEnableVertexAttribArray(texAttrib);
    glVertexAttribPointer(texAttrib, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(GLfloat),
                         (void*)(3 * sizeof(GLfloat)));

    isOpenGLInitialized = true;
    LOGI("OpenGL ES initialized successfully");
}

void renderWithOpenGL(float angle) {
    if (!isOpenGLInitialized || display == EGL_NO_DISPLAY) return;

    // Generate frame with OpenCV
    Mat cvFrame = drawCubeWithOpenCV(angle);
    Mat processedFrame = processWithOpenCV(cvFrame);

    // Convert to texture and render
    if (textureId != 0) {
        glDeleteTextures(1, &textureId);
    }
    textureId = matToTexture(processedFrame);

    glClearColor(0.1f, 0.1f, 0.2f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId);
    GLint texUniform = glGetUniformLocation(program, "uTexture");
    if (texUniform != -1) glUniform1i(texUniform, 0);

    GLint timeUniform = glGetUniformLocation(program, "uTime");
    if (timeUniform != -1) glUniform1f(timeUniform, angle);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    eglSwapBuffers(display, surface);
}

void cleanupOpenGL() {
    if (vbo != 0) {
        glDeleteBuffers(1, &vbo);
        vbo = 0;
    }
    if (textureId != 0) {
        glDeleteTextures(1, &textureId);
        textureId = 0;
    }
    if (program != 0) {
        glDeleteProgram(program);
        program = 0;
    }
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) {
            eglDestroySurface(display, surface);
            surface = EGL_NO_SURFACE;
        }
        if (context != EGL_NO_CONTEXT) {
            eglDestroyContext(display, context);
            context = EGL_NO_CONTEXT;
        }
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
    }
    isOpenGLInitialized = false;
}

// ================== MOVING 3D CUBE (OpenCV-only fallback) ==================
extern "C" JNIEXPORT jobject JNICALL
Java_com_mycompany_myapp_MainActivity_drawMoving3D(JNIEnv* env, jobject) {
    if (firstFrame) {
        frame = Mat::zeros(500, 500, CV_8UC3);
        firstFrame = false;
    }

    frame.setTo(Scalar(10, 10, 30));

    float cube[8][3] = {
        {-1.2,-1.2,-1.2}, { 1.2,-1.2,-1.2}, { 1.2, 1.2,-1.2}, {-1.2, 1.2,-1.2},
        {-1.2,-1.2, 1.2}, { 1.2,-1.2, 1.2}, { 1.2, 1.2, 1.2}, {-1.2, 1.2, 1.2}
    };

    float rotated[8][3];
    float timeAngle = rotationAngle;

    for (int i = 0; i < 8; i++) {
        rotated[i][0] = cube[i][0];
        rotated[i][1] = cube[i][1];
        rotated[i][2] = cube[i][2];
        rotate3D(rotated[i][0], rotated[i][1], rotated[i][2],
                 timeAngle, timeAngle * 0.8, timeAngle * 0.5);
    }

    Point pts[8];
    for (int i = 0; i < 8; i++) {
        pts[i] = project(rotated[i][0], rotated[i][1], rotated[i][2], 280);
    }

    int edges[][2] = {
        {0,1}, {1,2}, {2,3}, {3,0}, {4,5}, {5,6}, {6,7}, {7,4}, {0,4}, {1,5}, {2,6}, {3,7}
    };

    int r = 100 + (int)(155 * (sin(rotationAngle) + 1) / 2);
    int g = 100 + (int)(155 * (sin(rotationAngle + 2) + 1) / 2);
    int b = 100 + (int)(155 * (sin(rotationAngle + 4) + 1) / 2);

    for (auto &edge : edges) {
        Scalar color = Scalar(b, g, r);
        line(frame, pts[edge[0]], pts[edge[1]], color, 3);
    }

    for (int i = 0; i < 8; i++) {
        circle(frame, pts[i], 7, Scalar(0, 255, 255), -1);
        circle(frame, pts[i], 10, Scalar(0, 255, 255), 1);
    }

    line(frame, Point(250, 250), Point(400, 250), Scalar(0, 0, 255), 1);
    line(frame, Point(250, 250), Point(250, 100), Scalar(0, 255, 0), 1);

    rotationAngle += 0.07;
    if (rotationAngle > 2 * M_PI) rotationAngle -= 2 * M_PI;

    return matToBitmap(env, frame);
}

// ================== STATIC 3D DRAW ==================
extern "C" JNIEXPORT jobject JNICALL
Java_com_mycompany_myapp_MainActivity_draw3D(JNIEnv* env, jobject) {
    Mat img = Mat::zeros(500, 500, CV_8UC3);

    float cube[8][3] = {
        {-1,-1,-1}, {1,-1,-1}, {1,1,-1}, {-1,1,-1},
        {-1,-1,1}, {1,-1,1}, {1,1,1}, {-1,1,1}
    };

    Point pts[8];
    for (int i = 0; i < 8; i++) {
        pts[i] = project(cube[i][0], cube[i][1], cube[i][2], 250);
    }

    int edges[][2] = {
        {0,1}, {1,2}, {2,3}, {3,0}, {4,5}, {5,6}, {6,7}, {7,4}, {0,4}, {1,5}, {2,6}, {3,7}
    };

    for (auto &edge : edges) {
        line(img, pts[edge[0]], pts[edge[1]], Scalar(0, 255, 255), 2);
    }

    for (int i = 0; i < 8; i++) {
        circle(img, pts[i], 5, Scalar(255, 100, 0), -1);
    }

    return matToBitmap(env, img);
}

// ================== 2D DRAW ==================
extern "C" JNIEXPORT jobject JNICALL
Java_com_mycompany_myapp_MainActivity_draw2D(JNIEnv* env, jobject) {
    Mat img = Mat::zeros(500, 500, CV_8UC3);

    circle(img, Point(250, 150), 80, Scalar(0, 255, 0), 3);
    rectangle(img, Point(100, 300), Point(400, 450), Scalar(255, 0, 0), 3);
    putText(img, "2D Shapes", Point(180, 70), FONT_HERSHEY_SIMPLEX, 1, Scalar(255, 255, 255), 2);

    return matToBitmap(env, img);
}

// ================== OPENGL JNI EXPORTS ==================
extern "C" JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_initOpenGL(JNIEnv* env, jobject obj, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window) {
        initOpenGL(window);
        ANativeWindow_release(window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_renderFrame(JNIEnv* env, jobject obj) {
    rotationAngle += 0.05f;
    if (rotationAngle > 2 * M_PI) rotationAngle -= 2 * M_PI;

    renderWithOpenGL(rotationAngle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_resize(JNIEnv* env, jobject obj, jint width, jint height) {
    screenWidth = width;
    screenHeight = height;
    if (isOpenGLInitialized) {
        glViewport(0, 0, width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_cleanupOpenGL(JNIEnv* env, jobject obj) {
    cleanupOpenGL();
}

// ================== STRING FUNCTION ==================
extern "C" JNIEXPORT jstring JNICALL
Java_com_mycompany_myapp_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    return env->NewStringUTF("🚀 Hybrid OpenCV + OpenGL ES Engine Ready!");
}

// ================== MATH FUNCTIONS ==================
extern "C" JNIEXPORT jdouble JNICALL
Java_com_mycompany_myapp_MainActivity_cCircle(JNIEnv*, jobject, jdouble r) {
    return 3.14159265359 * r * r;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_mycompany_myapp_MainActivity_cRectangle(JNIEnv*, jobject, jdouble l, jdouble w) {
    return l * w;
}