// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

class AspectSafeSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio: Float = 0f

    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0)
        aspectRatio = width.toFloat() / height.toFloat()
        requestLayout() // Trigger a re-measure
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (aspectRatio == 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val newWidth: Int
        val newHeight: Int

        if (width / height.toFloat() > aspectRatio) {
            // View is wider than the video, so limit width
            newWidth = (height * aspectRatio).roundToInt()
            newHeight = height
        } else {
            // View is taller than the video, so limit height
            newHeight = (width / aspectRatio).roundToInt()
            newWidth = width
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY)
        )
    }
}
