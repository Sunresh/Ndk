#pragma once
// ═══════════════════════════════════════════════════════════════════════════
//  game_engine.h
//  Public API — everything MainActivity / JNI touches lives here.
//
//  Design rules
//  ────────────
//  • All functions are free functions inside namespace Engine.
//  • No raw pointers cross the boundary; state lives inside the .cpp.
//  • Coordinates are always NDC  [-1, +1]  (Y-up, matching OpenGL).
// ═══════════════════════════════════════════════════════════════════════════

#include <cstdint>

namespace Engine {

// ── Lifecycle ────────────────────────────────────────────────────────────────
void init();                        // called from nativeSurfaceCreated
void resize(int w, int h);          // called from nativeSurfaceChanged
void tick();                        // advance game logic   (called in drawFrame)
void draw();                        // issue all GL draw calls
void pause();
void resume();
void shutdown();                    // free all GPU resources

// ── Touch (NDC coordinates, Y-up) ────────────────────────────────────────────
void touchDown (float ndcX, float ndcY);
void touchMove (float ndcX, float ndcY);
void touchUp   (float ndcX, float ndcY);

// ── Multi-touch ──────────────────────────────────────────────────────────────
void pointerDown(int id, float ndcX, float ndcY);
void pointerUp  (int id, float ndcX, float ndcY);

// ── Gestures ─────────────────────────────────────────────────────────────────
void doubleTap (float ndcX, float ndcY);
void longPress (float ndcX, float ndcY);
void fling     (float nvx,  float nvy);   // normalised velocity (NDC / sec)

// ── Game commands (from HUD buttons / dialogs) ───────────────────────────────
void actionButton();          // Roll Dice / Deal / Flick
void dialogResult(int ok);    // 1 = OK, 0 = Cancel
void restart();

// ── Generic command channel (avoids new JNI symbols per feature) ─────────────
// commandId values are defined in game_commands.h (or below for the demo)
void command(int commandId, int param);

} // namespace Engine


// ── Command IDs (shared with Kotlin GameEvent / nativeSendCommand) ────────────
// Keep in sync with Kotlin: object GameEvent { … }
namespace Cmd {
    constexpr int SELECT_PIECE  = 10;
    constexpr int UNDO          = 11;
    constexpr int USE_POWER     = 12;
}