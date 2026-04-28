package com.mycompany.myapp

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity() {

    // All properties initialized or marked as lateinit
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private lateinit var statsTextView: TextView
    private lateinit var toggleButton: Button
    private lateinit var animationToggleBtn: Button
    private lateinit var resetButton: Button

    private var isOpenGLMode = true
    private var isAnimating = true
    private lateinit var handler: Handler  // Fixed: lateinit instead of direct initialization
    private var frameCount = 0
    private var lastFpsUpdate = 0L
    private var rotationAngle = 0f  // Fixed: initialized with default value

    companion object {
        init {
            System.loadLibrary("mystery")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupOpenGL()
        setupClickListeners()

        handler = Handler(Looper.getMainLooper())  // Initialize handler here
        startFpsCounter()

        textView.text = stringFromJNI()
    }

    private fun initViews() {
        glSurfaceView = findViewById(R.id.glSurfaceView)
        imageView = findViewById(R.id.imageView)
        textView = findViewById(R.id.mainTextView)
        statsTextView = findViewById(R.id.statsTextView)
        toggleButton = findViewById(R.id.toggleButton)
        animationToggleBtn = findViewById(R.id.animationToggleBtn)
        resetButton = findViewById(R.id.resetButton)
    }

    private fun setupOpenGL() {
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                initOpenGL(glSurfaceView.holder.surface)
                statsTextView.text = "Mode: OpenGL (GPU Accelerated)"
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                resize(width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                if (isAnimating) {
                    rotationAngle += 0.05f
                    if (rotationAngle > 360f) rotationAngle -= 360f
                    renderFrame()
                    frameCount++
                }
            }
        })
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun setupClickListeners() {
        toggleButton.setOnClickListener {
            isOpenGLMode = !isOpenGLMode
            if (isOpenGLMode) {
                glSurfaceView.visibility = GLSurfaceView.VISIBLE
                imageView.visibility = ImageView.GONE
                toggleButton.text = "Switch to OpenCV"
                statsTextView.text = "Mode: OpenGL (GPU Accelerated)"
                glSurfaceView.onResume()
            } else {
                glSurfaceView.visibility = GLSurfaceView.GONE
                imageView.visibility = ImageView.VISIBLE
                toggleButton.text = "Switch to OpenGL"
                statsTextView.text = "Mode: OpenCV (CPU Processing)"
                startOpenCVAnimation()
            }
        }

        animationToggleBtn.setOnClickListener {
            isAnimating = !isAnimating
            animationToggleBtn.text = if (isAnimating) "Pause" else "Resume"
            if (!isOpenGLMode && isAnimating) {
                startOpenCVAnimation()
            }
        }

        resetButton.setOnClickListener {
            rotationAngle = 0f
            statsTextView.text = "${statsTextView.text}\nReset at ${System.currentTimeMillis()}"
        }
    }

    private fun startOpenCVAnimation() {
        Thread {
            while (!isOpenGLMode && isAnimating) {
                runOnUiThread {
                    val bitmap = drawMoving3D()
                    imageView.setImageBitmap(bitmap)
                    frameCount++
                }
                Thread.sleep(33)
            }
        }.start()
    }

    private fun startFpsCounter() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                if (lastFpsUpdate > 0) {
                    val elapsed = currentTime - lastFpsUpdate
                    val fps = if (elapsed > 0) (frameCount * 1000.0 / elapsed).toInt() else 0
                    statsTextView.text = String.format(
                        "Mode: %s | FPS: %d | Angle: %.1f°",
                        if (isOpenGLMode) "OpenGL" else "OpenCV",
                        fps,
                        rotationAngle
                    )
                }
                frameCount = 0
                lastFpsUpdate = currentTime
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onPause() {
        super.onPause()
        if (isOpenGLMode) {
            glSurfaceView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isOpenGLMode) {
            glSurfaceView.onResume()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupOpenGL()
        handler.removeCallbacksAndMessages(null)
    }

    // Native methods
    external fun initOpenGL(surface: Any)
    external fun renderFrame()
    external fun resize(width: Int, height: Int)
    external fun cleanupOpenGL()
    external fun drawMoving3D(): Bitmap
    external fun stringFromJNI(): String
    external fun cCircle(radius: Double): Double
    external fun cRectangle(length: Double, width: Double): Double
}