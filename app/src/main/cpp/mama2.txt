#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <cmath>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Hybrid", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Hybrid", __VA_ARGS__)

// ── State ─────────────────────────────────────────────────────────────────────
static GLuint gProgram  = 0;
static GLuint gVbo      = 0;
static float  gTime     = 0.0f;
static bool   gReady    = false;
static int    gColorSet = 0;        // which palette is active
static float  gFlash    = 0.0f;    // 1.0 → 0.0 flash on tap

// 4 color palettes – each row = 3 vertices × RGB
static const float kPalettes[4][9] = {
        { 1.0f,0.2f,0.2f,  0.2f,1.0f,0.2f,  0.2f,0.4f,1.0f },  // red/green/blue
        { 1.0f,0.8f,0.0f,  1.0f,0.3f,0.0f,  1.0f,0.6f,0.2f },  // golden/orange
        { 0.6f,0.0f,1.0f,  1.0f,0.0f,0.8f,  0.2f,0.8f,1.0f },  // purple/pink/cyan
        { 0.0f,1.0f,0.7f,  0.0f,0.8f,0.3f,  0.5f,1.0f,0.2f },  // teal/lime
};

// ── Shaders ───────────────────────────────────────────────────────────────────
static const char* kVert =
        "attribute vec2 aPos;\n"
        "attribute vec3 aColor;\n"
        "varying   vec3 vColor;\n"
        "uniform   mat4 uRot;\n"
        "void main() {\n"
        "    gl_Position = uRot * vec4(aPos, 0.0, 1.0);\n"
        "    vColor = aColor;\n"
        "}\n";

static const char* kFrag =
        "precision mediump float;\n"
        "varying vec3 vColor;\n"
        "uniform float uTime;\n"
        "uniform float uFlash;\n"
        "void main() {\n"
        "    float pulse = 0.85 + 0.15 * sin(uTime * 3.0);\n"
        "    vec3 col = vColor * pulse;\n"
        "    col = mix(col, vec3(1.0), uFlash);\n"  // white flash on tap
        "    gl_FragColor = vec4(col, 1.0);\n"
        "}\n";

// ── Shader helpers ────────────────────────────────────────────────────────────
static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512] = {};
        glGetShaderInfoLog(s, sizeof(buf), nullptr, buf);
        LOGE("Shader error: %s", buf);
        glDeleteShader(s); return 0;
    }
    return s;
}

static GLuint buildProgram() {
    GLuint vs = compileShader(GL_VERTEX_SHADER,   kVert);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFrag);
    if (!vs || !fs) return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, vs); glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512] = {};
        glGetProgramInfoLog(p, sizeof(buf), nullptr, buf);
        LOGE("Link error: %s", buf);
        glDeleteProgram(p); return 0;
    }
    return p;
}

// ── Geometry helpers ──────────────────────────────────────────────────────────
// Build vertex data for current palette into a flat float[15] array
static void buildVerts(float* out) {
    // Base positions (equilateral triangle)
    static const float pos[6] = {
            0.0f,   1.0f,
            -0.866f,-0.5f,
            0.866f,-0.5f,
    };
    const float* pal = kPalettes[gColorSet];
    for (int i = 0; i < 3; i++) {
        out[i*5+0] = pos[i*2+0];
        out[i*5+1] = pos[i*2+1];
        out[i*5+2] = pal[i*3+0];
        out[i*5+3] = pal[i*3+1];
        out[i*5+4] = pal[i*3+2];
    }
}

static void uploadVerts() {
    float verts[15];
    buildVerts(verts);
    glBindBuffer(GL_ARRAY_BUFFER, gVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_DYNAMIC_DRAW);
}

// 2-D rotation matrix (column-major mat4, scale 0.75)
static void makeRotation(float angle, float* m16) {
    float c = cosf(angle), s = sinf(angle);
    const float sc = 0.75f;
    memset(m16, 0, 16 * sizeof(float));
    m16[ 0] =  c * sc;
    m16[ 1] =  s * sc;
    m16[ 4] = -s * sc;
    m16[ 5] =  c * sc;
    m16[10] =  1.0f;
    m16[15] =  1.0f;
}

// ── Point-in-triangle (2-D, sign method) ─────────────────────────────────────
static float cross2(float ax,float ay,float bx,float by){ return ax*by - ay*bx; }

static bool pointInTriangle(float px, float py) {
    // Inverse-rotate the touch point by -gTime so we test in model space
    float c = cosf(-gTime), s = sinf(-gTime);
    const float sc = 0.75f;
    float lx = ( c * px + s * py) / sc;   // undo scale + rotation
    float ly = (-s * px + c * py) / sc;

    // Triangle vertices in model space
    float v0x =  0.0f,   v0y =  1.0f;
    float v1x = -0.866f, v1y = -0.5f;
    float v2x =  0.866f, v2y = -0.5f;

    float d0 = cross2(v1x-v0x, v1y-v0y, lx-v0x, ly-v0y);
    float d1 = cross2(v2x-v1x, v2y-v1y, lx-v1x, ly-v1y);
    float d2 = cross2(v0x-v2x, v0y-v2y, lx-v2x, ly-v2y);

    bool has_neg = (d0 < 0) || (d1 < 0) || (d2 < 0);
    bool has_pos = (d0 > 0) || (d1 > 0) || (d2 > 0);
    return !(has_neg && has_pos);
}

// ── GL lifecycle ──────────────────────────────────────────────────────────────
static void nativeSurfaceCreated() {
    gProgram = buildProgram();
    if (!gProgram) return;

    glGenBuffers(1, &gVbo);
    uploadVerts();

    glUseProgram(gProgram);
    GLint aPos   = glGetAttribLocation(gProgram, "aPos");
    GLint aColor = glGetAttribLocation(gProgram, "aColor");
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos,   2, GL_FLOAT, GL_FALSE, 5*sizeof(float), (void*)0);
    glEnableVertexAttribArray(aColor);
    glVertexAttribPointer(aColor, 3, GL_FLOAT, GL_FALSE, 5*sizeof(float),
                          (void*)(2*sizeof(float)));

    gTime = 0.0f; gFlash = 0.0f;
    gReady = true;
    LOGI("GL ready");
}

static void nativeDrawFrame() {
    if (!gReady) return;

    gTime += 0.03f;
    if (gTime > 2.0f*(float)M_PI) gTime -= 2.0f*(float)M_PI;

    // Decay flash
    gFlash -= 0.05f;
    if (gFlash < 0.0f) gFlash = 0.0f;

    glClearColor(0.05f, 0.05f, 0.12f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(gProgram);
    glBindBuffer(GL_ARRAY_BUFFER, gVbo);

    GLint aPos   = glGetAttribLocation(gProgram, "aPos");
    GLint aColor = glGetAttribLocation(gProgram, "aColor");
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos,   2, GL_FLOAT, GL_FALSE, 5*sizeof(float), (void*)0);
    glEnableVertexAttribArray(aColor);
    glVertexAttribPointer(aColor, 3, GL_FLOAT, GL_FALSE, 5*sizeof(float),
                          (void*)(2*sizeof(float)));

    float mat[16];
    makeRotation(gTime, mat);
    glUniformMatrix4fv(glGetUniformLocation(gProgram,"uRot"),  1, GL_FALSE, mat);
    glUniform1f(glGetUniformLocation(gProgram,"uTime"),  gTime);
    glUniform1f(glGetUniformLocation(gProgram,"uFlash"), gFlash);

    glDrawArrays(GL_TRIANGLES, 0, 3);
}

static void nativeSurfaceChanged(int w, int h) {
    glViewport(0, 0, w, h);
}

// ndcX/ndcY: already converted from screen pixels to [-1,+1] in Kotlin
static void nativeTouchAt(float ndcX, float ndcY) {
    if (!gReady) return;
    if (pointInTriangle(ndcX, ndcY)) {
        gColorSet = (gColorSet + 1) % 4;
        gFlash    = 1.0f;           // trigger white flash
        uploadVerts();              // push new colours to GPU
        LOGI("Hit! palette -> %d", gColorSet);
    }
}

static void nativeCleanup() {
    if (gVbo)     { glDeleteBuffers(1, &gVbo);  gVbo = 0; }
    if (gProgram) { glDeleteProgram(gProgram);  gProgram = 0; }
    gReady = false;
}

// ── JNI ───────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_nativeSurfaceCreated(JNIEnv*, jobject)
{ nativeSurfaceCreated(); }

JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_nativeSurfaceChanged(JNIEnv*, jobject, jint w, jint h)
{ nativeSurfaceChanged(w, h); }

JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_nativeDrawFrame(JNIEnv*, jobject)
{ nativeDrawFrame(); }

JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_nativeTouchAt(JNIEnv*, jobject, jfloat x, jfloat y)
{ nativeTouchAt(x, y); }

JNIEXPORT void JNICALL
Java_com_mycompany_myapp_MainActivity_nativeCleanup(JNIEnv*, jobject)
{ nativeCleanup(); }

}