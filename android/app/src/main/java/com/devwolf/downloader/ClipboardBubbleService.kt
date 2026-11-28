package com.devwolf.downloader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating clipboard bubble ("estilo Shazam"):
 * - round, draggable, snaps to the nearest screen edge and remembers its position;
 * - while dragging, a close target appears at the bottom; dropping the bubble on it hides the
 *   bubble until the app is opened again (the permanent switch lives in Ajustes);
 * - shown only while the Devwolf UI is in background (it is meant for use over other apps);
 * - tapping it opens Devwolf, which reads the clipboard once it has window focus (Android 10+
 *   only allows clipboard reads by the focused app).
 *
 * Runs as a low-priority foreground service so Android does not kill it in background.
 */
class ClipboardBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var closeTargetView: View? = null
    private var appInForeground = true
    private var snapAnimator: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                dismissForSession(showToast = false)
                return START_NOT_STICKY
            }
        }
        if (!isBubbleAllowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        refreshVisibility()
        return START_STICKY
    }

    override fun onDestroy() {
        snapAnimator?.cancel()
        removeCloseTarget()
        removeBubble()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Foreground notification --------------------------------------------------------------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bolha flutuante", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Mantém a bolha do Devwolf disponível sobre outros apps"
                    setShowBadge(false)
                },
            )
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismissIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ClipboardBubbleService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bolha do Devwolf ativa")
            .setContentText("Copie um link e toque na bolha para baixar.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Fechar bolha", dismissIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- Visibility ---------------------------------------------------------------------------

    fun onAppForegroundChanged(inForeground: Boolean) {
        appInForeground = inForeground
        refreshVisibility()
    }

    private fun refreshVisibility() {
        if (!isBubbleAllowed(this)) {
            stopSelf()
            return
        }
        if (appInForeground || dismissedForSession) removeBubble() else showBubble()
    }

    private fun dismissForSession(showToast: Boolean) {
        dismissedForSession = true
        removeCloseTarget()
        removeBubble()
        if (showToast) {
            Toast.makeText(
                this,
                "Bolha fechada. Ela volta quando você abrir o Devwolf (desative de vez em Ajustes).",
                Toast.LENGTH_LONG,
            ).show()
        }
        stopSelf()
    }

    // ---- Bubble view --------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubbleView != null) return
        val size = dp(BUBBLE_DP)
        val bubble = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#7C5CFF"), Color.parseColor("#5E43F3")),
            ).apply { shape = GradientDrawable.OVAL }
            elevation = dp(8).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            contentDescription = "Baixar link copiado com o Devwolf"
            addView(
                ImageView(this@ClipboardBubbleService).apply {
                    setImageResource(R.mipmap.ic_launcher_round)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                },
                FrameLayout.LayoutParams(size - dp(6), size - dp(6), Gravity.CENTER),
            )
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val screen = screenSize()
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val onRight = prefs.getBoolean(KEY_ON_RIGHT, true)
            x = if (onRight) screen.first - size - dp(EDGE_MARGIN_DP) else dp(EDGE_MARGIN_DP)
            y = prefs.getInt(KEY_Y, (screen.second * 0.35f).toInt()).coerceIn(dp(24), screen.second - size - dp(48))
        }
        bubble.setOnTouchListener(BubbleTouchListener(bubble, params))
        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
            bubbleParams = params
        } catch (_: Exception) {
            bubbleView = null
            bubbleParams = null
        }
    }

    private fun removeBubble() {
        snapAnimator?.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
    }

    private inner class BubbleTouchListener(
        private val view: View,
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@ClipboardBubbleService).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        showCloseTarget()
                    }
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                        highlightCloseTarget(isOverCloseTarget(params))
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (!dragging) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) onBubbleTapped()
                        return true
                    }
                    val overClose = isOverCloseTarget(params)
                    removeCloseTarget()
                    if (overClose) {
                        dismissForSession(showToast = true)
                    } else {
                        snapToEdge(view, params)
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun onBubbleTapped() {
        val tap = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_CLIPBOARD_BUBBLE_TAP
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { startActivity(tap) }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screen = screenSize()
        val size = dp(BUBBLE_DP)
        val onRight = params.x + size / 2 > screen.first / 2
        val targetX = if (onRight) screen.first - size - dp(EDGE_MARGIN_DP) else dp(EDGE_MARGIN_DP)
        params.y = params.y.coerceIn(dp(24), screen.second - size - dp(48))
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON_RIGHT, onRight)
            .putInt(KEY_Y, params.y)
            .apply()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (bubbleView === view) runCatching { windowManager.updateViewLayout(view, params) }
            }
            start()
        }
    }

    // ---- Close target -------------------------------------------------------------------------

    private fun showCloseTarget() {
        if (closeTargetView != null) return
        val size = dp(CLOSE_DP)
        val target = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1E1940"))
                setStroke(dp(2), Color.parseColor("#80FFFFFF"))
            }
            alpha = 0f
            contentDescription = "Solte aqui para fechar a bolha"
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(CLOSE_BOTTOM_DP)
        }
        runCatching {
            windowManager.addView(target, params)
            closeTargetView = target
            target.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun highlightCloseTarget(active: Boolean) {
        val target = closeTargetView ?: return
        val scale = if (active) 1.25f else 1f
        if (abs(target.scaleX - scale) > 0.01f) {
            target.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
        }
        (target.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (active) "#E6E5484D" else "#CC1E1940"),
        )
    }

    private fun removeCloseTarget() {
        closeTargetView?.let { runCatching { windowManager.removeView(it) } }
        closeTargetView = null
    }

    private fun isOverCloseTarget(params: WindowManager.LayoutParams): Boolean {
        if (closeTargetView == null) return false
        val screen = screenSize()
        val bubbleSize = dp(BUBBLE_DP)
        val closeSize = dp(CLOSE_DP)
        val bubbleCx = params.x + bubbleSize / 2f
        val bubbleCy = params.y + bubbleSize / 2f
        val closeCx = screen.first / 2f
        val closeCy = screen.second - dp(CLOSE_BOTTOM_DP) - closeSize / 2f
        return hypot(bubbleCx - closeCx, bubbleCy - closeCy) < closeSize * 1.1f
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_DISMISS = "com.devwolf.downloader.action.BUBBLE_DISMISS"
        const val FEATURE_PREFS = "devwolf_features"
        const val KEY_ENABLED = "clipboard_bubble_enabled"
        private const val PREFS = "devwolf_bubble"
        private const val KEY_ON_RIGHT = "on_right"
        private const val KEY_Y = "y"
        private const val CHANNEL_ID = "devwolf_bubble"
        private const val NOTIFICATION_ID = 4107
        private const val BUBBLE_DP = 58
        private const val CLOSE_DP = 64
        private const val CLOSE_BOTTOM_DP = 72
        private const val EDGE_MARGIN_DP = 6

        @Volatile
        private var instance: ClipboardBubbleService? = null

        /** Set when the user drops the bubble on the close target; reset when the app opens. */
        @Volatile
        private var dismissedForSession = false

        fun isEnabledInSettings(context: Context): Boolean =
            context.getSharedPreferences(FEATURE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun hasOverlayPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun isBubbleAllowed(context: Context): Boolean = isEnabledInSettings(context) && hasOverlayPermission(context)

        /** Called by [MainActivity.onStart]: the app is visible, so the bubble is hidden. */
        fun onAppVisible(context: Context) {
            dismissedForSession = false
            val running = instance
            if (running != null) {
                running.onAppForegroundChanged(true)
            } else if (isBubbleAllowed(context)) {
                start(context)
            }
        }

        /** Called by [MainActivity.onStop]: the app left the screen, so the bubble appears. */
        fun onAppHidden() {
            instance?.onAppForegroundChanged(false)
        }

        fun start(context: Context) {
            if (!isBubbleAllowed(context)) return
            val intent = Intent(context, ClipboardBubbleService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardBubbleService::class.java))
        }
    }
}
