package com.mycompany.myapp;

import android.app.Activity;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
		TextView tx = findViewById(R.id.mainTextView);
		tx.setText( stringFromJNI() );
		double circleArea = cCircle(5.0); // Replace 5.0 with the desired radius
        double rectangleArea = cRectangle(200.0, 300.0); // Replace 4.0 and 3.0 with the desired length and width

        // Display the calculated areas
        tx.append("\nArea of Circle: " + circleArea);
        tx.append("\nArea of Rectangle: " + rectangleArea);
		
    }
    static {
        System.loadLibrary("mystery");
    }
	public native String  stringFromJNI();
	
	public native double cCircle(double radius);

    public native double cRectangle(double length, double width);
}

