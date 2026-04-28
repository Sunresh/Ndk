package com.mycompany.myapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("mystery")
        }
    }

    private lateinit var resultTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultTextView = findViewById(R.id.mainTextView)

        calculateAndDisplay()
    }

    private fun calculateAndDisplay() {
        // Get JNI greeting message
        val greeting = stringFromJNI()

        // Calculate areas
        val radius = 7.5
        val circleArea = cCircle(radius)

        val length = 12.0
        val width = 8.5
        val rectangleArea = cRectangle(length, width)

        // Format the output
        val result = buildString {
            appendLine(greeting)
            appendLine()
            appendLine("=== Geometry Calculator ===")
            appendLine("Circle (radius = $radius):")
            appendLine("  Area = %.2f".format(circleArea))
            appendLine()
            appendLine("Rectangle (length = $length, width = $width):")
            appendLine("  Area = %.2f".format(rectangleArea))
        }

        resultTextView.text = result
    }

    // Native methods
    external fun stringFromJNI(): String
    external fun cCircle(radius: Double): Double
    external fun cRectangle(length: Double, width: Double): Double
}