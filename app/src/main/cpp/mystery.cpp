// ═══════════════════════════════════════════════════════════════════════════
//  mystery.cpp
//
//  Two responsibilities:
//    1. Export every nativeXxx() symbol that MainActivity.kt declares.
//    2. Implement bridge_callbacks.h by calling back into Kotlin.
//
//  Naming rule (must be exact):
//    Kotlin:  private external fun nativeFoo()
//    C++:     Java_com_mycompany_myapp_MainActivity_nativeFoo(JNIEnv*, jobject)
// ═══════════════════════════════════════════════════════════════════════════
// (Normally this would be a separate .cpp file; merged here for readability.)

#include "game_engine.h"
#include "bridge_callbacks.h"

#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "GameJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

// ── Package / class shorthand ─────────────────────────────────────────────────
#define MAIN_CLASS   "com/mycompany/myapp/MainActivity"
#define BRIDGE_CLASS "com/mycompany/myapp/GameBridge"

// ── Macro: build the full JNI symbol name from a bare function name ───────────
#define JNI(name) \
    JNIEXPORT void JNICALL Java_com_mycompany_myapp_MainActivity_##name

// ════════════════════════════════════════════════════════════════════════════
//  SECTION 1 — Kotlin → C++   (JNI exports)
// ════════════════════════════════════════════════════════════════════════════
extern "C" {

// ── Lifecycle ─────────────────────────────────────────────────────────────────
JNI(nativeSurfaceCreated)(JNIEnv*, jobject)              { Engine::init(); }
JNI(nativeSurfaceChanged)(JNIEnv*, jobject,
                           jint w, jint h)               { Engine::resize(w, h); }
JNI(nativeDrawFrame)     (JNIEnv*, jobject)              { Engine::tick(); Engine::draw(); }
JNI(nativePause)         (JNIEnv*, jobject)              { Engine::pause(); }
JNI(nativeResume)        (JNIEnv*, jobject)              { Engine::resume(); }
JNI(nativeCleanup)       (JNIEnv*, jobject)              { Engine::shutdown(); }

// ── Primary touch ─────────────────────────────────────────────────────────────
JNI(nativeTouchDown)(JNIEnv*, jobject, jfloat x, jfloat y) { Engine::touchDown(x, y); }
JNI(nativeTouchMove)(JNIEnv*, jobject, jfloat x, jfloat y) { Engine::touchMove(x, y); }
JNI(nativeTouchUp)  (JNIEnv*, jobject, jfloat x, jfloat y) { Engine::touchUp(x, y); }

// ── Multi-touch ───────────────────────────────────────────────────────────────
JNI(nativePointerDown)(JNIEnv*, jobject,
                        jint id, jfloat x, jfloat y)   { Engine::pointerDown(id, x, y); }
JNI(nativePointerUp)  (JNIEnv*, jobject,
                        jint id, jfloat x, jfloat y)   { Engine::pointerUp(id, x, y); }

// ── Gestures ──────────────────────────────────────────────────────────────────
JNI(nativeDoubleTap)(JNIEnv*, jobject, jfloat x, jfloat y) { Engine::doubleTap(x, y); }
JNI(nativeLongPress)(JNIEnv*, jobject, jfloat x, jfloat y) { Engine::longPress(x, y); }
JNI(nativeFling)    (JNIEnv*, jobject, jfloat vx,jfloat vy){ Engine::fling(vx, vy); }

// ── Game commands ─────────────────────────────────────────────────────────────
JNI(nativeActionButton)(JNIEnv*, jobject)              { Engine::actionButton(); }
JNI(nativeDialogResult)(JNIEnv*, jobject, jint r)      { Engine::dialogResult(r); }
JNI(nativeRestartGame) (JNIEnv*, jobject)              { Engine::restart(); }
JNI(nativeSendCommand) (JNIEnv*, jobject,
                         jint cmd, jint p)             { Engine::command(cmd, p); }

} // extern "C"


// ════════════════════════════════════════════════════════════════════════════
//  SECTION 2 — C++ → Kotlin   (Bridge::xxx implementations)
//
//  Called from the GL thread; GameBridge.onXxx() posts to the main thread,
//  so we never need AttachCurrentThread here — we are already attached
//  because GLSurfaceView manages the GL thread and calls FindClass once.
//
//  Pattern for every callback:
//    1. GetEnv from cached JavaVM
//    2. FindClass("com/mycompany/myapp/GameBridge")
//    3. GetStaticMethodID(…)
//    4. CallStaticVoidMethod(…)
// ════════════════════════════════════════════════════════════════════════════

// ── Cache the JavaVM once on JNI_OnLoad ──────────────────────────────────────
static JavaVM* gJvm = nullptr;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    LOGI("JNI_OnLoad — JavaVM cached");
    return JNI_VERSION_1_6;
}

/** Helper: returns the JNIEnv for the calling thread (GL thread is attached). */
static JNIEnv* getEnv() {
    JNIEnv* env = nullptr;
    if (gJvm) gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    return env;
}

// ── Bridge implementations ───────────────────────────────────────────────────

namespace Bridge {

void scoreChanged(int player, int score) {
    JNIEnv* env = getEnv();
    if (!env) return;
    jclass   cls = env->FindClass(BRIDGE_CLASS);
    jmethodID m  = env->GetStaticMethodID(cls, "onScoreChanged", "(II)V");
    env->CallStaticVoidMethod(cls, m, (jint)player, (jint)score);
    env->DeleteLocalRef(cls);
}

void turnChanged(int playerIndex) {
    JNIEnv* env = getEnv();
    if (!env) return;
    jclass    cls = env->FindClass(BRIDGE_CLASS);
    jmethodID m   = env->GetStaticMethodID(cls, "onTurnChanged", "(I)V");
    env->CallStaticVoidMethod(cls, m, (jint)playerIndex);
    env->DeleteLocalRef(cls);
}

void gameEvent(int eventCode, int payload) {
    JNIEnv* env = getEnv();
    if (!env) return;
    jclass    cls = env->FindClass(BRIDGE_CLASS);
    jmethodID m   = env->GetStaticMethodID(cls, "onGameEvent", "(II)V");
    env->CallStaticVoidMethod(cls, m, (jint)eventCode, (jint)payload);
    env->DeleteLocalRef(cls);
}

void playSound(int soundId) {
    JNIEnv* env = getEnv();
    if (!env) return;
    jclass    cls = env->FindClass(BRIDGE_CLASS);
    jmethodID m   = env->GetStaticMethodID(cls, "onPlaySound", "(I)V");
    env->CallStaticVoidMethod(cls, m, (jint)soundId);
    env->DeleteLocalRef(cls);
}

void showDialog(int type, const std::string& msg) {
    JNIEnv* env = getEnv();
    if (!env) return;
    jclass    cls  = env->FindClass(BRIDGE_CLASS);
    jmethodID m    = env->GetStaticMethodID(cls, "onShowDialog",
                                            "(ILjava/lang/String;)V");
    jstring   jmsg = env->NewStringUTF(msg.c_str());
    env->CallStaticVoidMethod(cls, m, (jint)type, jmsg);
    env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(cls);
}

} // namespace Bridge