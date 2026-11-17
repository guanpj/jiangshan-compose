package com.jiangshan.atlas

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val zoomSweep = intent.getBooleanExtra("zoom_sweep", false)
        val zoomFit = intent.getBooleanExtra("zoom_fit", false)
        val timelineSpan = intent.getIntExtra("timeline_span", 0)
        val timelineYear = intent.getIntExtra("timeline_year", Int.MIN_VALUE)
        setContent { App(zoomSweep = zoomSweep, zoomFit = zoomFit, timelineSpan = timelineSpan, timelineYear = timelineYear) }
    }
}
