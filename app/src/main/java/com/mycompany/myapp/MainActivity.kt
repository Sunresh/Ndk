package com.mycompany.myapp

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var statsTextView: TextView
    private lateinit var handler: Handler
    @Volatile private var frameCount = 0
    private var lastFpsTime = 0L

    companion object {
        init { System.loadLibrary("mystery") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        statsTextView = findViewById(R.id.statsTextView)
        handler       = Handler(Looper.getMainLooper())

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                nativeSurfaceCreated()
            }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                nativeSurfaceChanged(width, height)
            }
            override fun onDrawFrame(gl: GL10?) {
                nativeDrawFrame()
                frameCount++
            }
        })
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        startFpsCounter()
    }

    // ── Touch ─────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val w = glSurfaceView.width.toFloat()
            val h = glSurfaceView.height.toFloat()

            // Convert screen pixels → NDC [-1, +1]
            val ndcX =  (event.x / w) * 2f - 1f
            val ndcY = -(event.y / h) * 2f + 1f   // Y flipped (OpenGL origin = bottom-left)

            // Must call GL functions on the GL thread
            glSurfaceView.queueEvent { nativeTouchAt(ndcX, ndcY) }
        }
        return true
    }

    private fun startFpsCounter() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (lastFpsTime > 0) {
                    val fps = frameCount * 1000L / maxOf(now - lastFpsTime, 1L)
                    statsTextView.text = "FPS: $fps  |  tap the triangle!"
                }
                frameCount  = 0
                lastFpsTime = now
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onPause()  { super.onPause();  glSurfaceView.onPause() }
    override fun onResume() { super.onResume(); glSurfaceView.onResume() }
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        glSurfaceView.queueEvent { nativeCleanup() }
    }

    private external fun nativeSurfaceCreated()
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeDrawFrame()
    private external fun nativeTouchAt(ndcX: Float, ndcY: Float)
    private external fun nativeCleanup()
}