package com.mycompany.myapp

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// ─── Constants ────────────────────────────────────────────────────────────────

private const val TAG = "GameNDK"

/** Game-event codes shared with C++ (keep in sync with game_events.h) */
object GameEvent {
    const val DICE_ROLLED       = 1
    const val PIECE_MOVED       = 2
    const val CARD_FLIPPED      = 3
    const val CARD_SELECTED     = 4
    const val PIECE_CAPTURED    = 5
    const val TURN_SKIP         = 6
    const val GAME_OVER         = 7
    const val SAFE_ZONE_REACHED = 8
    const val COIN_STRUCK       = 9   // carrom
    const val QUEEN_POCKETED    = 10  // carrom
}

/** Sound IDs – must match assets loaded in SoundManager */
object SoundId {
    const val CARD_FLIP   = 1
    const val DICE_ROLL   = 2
    const val PIECE_LAND  = 3
    const val COIN_STRIKE = 4
    const val WIN_FANFARE = 5
    const val ERROR_BUZZ  = 6
}

/** Dialog types the NDK can request */
object DialogType {
    const val INFO    = 0
    const val CONFIRM = 1
    const val WINNER  = 2
}

// ─── Sound Manager ────────────────────────────────────────────────────────────

class SoundManager(context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    /** Map soundId → SoundPool streamId */
    private val loadedSounds = mutableMapOf<Int, Int>()

    init {
        // Load from res/raw – add your actual resource IDs here
        // loadedSounds[SoundId.CARD_FLIP]   = soundPool.load(context, R.raw.card_flip,   1)
        // loadedSounds[SoundId.DICE_ROLL]   = soundPool.load(context, R.raw.dice_roll,   1)
        // loadedSounds[SoundId.PIECE_LAND]  = soundPool.load(context, R.raw.piece_land,  1)
        // loadedSounds[SoundId.COIN_STRIKE] = soundPool.load(context, R.raw.coin_strike, 1)
        // loadedSounds[SoundId.WIN_FANFARE] = soundPool.load(context, R.raw.win_fanfare, 1)
        // loadedSounds[SoundId.ERROR_BUZZ]  = soundPool.load(context, R.raw.error_buzz,  1)
    }

    fun play(soundId: Int, volume: Float = 1f) {
        loadedSounds[soundId]?.let { streamId ->
            soundPool.play(streamId, volume, volume, 1, 0, 1f)
        }
    }

    fun release() = soundPool.release()
}

// ─── Vibration Helper ─────────────────────────────────────────────────────────

class HapticHelper(context: Context) {

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** Short click (piece tap) */
    fun click() = vibrate(20L)

    /** Medium bump (dice roll, piece capture) */
    fun bump() = vibrate(60L)

    /** Long buzz (illegal move) */
    fun error() = vibrate(120L)

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(ms)
        }
    }
}

// ─── Game Bridge (NDK → Kotlin static callbacks) ─────────────────────────────
/**
 * C++ calls these via JNI on whichever thread it chooses (usually the GL thread).
 * We immediately hop to the main thread for any UI mutation.
 *
 * C++ usage example:
 *   jclass  cls = env->FindClass("com/mycompany/myapp/GameBridge");
 *   jmethod mid = env->GetStaticMethodID(cls, "onScoreChanged", "(II)V");
 *   env->CallStaticVoidMethod(cls, mid, playerIndex, newScore);
 */
object GameBridge {

    /** Weak reference set by MainActivity on create / cleared on destroy */
    @Volatile
    var listener: GameEventListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    interface GameEventListener {
        fun onScoreChanged(player: Int, score: Int)
        fun onTurnChanged(playerIndex: Int)
        fun onGameEvent(eventCode: Int, payload: Int)
        fun onPlaySound(soundId: Int)
        fun onShowDialog(type: Int, message: String)
    }

    // ── Called from NDK ───────────────────────────────────────────────────

    @JvmStatic
    fun onScoreChanged(player: Int, score: Int) {
        mainHandler.post { listener?.onScoreChanged(player, score) }
    }

    @JvmStatic
    fun onTurnChanged(playerIndex: Int) {
        mainHandler.post { listener?.onTurnChanged(playerIndex) }
    }

    /**
     * Generic event channel.
     * @param eventCode  one of GameEvent.*
     * @param payload    event-specific integer (e.g. dice value, card id)
     */
    @JvmStatic
    fun onGameEvent(eventCode: Int, payload: Int) {
        mainHandler.post { listener?.onGameEvent(eventCode, payload) }
    }

    @JvmStatic
    fun onPlaySound(soundId: Int) {
        mainHandler.post { listener?.onPlaySound(soundId) }
    }

    @JvmStatic
    fun onShowDialog(type: Int, message: String) {
        mainHandler.post { listener?.onShowDialog(type, message) }
    }
}

// ─── MainActivity ─────────────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class MainActivity : AppCompatActivity(), GameBridge.GameEventListener {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var glSurfaceView : GLSurfaceView
    private lateinit var hudOverlay    : FrameLayout   // transparent HUD above GL
    private lateinit var tvScore       : TextView      // "P1: 0   P2: 0"
    private lateinit var tvTurn        : TextView      // "Player 1's turn"
    private lateinit var tvFps         : TextView      // debug FPS counter
    private lateinit var btnAction     : Button        // Roll Dice / Deal Cards

    // ── Helpers ────────────────────────────────────────────────────────────
    private lateinit var soundManager  : SoundManager
    private lateinit var haptic        : HapticHelper
    private lateinit var gestureDetector: GestureDetector

    // ── State ──────────────────────────────────────────────────────────────
    @Volatile private var frameCount   = 0
    private var fpsUpdateMs            = 0L
    private val mainHandler            = Handler(Looper.getMainLooper())

    // ── Scores per player (mirror of NDK state) ────────────────────────────
    private val scores = intArrayOf(0, 0, 0, 0)  // up to 4 players
    private var currentPlayer = 0

    // ── Native library ─────────────────────────────────────────────────────
    companion object {
        init { System.loadLibrary("mystery") }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN          or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION     or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        setContentView(R.layout.activity_main)

        // ── Wire up views ────────────────────────────────────────────────
        glSurfaceView = findViewById(R.id.glSurfaceView)
        hudOverlay    = findViewById(R.id.hudOverlay)
        tvScore       = findViewById(R.id.tvScore)
        tvTurn        = findViewById(R.id.tvTurn)
        tvFps         = findViewById(R.id.tvFps)
        btnAction     = findViewById(R.id.btnAction)

        // ── Helpers ──────────────────────────────────────────────────────
        soundManager = SoundManager(this)
        haptic       = HapticHelper(this)

        // ── Gesture detector ─────────────────────────────────────────────
        gestureDetector = GestureDetector(this, GameGestureListener())

        // ── OpenGL ───────────────────────────────────────────────────────
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(GameRenderer())
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Prevent HUD touch events from reaching GL view accidentally
        hudOverlay.setOnTouchListener { _, _ -> false }

        // ── HUD button ───────────────────────────────────────────────────
        btnAction.setOnClickListener { onActionButtonClicked() }

        // ── NDK → Kotlin callback bridge ─────────────────────────────────
        GameBridge.listener = this

        // ── FPS ticker ───────────────────────────────────────────────────
        scheduleFpsTick()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        glSurfaceView.queueEvent { nativeResume() }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.queueEvent { nativePause() }
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        GameBridge.listener = null
        mainHandler.removeCallbacksAndMessages(null)
        glSurfaceView.queueEvent { nativeCleanup() }
        soundManager.release()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Touch → NDK pipeline
    //  All game input flows through one method: dispatchToNdk()
    //  so it is easy to add logging, replay recording, or AI injection.
    // ══════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        val w = glSurfaceView.width.toFloat()
        val h = glSurfaceView.height.toFloat()

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val (nx, ny) = toNdc(event.x, event.y, w, h)
                glSurfaceView.queueEvent { nativeTouchDown(nx, ny) }
                haptic.click()
            }

            MotionEvent.ACTION_MOVE -> {
                val (nx, ny) = toNdc(event.x, event.y, w, h)
                glSurfaceView.queueEvent { nativeTouchMove(nx, ny) }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val (nx, ny) = toNdc(event.x, event.y, w, h)
                glSurfaceView.queueEvent { nativeTouchUp(nx, ny) }
            }

            // ── Multi-touch (carrom striker, pinch-zoom board) ──────────
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val (nx, ny) = toNdc(event.getX(idx), event.getY(idx), w, h)
                val pointerId = event.getPointerId(idx)
                glSurfaceView.queueEvent { nativePointerDown(pointerId, nx, ny) }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val (nx, ny) = toNdc(event.getX(idx), event.getY(idx), w, h)
                val pointerId = event.getPointerId(idx)
                glSurfaceView.queueEvent { nativePointerUp(pointerId, nx, ny) }
            }
        }
        return true
    }

    /** Screen pixels → OpenGL NDC  [-1, +1] × [-1, +1] */
    private fun toNdc(px: Float, py: Float, w: Float, h: Float): Pair<Float, Float> {
        val nx =  (px / w) * 2f - 1f
        val ny = -(py / h) * 2f + 1f   // Y-flip: screen top → GL bottom
        return nx to ny
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Gesture Detector (fling → flick card/coin, long-press → context menu)
    // ══════════════════════════════════════════════════════════════════════

    private inner class GameGestureListener : GestureDetector.SimpleOnGestureListener() {

        private val FLING_MIN_VELOCITY = 200f

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val w = glSurfaceView.width.toFloat()
            val h = glSurfaceView.height.toFloat()
            val (nx, ny) = toNdc(e.x, e.y, w, h)
            glSurfaceView.queueEvent { nativeDoubleTap(nx, ny) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val w = glSurfaceView.width.toFloat()
            val h = glSurfaceView.height.toFloat()
            val (nx, ny) = toNdc(e.x, e.y, w, h)
            glSurfaceView.queueEvent { nativeLongPress(nx, ny) }
            haptic.bump()
        }

        /**
         * Fling: send normalised velocity to NDK.
         * Used for carrom striker flick, card throw, dice shake.
         * velX/velY are clamped to [-1, +1] relative to screen size.
         */
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val speed = Math.hypot(velocityX.toDouble(), velocityY.toDouble()).toFloat()
            if (speed < FLING_MIN_VELOCITY) return false

            val w = glSurfaceView.width.toFloat()
            val h = glSurfaceView.height.toFloat()

            // Normalise velocity to screen-size units (NDC-per-second feel)
            val nvx =  (velocityX / w) * 2f
            val nvy = -(velocityY / h) * 2f   // Y-flip matches NDC convention
            glSurfaceView.queueEvent { nativeFling(nvx, nvy) }
            return true
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GL Renderer
    // ══════════════════════════════════════════════════════════════════════

    private inner class GameRenderer : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            Log.d(TAG, "onSurfaceCreated")
            nativeSurfaceCreated()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            Log.d(TAG, "onSurfaceChanged $width×$height")
            nativeSurfaceChanged(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            nativeDrawFrame()
            frameCount++
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HUD / Overlay actions
    // ══════════════════════════════════════════════════════════════════════

    private fun onActionButtonClicked() {
        // The button label and semantics change based on game state.
        // The NDK owns game state; just forward the event and let it decide.
        haptic.bump()
        glSurfaceView.queueEvent { nativeActionButton() }
    }

    /** Called by NDK (via GameBridge) to relabel the action button */
    fun setActionButtonLabel(label: String) {
        mainHandler.post { btnAction.text = label }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GameBridge.GameEventListener  (NDK → Kotlin callbacks)
    // ══════════════════════════════════════════════════════════════════════

    override fun onScoreChanged(player: Int, score: Int) {
        if (player in scores.indices) scores[player] = score
        refreshScoreHud()
    }

    override fun onTurnChanged(playerIndex: Int) {
        currentPlayer = playerIndex
        val names = arrayOf("Player 1", "Player 2", "Player 3", "Player 4")
        tvTurn.text = "${names.getOrElse(playerIndex) { "Player ${playerIndex+1}" }}'s turn"
    }

    override fun onGameEvent(eventCode: Int, payload: Int) {
        when (eventCode) {
            GameEvent.DICE_ROLLED    -> {
                haptic.bump()
                Log.d(TAG, "Dice rolled: $payload")
            }
            GameEvent.PIECE_CAPTURED -> haptic.bump()
            GameEvent.GAME_OVER      -> showWinnerDialog(payload)
            GameEvent.COIN_STRUCK    -> haptic.click()
            else                     -> Log.d(TAG, "GameEvent $eventCode payload=$payload")
        }
    }

    override fun onPlaySound(soundId: Int) = soundManager.play(soundId)

    override fun onShowDialog(type: Int, message: String) {
        when (type) {
            DialogType.WINNER  -> showWinnerDialog(message = message)
            DialogType.CONFIRM -> showConfirmDialog(message)
            else               -> showInfoDialog(message)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HUD helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun refreshScoreHud() {
        tvScore.text = "P1:${scores[0]}  P2:${scores[1]}"
    }

    private fun scheduleFpsTick() {
        mainHandler.postDelayed({
            val now   = System.currentTimeMillis()
            val delta = now - fpsUpdateMs
            if (delta > 0) {
                val fps = frameCount * 1000L / delta
                tvFps.text = "FPS: $fps"
                frameCount = 0
                fpsUpdateMs = now
            }
            scheduleFpsTick()        // reschedule
        }, 1000L)
        fpsUpdateMs = System.currentTimeMillis()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Dialogs
    // ══════════════════════════════════════════════════════════════════════

    private fun showWinnerDialog(playerIndex: Int = -1, message: String = "") {
        val msg = message.ifEmpty {
            val names = arrayOf("Player 1", "Player 2", "Player 3", "Player 4")
            "${names.getOrElse(playerIndex) { "Player ${playerIndex+1}" }} wins!"
        }
        AlertDialog.Builder(this)
            .setTitle("Game Over")
            .setMessage(msg)
            .setPositiveButton("Play Again") { _, _ ->
                glSurfaceView.queueEvent { nativeRestartGame() }
            }
            .setNegativeButton("Quit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showConfirmDialog(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK")     { _, _ ->
                glSurfaceView.queueEvent { nativeDialogResult(1) }
            }
            .setNegativeButton("Cancel") { _, _ ->
                glSurfaceView.queueEvent { nativeDialogResult(0) }
            }
            .show()
    }

    private fun showInfoDialog(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  JNI declarations
    //  Keep in sync with:  jni/game_jni.cpp
    //
    //  Every extern "C" JNIEXPORT void JNICALL
    //  Java_com_mycompany_myapp_MainActivity_nativeXxx(...) { … }
    // ══════════════════════════════════════════════════════════════════════

    // ── Lifecycle ─────────────────────────────────────────────────────────
    private external fun nativeSurfaceCreated()
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeDrawFrame()
    private external fun nativePause()
    private external fun nativeResume()
    private external fun nativeCleanup()

    // ── Touch ─────────────────────────────────────────────────────────────
    /** Primary finger press (NDC coordinates) */
    private external fun nativeTouchDown(ndcX: Float, ndcY: Float)
    /** Primary finger drag */
    private external fun nativeTouchMove(ndcX: Float, ndcY: Float)
    /** Primary finger lift */
    private external fun nativeTouchUp(ndcX: Float, ndcY: Float)

    // ── Multi-touch ───────────────────────────────────────────────────────
    private external fun nativePointerDown(pointerId: Int, ndcX: Float, ndcY: Float)
    private external fun nativePointerUp  (pointerId: Int, ndcX: Float, ndcY: Float)

    // ── Gestures ──────────────────────────────────────────────────────────
    private external fun nativeDoubleTap (ndcX: Float, ndcY: Float)
    private external fun nativeLongPress (ndcX: Float, ndcY: Float)
    /** Normalised fling velocity (NDC units/sec, Y-up) */
    private external fun nativeFling     (nvx: Float, nvy: Float)

    // ── Game commands ─────────────────────────────────────────────────────
    /** Player pressed the Roll / Deal / Flick action button */
    private external fun nativeActionButton()
    /** result = 1 (OK) or 0 (Cancel) from a confirm dialog */
    private external fun nativeDialogResult(result: Int)
    /** Full game reset */
    private external fun nativeRestartGame()

    // ── Optional: send arbitrary game-specific command ────────────────────
    /**
     * Flexible channel for game-specific commands without adding new JNI symbols.
     *
     * Examples:
     *   nativeSendCommand(CMD_SELECT_CARD, cardId)
     *   nativeSendCommand(CMD_USE_POWER,   powerType)
     *   nativeSendCommand(CMD_UNDO,        0)
     */
    private external fun nativeSendCommand(commandId: Int, param: Int)
}
