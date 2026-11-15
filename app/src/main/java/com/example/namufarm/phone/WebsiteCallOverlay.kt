package com.example.namufarm.phone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A compact, movable window; touches outside it still belong to the phone app. */
internal class WebsiteCallOverlay(
    private val context: Context,
    private val onSend: () -> Unit,
    private val onClose: () -> Unit
) {
    private val manager = requireNotNull(context.getSystemService(WindowManager::class.java))
    private val green = Color.rgb(31, 105, 67)
    private val number = text(19f).apply { setTypeface(typeface, Typeface.BOLD) }
    private val status = text(15f)
    private val send = Button(context).apply {
        text = "문자 전송"
        textSize = 17f
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(green)
        setOnClickListener { onSend() }
    }
    private val title = text(16f).apply {
        text = "나무팜 · 홈페이지 안내"
        setTextColor(green)
        setTypeface(typeface, Typeface.BOLD)
        contentDescription = "나무팜 홈페이지 안내 창. 길게 끌어 위치를 옮길 수 있습니다."
    }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(190, 212, 198))
        }
        elevation = dp(8).toFloat()
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply {
                text = "×"
                textSize = 24f
                contentDescription = "이번 통화의 안내 창 닫기"
                setTextColor(Color.DKGRAY)
                backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(0, 0, 0, 0)
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(number, LinearLayout.LayoutParams(-1, -2))
        addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        })
        addView(send, LinearLayout.LayoutParams(-1, -2))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(body)
    }
    @Suppress("DEPRECATION")
    private val parameters = WindowManager.LayoutParams(
        width(), WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = dp(16)
        y = dp(120)
    }
    private var attached = false

    init {
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = parameters.x; startY = parameters.y
                    downX = event.rawX; downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    parameters.x = (startX + event.rawX - downX).toInt()
                        .coerceIn(0, (context.resources.displayMetrics.widthPixels - parameters.width).coerceAtLeast(0))
                    parameters.y = (startY + event.rawY - downY).toInt()
                        .coerceIn(dp(32), (context.resources.displayMetrics.heightPixels - parameters.height - dp(48)).coerceAtLeast(dp(32)))
                    if (attached) runCatching { manager.updateViewLayout(scroll, parameters) }
                    true
                }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    fun show(phone: String, description: String, canSend: Boolean, buttonLabel: String): Boolean {
        number.text = android.telephony.PhoneNumberUtils.formatNumber(phone, "KR") ?: phone
        status.text = description
        send.isEnabled = canSend
        send.alpha = if (canSend) 1f else 0.55f
        send.text = buttonLabel
        parameters.width = width()
        scroll.measure(View.MeasureSpec.makeMeasureSpec(parameters.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.heightPixels / 2, View.MeasureSpec.AT_MOST))
        parameters.height = scroll.measuredHeight
        parameters.y = parameters.y.coerceIn(dp(32),
            (context.resources.displayMetrics.heightPixels - parameters.height - dp(48)).coerceAtLeast(dp(32)))
        return runCatching {
            if (attached) manager.updateViewLayout(scroll, parameters)
            else { manager.addView(scroll, parameters); attached = true }
            true
        }.getOrDefault(false)
    }

    fun hide() {
        if (attached) runCatching { manager.removeView(scroll) }
        attached = false
    }

    private fun text(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(Color.rgb(35, 49, 41))
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun width() = minOf(dp(340), (context.resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(180)))
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
