// ═══════════════════════════════════════════════════════════════════════════
//  bridge_callbacks.h
//
//  Thin C++ wrappers around the Kotlin GameBridge @JvmStatic methods.
//  game_engine.cpp calls these; JNI plumbing lives in game_jni.cpp.
//
//  These are the ONLY points where C++ touches the JVM.
//  ─────────────────────────────────────────────────────
//  Kotlin side:  object GameBridge { @JvmStatic fun onXxx(…) }
//  C++ side:     Bridge::xxx(…)  →  CallStaticVoidMethod
// ═══════════════════════════════════════════════════════════════════════════
#pragma once
#include <string>

namespace Bridge {
    void scoreChanged(int player, int score);
    void turnChanged (int playerIndex);
    void gameEvent   (int eventCode, int payload);
    void playSound   (int soundId);
    void showDialog  (int type, const std::string& message);
}
