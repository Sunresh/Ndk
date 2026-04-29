#include "game_engine.h"
#include "bridge_callbacks.h"   // Bridge::scoreChanged(), Bridge::gameEvent()…

#include <GLES2/gl2.h>
#include <cmath>
#include <cstdlib>
#include <ctime>
#include <array>
#include <string>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "GameEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GameEngine", __VA_ARGS__)

// ─── Constants ───────────────────────────────────────────────────────────────

static constexpr int   NUM_TOKENS   = 4;
static constexpr int   NUM_WAYPOINTS = 8;   // steps around the board
static constexpr float TOKEN_RADIUS  = 0.10f;
static constexpr float SELECT_SCALE  = 1.35f;

// GameEvent codes (must match Kotlin GameEvent object)
static constexpr int EVT_DICE_ROLLED     = 1;
static constexpr int EVT_PIECE_MOVED     = 2;
static constexpr int EVT_PIECE_CAPTURED  = 5;
static constexpr int EVT_GAME_OVER       = 7;

// SoundId codes (must match Kotlin SoundId object)
static constexpr int SND_DICE_ROLL  = 2;
static constexpr int SND_PIECE_LAND = 3;
static constexpr int SND_WIN_FANFARE = 5;

// ─── Shader source ───────────────────────────────────────────────────────────

static const char* VERT_SRC = R"(
    attribute vec2 aPos;
    uniform   vec2 uCenter;
    uniform   float uScale;
    void main() {
        vec2 p = aPos * uScale + uCenter;
        gl_Position = vec4(p, 0.0, 1.0);
    }
)";

static const char* FRAG_SRC = R"(
    precision mediump float;
    uniform vec4 uColor;
    void main() {
        gl_FragColor = uColor;
    }
)";

/// edit by me 

// ─── Types ───────────────────────────────────────────────────────────────────

struct Vec2 { float x, y; };
struct Color4 { float r, g, b, a; };

struct Token {
    int   owner;          // 0–3 (player index)
    int   waypointIndex;  // current position on the path
    int   laps;           // full laps completed
    bool  selected;
    bool  alive;
    Color4 color;
};

// ─── Module-level state (all private to this translation unit) ───────────────
namespace {

    // OpenGL handles
    GLuint gProgram  = 0;
    GLuint gQuadVBO  = 0;

    // Uniform / attribute locations
    GLint gLocPos, gLocCenter, gLocScale, gLocColor;

    // Viewport
    int gWidth = 1, gHeight = 1;

    // Board waypoints (a simple octagon path in NDC)
    Vec2 gWaypoints[NUM_WAYPOINTS];

    // Game state
    Token  gTokens[NUM_TOKENS];
    int    gCurrentPlayer = 0;
    int    gLastDice      = 0;
    bool   gGameOver      = false;
    bool   gWaitingForTap = false; // true after dice rolled, waiting for piece tap

    // Unit-square quad verts (will be scaled / translated via uniforms)
    // Two triangles forming a [-1,+1] square
    const float QUAD_VERTS[] = {
        -1.f, -1.f,
         1.f, -1.f,
        -1.f,  1.f,
         1.f, -1.f,
         1.f,  1.f,
        -1.f,  1.f,
    };

    // Animation pulse timer
    float gPulse = 0.f;

} // anonymous namespace

// ─── GL helpers ──────────────────────────────────────────────────────────────

static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512]; glGetShaderInfoLog(s, 512, nullptr, buf);
        LOGE("Shader compile error: %s", buf);
    }
    return s;
}

static GLuint buildProgram() {
    GLuint v = compileShader(GL_VERTEX_SHADER,   VERT_SRC);
    GLuint f = compileShader(GL_FRAGMENT_SHADER, FRAG_SRC);
    GLuint p = glCreateProgram();
    glAttachShader(p, v); glAttachShader(p, f);
    glLinkProgram(p);
    GLint ok; glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) { char buf[512]; glGetProgramInfoLog(p, 512, nullptr, buf); LOGE("Link: %s", buf); }
    glDeleteShader(v); glDeleteShader(f);
    return p;
}

// ─── Board geometry ──────────────────────────────────────────────────────────

static void buildWaypoints() {
    // Place NUM_WAYPOINTS evenly on a circle of radius 0.65
    for (int i = 0; i < NUM_WAYPOINTS; ++i) {
        float angle = (float)i / NUM_WAYPOINTS * 2.f * 3.14159f - 3.14159f / 2.f;
        gWaypoints[i] = { cosf(angle) * 0.65f, sinf(angle) * 0.65f };
    }
}

// ─── Token helpers ───────────────────────────────────────────────────────────

static void resetTokens() {
    // Each player starts at a different waypoint (evenly spaced)
    Color4 colors[4] = {
        {0.9f, 0.2f, 0.2f, 1.f},   // red
        {0.2f, 0.6f, 0.9f, 1.f},   // blue
        {0.2f, 0.85f, 0.3f, 1.f},  // green
        {0.95f, 0.75f, 0.1f, 1.f}, // yellow
    };
    for (int i = 0; i < NUM_TOKENS; ++i) {
        gTokens[i] = { i, i * 2, 0, false, true, colors[i] };
    }
}

/** Returns the NDC position of a token (its current waypoint) */
static Vec2 tokenPos(const Token& t) {
    return gWaypoints[t.waypointIndex % NUM_WAYPOINTS];
}

/** Hit-test: is NDC point (x,y) inside token t? */
static bool hitToken(const Token& t, float x, float y) {
    if (!t.alive) return false;
    Vec2 p = tokenPos(t);
    float dx = x - p.x, dy = y - p.y;
    return (dx*dx + dy*dy) <= (TOKEN_RADIUS * TOKEN_RADIUS * SELECT_SCALE * SELECT_SCALE);
}

// ─── Drawing ─────────────────────────────────────────────────────────────────

/** Draw a filled quad at (cx,cy), scaled by s, with colour c */
static void drawQuad(float cx, float cy, float s, Color4 c) {
    glUniform2f(gLocCenter, cx, cy);
    glUniform1f(gLocScale,  s);
    glUniform4f(gLocColor, c.r, c.g, c.b, c.a);
    glDrawArrays(GL_TRIANGLES, 0, 6);
}

static void drawBoard() {
    // Dark background board quad
    drawQuad(0.f, 0.f, 0.85f, {0.13f, 0.13f, 0.18f, 1.f});

    // Waypoint dots
    for (int i = 0; i < NUM_WAYPOINTS; ++i) {
        Color4 wc = {0.28f, 0.28f, 0.35f, 1.f};
        drawQuad(gWaypoints[i].x, gWaypoints[i].y, 0.045f, wc);
    }
}

static void drawTokens() {
    float pulse = 0.5f + 0.5f * sinf(gPulse * 4.f); // 0→1 oscillation

    for (int i = 0; i < NUM_TOKENS; ++i) {
        const Token& t = gTokens[i];
        if (!t.alive) continue;

        Vec2 p = tokenPos(t);

        // Shadow / outline
        drawQuad(p.x + 0.008f, p.y - 0.008f, TOKEN_RADIUS * 1.15f,
                 {0.f, 0.f, 0.f, 0.5f});

        float sc = t.selected ? (TOKEN_RADIUS * (1.f + 0.25f * pulse)) : TOKEN_RADIUS;

        // Selection glow ring
        if (t.selected) {
            drawQuad(p.x, p.y, sc * 1.45f,
                     {1.f, 0.9f, 0.1f, 0.5f + 0.3f * pulse});
        }

        // Token body
        drawQuad(p.x, p.y, sc, t.color);

        // Highlight dot
        drawQuad(p.x - sc*0.25f, p.y + sc*0.3f, sc * 0.28f,
                 {1.f, 1.f, 1.f, 0.4f});
    }
}

// ─── Dice & turn logic ───────────────────────────────────────────────────────

static int rollDice() { return (rand() % 6) + 1; }

static void deselectAll() {
    for (auto& t : gTokens) t.selected = false;
}

/** Move the selected token for the current player by `steps` waypoints */
static void moveSelected(int steps) {
    for (int i = 0; i < NUM_TOKENS; ++i) {
        Token& t = gTokens[i];
        if (t.owner != gCurrentPlayer || !t.selected) continue;

        int prevWP = t.waypointIndex;
        t.waypointIndex = (t.waypointIndex + steps) % NUM_WAYPOINTS;
        if (t.waypointIndex < prevWP) t.laps++;   // crossed start → lapped

        Bridge::gameEvent(EVT_PIECE_MOVED, i);
        Bridge::playSound(SND_PIECE_LAND);

        // Capture check — any enemy on same waypoint?
        for (int j = 0; j < NUM_TOKENS; ++j) {
            if (j == i) continue;
            Token& enemy = gTokens[j];
            if (enemy.alive && enemy.waypointIndex == t.waypointIndex) {
                enemy.alive = false;
                Bridge::gameEvent(EVT_PIECE_CAPTURED, j);
            }
        }

        // Win check — first to complete 2 laps
        if (t.laps >= 2) {
            gGameOver = true;
            Bridge::gameEvent(EVT_GAME_OVER, gCurrentPlayer);
            Bridge::playSound(SND_WIN_FANFARE);
            Bridge::showDialog(2 /*DialogType::WINNER*/,
                               "Player " + std::to_string(gCurrentPlayer + 1) + " wins!");
            return;
        }

        // Advance turn
        deselectAll();
        gCurrentPlayer = (gCurrentPlayer + 1) % NUM_TOKENS;
//        Bridge::turnChanged(gCurrentPlayer);
//        Bridge::scoreChanged(gCurrentPlayer, gTokens[gCurrentPlayer].laps);
        gWaitingForTap = false;
        return;
    }
    // No piece was selected — nothing to move
}

// ═══════════════════════════════════════════════════════════════════════════
//  Engine API Implementation
// ═══════════════════════════════════════════════════════════════════════════

namespace Engine {

void init() {
    LOGI("Engine::init");
    srand((unsigned)time(nullptr));

    gProgram = buildProgram();
    glUseProgram(gProgram);

    // Cache locations
    gLocPos    = glGetAttribLocation (gProgram, "aPos");
    gLocCenter = glGetUniformLocation(gProgram, "uCenter");
    gLocScale  = glGetUniformLocation(gProgram, "uScale");
    gLocColor  = glGetUniformLocation(gProgram, "uColor");

    // Upload quad geometry once
    glGenBuffers(1, &gQuadVBO);
    glBindBuffer(GL_ARRAY_BUFFER, gQuadVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_VERTS), QUAD_VERTS, GL_STATIC_DRAW);

    buildWaypoints();
    resetTokens();

    glClearColor(0.08f, 0.08f, 0.12f, 1.f);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Notify Kotlin of initial state
//    Bridge::turnChanged(0);
//    Bridge::scoreChanged(0, 0);
}

void resize(int w, int h) {
    LOGI("Engine::resize %d×%d", w, h);
    gWidth = w; gHeight = h;
    glViewport(0, 0, w, h);
}

void tick() {
    if (gGameOver) return;
    gPulse += 0.016f;   // ~1 tick per frame @ 60 fps
}

void draw() {
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(gProgram);

    glBindBuffer(GL_ARRAY_BUFFER, gQuadVBO);
    glEnableVertexAttribArray(gLocPos);
    glVertexAttribPointer(gLocPos, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    drawBoard();
    drawTokens();

    glDisableVertexAttribArray(gLocPos);
}

void pause()  { LOGI("Engine::pause"); }
void resume() { LOGI("Engine::resume"); }

void shutdown() {
    LOGI("Engine::shutdown");
    if (gQuadVBO)  { glDeleteBuffers(1, &gQuadVBO);  gQuadVBO  = 0; }
    if (gProgram)  { glDeleteProgram(gProgram);       gProgram  = 0; }
}

// ── Touch ────────────────────────────────────────────────────────────────────

void touchDown(float ndcX, float ndcY) {
    if (gGameOver || !gWaitingForTap) return;

    // Hit-test tokens owned by the current player
    for (int i = 0; i < NUM_TOKENS; ++i) {
        Token& t = gTokens[i];
        if (t.owner != gCurrentPlayer || !t.alive) continue;
        if (hitToken(t, ndcX, ndcY)) {
            deselectAll();
            t.selected = true;
            LOGI("Token %d selected", i);
            return;
        }
    }
}

void touchMove(float /*ndcX*/, float /*ndcY*/) {
    // Not used in this demo — hook drag-and-drop here for carrom striker
}

void touchUp(float ndcX, float ndcY) {
    // In this demo, touchDown does the work; touchUp is a no-op.
    (void)ndcX; (void)ndcY;
}

// ── Multi-touch ──────────────────────────────────────────────────────────────

void pointerDown(int id, float ndcX, float ndcY) {
    // Demo: treat second finger same as primary finger
    (void)id;
    touchDown(ndcX, ndcY);
}

void pointerUp(int id, float ndcX, float ndcY) {
    (void)id; (void)ndcX; (void)ndcY;
}

// ── Gestures ─────────────────────────────────────────────────────────────────

void doubleTap(float ndcX, float ndcY) {
    // Double-tap a token = move it immediately (skip dice) — demo shortcut
    touchDown(ndcX, ndcY);
    if (gWaitingForTap) moveSelected(1);
}

void longPress(float ndcX, float ndcY) {
    LOGI("longPress at %.2f,%.2f — could show context menu", ndcX, ndcY);
}

void fling(float nvx, float nvy) {
    // In a carrom game: launch striker here.
    // For this demo, fling strength = dice override
    float speed = sqrtf(nvx*nvx + nvy*nvy);
    LOGI("fling speed=%.2f — carrom hook point", speed);
}

// ── Game commands ─────────────────────────────────────────────────────────────

void actionButton() {
    // "Roll Dice" button pressed
    if (gGameOver)         return;
    if (gWaitingForTap)    return;   // must move before rolling again

    gLastDice = rollDice();
    LOGI("Dice rolled: %d", gLastDice);

    Bridge::gameEvent(EVT_DICE_ROLLED, gLastDice);
    Bridge::playSound(SND_DICE_ROLL);

    gWaitingForTap = true;   // player must now tap a token

    // Auto-select if only one token is alive for this player
    int aliveCount = 0, aliveIdx = -1;
    for (int i = 0; i < NUM_TOKENS; ++i) {
        if (gTokens[i].owner == gCurrentPlayer && gTokens[i].alive) {
            aliveCount++; aliveIdx = i;
        }
    }
    if (aliveCount == 1) {
        deselectAll();
        gTokens[aliveIdx].selected = true;
        moveSelected(gLastDice);   // auto-move the only surviving token
    }
}

void dialogResult(int ok) {
    if (ok && gWaitingForTap && gLastDice > 0) {
        moveSelected(gLastDice);
    }
}

void restart() {
    LOGI("Engine::restart");
    gGameOver      = false;
    gCurrentPlayer = 0;
    gLastDice      = 0;
    gWaitingForTap = false;
    gPulse         = 0.f;
    resetTokens();
    Bridge::turnChanged(0);
    Bridge::scoreChanged(0, 0);
}

void command(int commandId, int param) {
    switch (commandId) {
        case Cmd::SELECT_PIECE:
            if (param >= 0 && param < NUM_TOKENS) {
                deselectAll();
                gTokens[param].selected = true;
            }
            break;
        case Cmd::UNDO:
            LOGI("Undo — not implemented in demo");
            break;
        default:
            LOGI("Unknown command %d param=%d", commandId, param);
    }
}

} // namespace Engine