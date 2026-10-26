/*
 * SPDX-FileCopyrightText: 2025 Martin Sh <hemisputnik@proton.me>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.digitizer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.google.android.material.R
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.roundToInt

class DrawingPadView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private fun mmToPixels(mm: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_MM,
            mm,
            resources.displayMetrics
        )

    val gridSpacing = mmToPixels(5.0f)
    val gridOffset = mmToPixels(2.5f)

    val backgroundPaint = Paint().apply {
        color = MaterialColors.getColor(this@DrawingPadView, R.attr.colorSurfaceContainer, Color.WHITE)
        style = Paint.Style.FILL
    }

    val linePaint = Paint().apply {
        color = MaterialColors.getColor(this@DrawingPadView, R.attr.colorOutlineVariant, Color.WHITE)
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
    }

    var eventListener: EventListener? = null
    var fingerTouchEventsEnabled: Boolean = false

    // Keypad pen, for phones with no touchscreen: arrows or 2 4 6 8 move a pen cursor, OK or 5 puts the pen
    // down / lifts it, * switches pen / eraser, 0 lifts it. It reports the same tool events a stylus does.
    var keypadPen: Boolean = false
        set(value) {
            field = value
            isFocusable = value
            isFocusableInTouchMode = value
            invalidate()
        }
    var penDown: Boolean = false
        private set
    var penTool: ToolEvent.Tool = ToolEvent.Tool.Pen
        private set
    private var penX = -1f
    private var penY = -1f
    private val strokes = Path() // local preview of what the pen drew

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@DrawingPadView, R.attr.colorOnSurface, Color.BLACK)
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@DrawingPadView, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    /** Puts the keypad pen in the middle of the pad and tells the other end it is in range. */
    fun startKeypadPen() {
        if (!keypadPen || width == 0) return
        if (penX < 0) {
            penX = width / 2f
            penY = height / 2f
        }
        eventListener?.onToolEvent(ToolEvent(active = true, tool = penTool, x = penX.roundToInt(), y = penY.roundToInt()))
        invalidate()
    }

    fun togglePen() {
        if (penX < 0) startKeypadPen()
        penDown = !penDown
        if (penDown && penTool == ToolEvent.Tool.Pen) strokes.moveTo(penX, penY)
        eventListener?.onToolEvent(
            ToolEvent(
                active = true,
                touching = penDown,
                tool = penTool,
                x = penX.roundToInt(),
                y = penY.roundToInt(),
                pressure = if (penDown) 1.0 else 0.0
            )
        )
        eventListener?.onKeypadPenChanged()
        invalidate()
    }

    private fun toggleTool() {
        penTool = if (penTool == ToolEvent.Tool.Pen) ToolEvent.Tool.Rubber else ToolEvent.Tool.Pen
        eventListener?.onToolEvent(ToolEvent(tool = penTool, x = penX.roundToInt(), y = penY.roundToInt()))
        eventListener?.onKeypadPenChanged()
        invalidate()
    }

    private fun movePen(dx: Float, dy: Float) {
        if (penX < 0) startKeypadPen()
        penX = (penX + dx).coerceIn(0f, (width - 1).toFloat())
        penY = (penY + dy).coerceIn(0f, (height - 1).toFloat())
        if (penDown && penTool == ToolEvent.Tool.Pen) strokes.lineTo(penX, penY)
        eventListener?.onToolEvent(
            ToolEvent(
                tool = penTool,
                x = penX.roundToInt(),
                y = penY.roundToInt(),
                pressure = if (penDown) 1.0 else null
            )
        )
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!keypadPen) return super.onKeyDown(keyCode, event)
        // 4dp a press; holding a key speeds the pen up (to 24dp a step), like the keypad mouse
        val step = (4 + 2 * event.repeatCount).coerceAtMost(24) * resources.displayMetrics.density
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_4 -> movePen(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_6 -> movePen(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_2 -> movePen(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_8 -> movePen(0f, step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_5 ->
                if (event.repeatCount == 0) togglePen()
            KeyEvent.KEYCODE_STAR -> if (event.repeatCount == 0) toggleTool()
            KeyEvent.KEYCODE_0 -> if (event.repeatCount == 0 && penDown) togglePen()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun convertTool(motionEventTool: Int): ToolEvent.Tool =
        when (motionEventTool) {
            MotionEvent.TOOL_TYPE_ERASER -> ToolEvent.Tool.Rubber
            else -> ToolEvent.Tool.Pen
        }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER -> eventListener?.onToolEvent(
                ToolEvent(
                    active = true,
                    x = event.x.roundToInt(),
                    y = event.y.roundToInt()
                )
            )

            MotionEvent.ACTION_HOVER_EXIT -> eventListener?.onToolEvent(
                ToolEvent(
                    active = false,
                )
            )

            MotionEvent.ACTION_HOVER_MOVE -> eventListener?.onToolEvent(
                ToolEvent(
                    tool = convertTool(event.getToolType(0)),
                    x = event.x.roundToInt(),
                    y = event.y.roundToInt()
                )
            )
        }
        return true
    }

    private fun findPressure(event: MotionEvent): Double =
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
            // We report constant 1.0 pressure for fingers, since most devices can't report
            // pressure for fingers (so they report a constant 0.001).
            if (fingerTouchEventsEnabled) 1.0
            else 0.0
        else
            event.pressure.toDouble()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                eventListener?.onToolEvent(
                    ToolEvent(
                        active = true,
                        touching = toolType != MotionEvent.TOOL_TYPE_FINGER,
                        tool = convertTool(toolType),
                        x = event.x.roundToInt(),
                        y = event.y.roundToInt(),
                        pressure = findPressure(event)
                    )
                )

                if (toolType == MotionEvent.TOOL_TYPE_FINGER)
                    eventListener?.onFingerTouchEvent(true)
            }

            MotionEvent.ACTION_UP -> {
                eventListener?.onToolEvent(
                    ToolEvent(
                        // If the finger is lifted from the screen,
                        // we consider the device as "not tracking the tool".
                        active = toolType != MotionEvent.TOOL_TYPE_FINGER,
                        touching = false,
                    )
                )

                if (toolType == MotionEvent.TOOL_TYPE_FINGER)
                    eventListener?.onFingerTouchEvent(false)

                // Set this variable to `false` if the user stopped drawing without first letting go
                // of the "draw" button.
                fingerTouchEventsEnabled = false
            }

            MotionEvent.ACTION_MOVE -> eventListener?.onToolEvent(
                ToolEvent(
                    tool = convertTool(toolType),
                    x = event.x.roundToInt(),
                    y = event.y.roundToInt(),
                    pressure = findPressure(event)
                )
            )
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(backgroundPaint)

        for (i in 0..<ceil(width / gridSpacing).toInt()) {
            val x = gridOffset + i * gridSpacing
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }

        for (i in 0..<ceil(height / gridSpacing).toInt()) {
            val y = gridOffset + i * gridSpacing
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        }

        if (keypadPen && penX >= 0) {
            canvas.drawPath(strokes, strokePaint)
            val r = 6 * resources.displayMetrics.density
            if (penTool == ToolEvent.Tool.Rubber) {
                canvas.drawRect(penX - r, penY - r, penX + r, penY + r, penPaint)
            } else {
                canvas.drawCircle(penX, penY, r, penPaint)
            }
            if (penDown) canvas.drawCircle(penX, penY, r / 3, penPaint)
        }
    }

    interface EventListener {
        fun onToolEvent(event: ToolEvent)
        fun onFingerTouchEvent(touching: Boolean)
        fun onKeypadPenChanged() {}
    }

    companion object {
        const val TAG = "DrawingPadView"
    }
}
