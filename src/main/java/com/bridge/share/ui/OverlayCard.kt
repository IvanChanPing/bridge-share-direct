package com.bridge.share.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.bridge.share.R
import kotlin.math.abs
import kotlin.math.min

/**
 * 1:1 Android reimplementation of src/main/assets/card/card.html.
 * Every literal here is transcribed verbatim from card.html (line refs in comments).
 * CSS px -> dp ; CSS font px -> sp.
 */
class OverlayCard(
    ctx: Context,
    val onDismiss: () -> Unit,
    val onOpen: () -> Unit,
    val onAccept: () -> Unit = {},
    /** "receive" (default, unchanged) shows the Accept/Decline ack first; "send" skips the ack
     *  and starts straight in the "Sending to <peerName>" progress state — used to mirror the
     *  receive pop-up on the SENDER side (driven by the send sheet via liveProgress/etc.). */
    val role: String = "receive",
    val peerName: String = ""
) : FrameLayout(ctx) {

    // ---- unit helpers : CSS px -> dp, CSS font px -> sp ----
    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpI(v: Float): Int = (dp(v) + 0.5f).toInt()
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ---- colors (card.html) ----
    private val cCardBg = 0xD6242428.toInt()           // rgba(36,36,40,.84)  L15
    private val cCardBorder = 0x24FFFFFF                // rgba(255,255,255,.14) L15
    private val cTrack = 0x38FFFFFF                     // rgba(255,255,255,.22) L23/L34
    private val cArc = 0xFFFFFFFF.toInt()               // #fff  L24/L35
    private val cTitle = 0xFFFFFFFF.toInt()             // #fff  L28
    private val cSub = 0xFF9A9AA0.toInt()               // #9a9aa0 L29
    private val cThumbBg = 0xFF3A3A3D.toInt()           // #3a3a3d L31
    private val cStopSquare = 0xFF00A1FF.toInt()        // #00A1FF L36
    private val cBtnGrey = 0x29FFFFFF                   // rgba(255,255,255,.16) L40
    private val cBtnBlue = 0xFF00A1FF.toInt()           // #00A1FF L40
    private val cBtnText = 0xFFFFFFFF.toInt()           // #fff  L40
    private val cGreenCheck = 0xFF00BD13.toInt()        // #00BD13 L108

    // ---- cubic-bezier interpolators (exact, card.html) ----
    private val easeDescend = PathInterpolator(.25f, .9f, .3f, 1f)     // EASE L161
    private val easeMorphBounce = PathInterpolator(.2f, 1.2f, .4f, 1f) // sp L172
    private val easeBtnBounce = PathInterpolator(.25f, 1.7f, .45f, 1f) // L177/L231
    private val easeHeight = PathInterpolator(.2f, 1.3f, .45f, 1f)     // L132 / b L204/L225
    private val easePop = PathInterpolator(.3f, 1f, .4f, 1f)           // L134
    private val easeNewBtns = PathInterpolator(.3f, 1.3f, .45f, 1f)    // L136
    private val easeTransform = PathInterpolator(.3f, .7f, .2f, 1f)    // e L204/L225
    private val easeFade = PathInterpolator(.5f, 0f, .8f, .4f)         // L191
    private val easeSideways = PathInterpolator(.4f, 0f, .7f, 1f)      // L238
    private val easeSidePill = PathInterpolator(.2f, 1.4f, .45f, 1f)   // L245
    private val easeSnap = PathInterpolator(.2f, 1.25f, .4f, 1f)       // L277
    private val easeIn = PathInterpolator(0.42f, 0f, 1f, 1f)           // CSS "ease"

    // ---- entrance / pill constants (card.html L160 / L200 / L255) ----
    private val PILL_W = 64f   // L160
    private val PILL_H = 32f   // L160
    private val PILL_R = 16f   // L160
    private val UP = -40f      // L160
    private val PILL_UP = -22f // L200
    private val UP_MAX = 88f   // L255

    // ---- card-size constants ----
    private val CARD_RADIUS = 26f   // L15
    private val CARD_PAD = 14f       // L15
    private val PILL_CARD_W = 150f   // L16
    private val PILL_CARD_H = 34f    // L16
    private val PILL_CARD_R = 17f    // L16
    private val CARD_SIDE_MARGIN = 20f // L14/15 : calc(100vw - 40px) => 20 each side

    // Delay between the Accept-tap collapse animation and launching the foreground hop, so the two
    // don't contend on the main thread (the collapse takes ~300ms). Tune if it still feels coupled.
    private val ACCEPT_HOP_DELAY_MS = 320L

    // ---- state ----
    private var state = "ack"            // L61
    private var progress = 0             // L61
    private var collapsed = false        // L61
    private var expandLock = false       // L61
    private var progTimer: Runnable? = null

    private val card: CardView
    private val cardBg = GradientDrawable()

    init {
        // #stage : flex, align-items:flex-start, justify-content:center, padding-top:44px  (L13)
        setBackgroundColor(Color.TRANSPARENT)
        cardBg.setColor(cCardBg)
        cardBg.cornerRadius = dp(CARD_RADIUS)
        cardBg.setStroke(dpI(1f), cCardBorder) // border:1px  L15

        card = CardView(ctx)
        card.background = cardBg
        card.clipToOutline = true
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = dpI(44f) // padding-top:44px L13
        addView(card, lp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (role == "send") {
            // SENDER: no Accept gate. Start in the "Sending…" progress card, animate it in, then
            // tuck up to the island pill (mirrors the receiver right after Accept). Live %, the
            // final "Sent" / failed state are pushed in by the send sheet via liveProgress/etc.
            progress = 0
            progressContent()
            animateIn()
            postDelayed({
                if (!collapsed && state == "progress")
                    collapseToPill { progressContent(); setPillProgress(progress) }
            }, 1200)
        } else {
            ackContent()    // L280
            animateIn()     // L281
        }
    }

    // ---- role-aware labels (receiver shows "received / From X"; sender "sent / To X") ----
    private fun peerLabel(): String = if (peerName.isNotBlank()) peerName else "OnePlus 15R"
    private fun progressTitle(): String =
        if (role == "send") "$progress% sent" else "$progress% received"
    private fun peerLine(): String = (if (role == "send") "To " else "From ") + peerLabel()

    // full width of card = parent width - 40dp ; computed lazily once measured
    private fun fullCardWidthPx(): Int {
        val pw = if (width > 0) width else resources.displayMetrics.widthPixels
        return pw - dpI(CARD_SIDE_MARGIN * 2)
    }

    /**
     * Resting vertical offset (px, NEGATIVE) that lifts the COLLAPSED pill from its laid
     * position (the card's 44dp topMargin) up so its vertical centre sits over the camera
     * punch-hole. Read from the real DisplayCutout so it lands on THIS device's camera rather
     * than card.html's hardcoded -22 (which left the pill down at title-bar height).
     * Clamped so the pill never rises above the overlay window's own top edge (clipping).
     */
    private fun pillRestPx(): Float {
        val laidPillCenter = dp(44f) + dp(PILL_CARD_H) / 2f   // px from window top, no translation
        var cutoutCenter = dp(18f)                            // fallback ~ a typical punch-hole centre
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cut = rootWindowInsets?.displayCutout
                if (cut != null) {
                    val top = cut.boundingRectTop
                    cutoutCenter = when {
                        !top.isEmpty -> top.exactCenterY()
                        cut.safeInsetTop > 0 -> cut.safeInsetTop / 2f
                        else -> cutoutCenter
                    }
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val st = rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
                if (st > 0) cutoutCenter = st / 2f
            }
        } catch (t: Throwable) { /* keep fallback */ }
        // floor at -44dp so the card's top never goes above the window top (would clip the pill)
        return (cutoutCenter - laidPillCenter).coerceAtLeast(-dp(44f))
    }

    // =====================================================================
    //  CardView : holds #inner (LinearLayout) and #pill (the pill row)
    //  Animates its own width/height/borderRadius/translation/scale/alpha.
    // =====================================================================
    inner class CardView(ctx: Context) : FrameLayout(ctx) {
        val inner = LinearLayout(ctx)
        val pill = LinearLayout(ctx)

        // overrides for explicit animated size (-1 = wrap)
        var animW = -1
        var animH = -1

        init {
            pivotXTop() // transform-origin:top center L15
            inner.orientation = LinearLayout.VERTICAL
            pill.orientation = LinearLayout.HORIZONTAL
            pill.gravity = Gravity.CENTER_VERTICAL
            pill.visibility = View.GONE
            val pad = dpI(CARD_PAD)
            inner.setPadding(pad, pad, pad, pad)
            addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(pill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        private fun pivotXTop() {
            post {
                pivotX = measuredWidth / 2f
                pivotY = 0f
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var wms = widthMeasureSpec
            var hms = heightMeasureSpec
            if (animW >= 0) wms = MeasureSpec.makeMeasureSpec(animW, MeasureSpec.EXACTLY)
            if (animH >= 0) hms = MeasureSpec.makeMeasureSpec(animH, MeasureSpec.EXACTLY)
            super.onMeasure(wms, hms)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w / 2f
            pivotY = 0f
        }

        fun setAnimWidth(px: Int) { animW = px; requestLayout() }
        fun setAnimHeight(px: Int) { animH = px; requestLayout() }
        fun clearAnimWidth() { animW = -1; requestLayout() }
        fun clearAnimHeight() { animH = -1; requestLayout() }
        fun setRadius(r: Float) { cardBg.cornerRadius = r; invalidate() }

        /** measure natural (wrap) full-card size at the fixed full width */
        fun measureFull(): Pair<Int, Int> {
            val fw = fullCardWidthPx()
            val wms = MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY)
            val hms = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val savedW = animW; val savedH = animH
            animW = -1; animH = -1
            measure(wms, hms)
            val fh = measuredHeight
            animW = savedW; animH = savedH
            return fw to fh
        }
    }

    // =====================================================================
    //  Content builders (rows / states)
    // =====================================================================

    /** .row : gap 12, marginBottom 12, logo 40, title 17sp/700, sub 13sp, right 46  (L25-31) */
    private fun rowView(title: String, sub: String, rightNode: View?): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        (row.layoutParams as? ViewGroup.MarginLayoutParams)

        // logo 40x40 (L26) -> logo_a vector
        val logo = ImageView(context)
        logo.setImageResource(R.drawable.logo_a)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        val logoLp = LinearLayout.LayoutParams(dpI(40f), dpI(40f))
        row.addView(logo, logoLp)

        // text column : flex:1 (L27)
        val txt = LinearLayout(context)
        txt.orientation = LinearLayout.VERTICAL
        val txtLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        txtLp.leftMargin = dpI(12f)   // gap:12 (L25)
        txtLp.rightMargin = dpI(12f)  // gap:12 before right slot

        val t = TextView(context)
        t.text = title
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)   // 17px (L28)
        t.setTypeface(Typeface.DEFAULT_BOLD)             // font-weight:700
        t.setTextColor(cTitle)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END           // ellipsis (L28)
        t.isSingleLine = true
        val tLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tLp.bottomMargin = dpI(2f)                        // margin 0 0 2px (L28)
        txt.addView(t, tLp)

        val s = TextView(context)
        s.text = sub
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)   // 13px (L29)
        s.setTextColor(cSub)
        s.maxLines = 2                                    // -webkit-line-clamp:2 (L29)
        s.ellipsize = TextUtils.TruncateAt.END
        s.setLineSpacing(0f, 1.25f)                       // line-height:1.25 (L29)
        txt.addView(s)

        row.addView(txt, txtLp)

        // right slot 46x46 (L30)
        val right = FrameLayout(context)
        val rLp = LinearLayout.LayoutParams(dpI(46f), dpI(46f))
        if (rightNode != null) {
            right.addView(
                rightNode,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        row.addView(right, rLp)

        // marginBottom:12 on the row itself
        val wrapLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wrapLp.bottomMargin = dpI(12f) // margin-bottom:12 (L25)
        row.layoutParams = wrapLp
        return row
    }

    /** two .btn : flex1, h42, radius21, 14sp/600, press scale 1.05 over 90ms (L37-41) */
    private fun btnsView(
        greyLabel: String, greyFn: () -> Unit,
        blueLabel: String, blueFn: () -> Unit
    ): LinearLayout {
        val btns = LinearLayout(context)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(makeBtn(greyLabel, cBtnGrey, greyFn, marginRight = 6f))
        btns.addView(makeBtn(blueLabel, cBtnBlue, blueFn, marginLeft = 6f))
        return btns
    }

    private fun makeBtn(
        label: String, bgColor: Int, fn: () -> Unit,
        marginLeft: Float = 0f, marginRight: Float = 0f
    ): TextView {
        val b = TextView(context)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) // 14px (L39)
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) // weight 600 (L39)
        b.setTextColor(cBtnText)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(21f)                       // border-radius:21px (L38)
        bg.setColor(bgColor)
        b.background = bg
        val lp = LinearLayout.LayoutParams(0, dpI(42f), 1f) // flex:1 ; height:42 (L38)
        // gap:12 between buttons (L37) -> 6+6
        lp.leftMargin = dpI(marginLeft)
        lp.rightMargin = dpI(marginRight)
        b.layoutParams = lp
        b.isClickable = true
        b.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    // :active transform:scale(1.05) transition .09s ease (L39/L41)
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(90)
                        .setInterpolator(easeIn).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90)
                        .setInterpolator(easeIn).start()
                    if (ev.action == MotionEvent.ACTION_UP &&
                        ev.x >= 0 && ev.x <= v.width && ev.y >= 0 && ev.y <= v.height
                    ) {
                        fn()
                    }
                }
            }
            true
        }
        return b
    }

    private fun clearInner() {
        card.inner.removeAllViews()
    }

    /** ackContent (L73-79) */
    private fun ackContent() {
        state = "ack"
        com.bridge.share.diag.DiagLog.d("OverlayCard", "ackContent (Accept/Decline prompt shown)")
        clearInner()
        val thumb = ThumbView(context)
        card.inner.addView(
            rowView("OnePlus Share", "1 file from \"OnePlus 15R\"", thumb)
        )
        card.inner.addView(
            btnsView("Decline", { dismiss() }, "Accept", {
                // Two heavy transitions used to fire on the SAME frame as the Accept tap:
                //  - onAccept() launches the foreground hop (TransferAcceptActivity = a foreground
                //    activity -> window/focus/importance change, all on the main thread), and
                //  - beginLiveProgress() starts the collapse-to-pill ANIMATION.
                // They contended on the main thread, so the collapse janked. SPACE THEM OUT: collapse
                // immediately (instant tap feedback, smooth while the main thread is free), then launch
                // the hop after the collapse settles. The connect inside the hop absorbs the small delay.
                beginLiveProgress()
                postDelayed({ onAccept() }, ACCEPT_HOP_DELAY_MS)
            })
        )
    }

    /** progressContent (L80-87) : stop ring + row, no buttons */
    private fun progressContent() {
        state = "progress"
        clearInner()
        val stop = StopRingView(context)
        stop.setOnClickListener { cancelTransfer() } // L84
        card.inner.addView(
            rowView(progressTitle(), peerLine(), stop) // L85
        )
        stop.setProgress(progress) // setStopProgress(progress) L86
    }

    /** canceledContent (L92-93) */
    private fun canceledContent() {
        state = "canceled"
        clearInner()
        card.inner.addView(rowView(if (role == "send") "Send failed" else "Canceled", peerLine(), null))
    }

    /** completeContent (L97-103) : success lottie + row + [OK, View] */
    private fun completeContent() {
        state = "complete"
        clearInner()
        val ring = LottieAnimationView(context)
        ring.setAnimation(R.raw.success)                 // lotties.success
        ring.repeatCount = 0                             // loop:false (L56)
        ring.playAnimation()                             // autoplay:true (L56)
        card.inner.addView(
            rowView(
                if (role == "send") "Transfer sent" else "Transfer completed",
                if (role == "send") "1 file sent." else "1 file received.",
                ring
            )
        )
        // The sender owns the files (nothing to "View") -> no buttons; the pop-up auto-dismisses.
        if (role != "send") {
            card.inner.addView(
                btnsView("OK", { dismiss() }, "View", { openView() })
            )
        }
    }

    // ---- pill content (setPill L104-113) ----
    private lateinit var pillRing: PillRingView
    private fun setPill() {
        card.pill.removeAllViews()
        // left glyph 22x22 logo_a (L19/L105)
        val glyph = ImageView(context)
        glyph.setImageResource(R.drawable.logo_a)
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        val gLp = LinearLayout.LayoutParams(dpI(22f), dpI(22f))
        card.pill.addView(glyph, gLp)

        // space-between : spacer flex:1
        val spacer = View(context)
        card.pill.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        // right 24x24 (L20)
        pillRing = PillRingView(context)
        pillRing.complete = (state == "complete") // green check vs progress ring (L107)
        val rLp = LinearLayout.LayoutParams(dpI(24f), dpI(24f))
        card.pill.addView(pillRing, rLp)

        // #pill padding 0 13px (L18)
        card.pill.setPadding(dpI(13f), 0, dpI(13f), 0)
        setPillProgress(progress)
    }

    private fun setPillProgress(p: Int) {
        if (::pillRing.isInitialized) pillRing.setProgress(p)
    }

    // =====================================================================
    //  progress timer (startProgress L141-145, refreshProgress L147-149, toComplete L151-156)
    // =====================================================================
    // ---- LIVE (engine-driven) progress: same visuals as startProgress but NO fake
    //      timer — real percentages arrive via liveProgress()/liveComplete()/liveCanceled().
    fun beginLiveProgress() {
        com.bridge.share.diag.DiagLog.d("OverlayCard", "beginLiveProgress (accepted -> collapse to pill)")
        stopTimer()
        progress = 0
        collapseToPill { progressContent(); setPillProgress(progress) }
    }

    fun liveProgress(p: Int) {
        com.bridge.share.diag.DiagLog.d("OverlayCard", "liveProgress " + p + "% collapsed=" + collapsed)
        progress = p.coerceIn(0, 100)
        if (state != "progress") progressContent()
        refreshProgress()
        setPillProgress(progress)
    }

    fun liveComplete() {
        com.bridge.share.diag.DiagLog.d("OverlayCard", "liveComplete")
        stopTimer()
        progress = 100
        refreshProgress()
        toComplete()
    }

    fun liveCanceled() {
        com.bridge.share.diag.DiagLog.d("OverlayCard", "liveCanceled")
        stopTimer()
        morphTo({ canceledContent() })
        postDelayed({ dismiss() }, 1100)
    }

    private fun startProgress() {
        progress = 0
        collapseToPill { progressContent(); setPillProgress(progress) } // L143
        stopTimer()
        val r = object : Runnable {
            override fun run() {
                progress += 2 // L145
                if (progress >= 100) {
                    progress = 100
                    stopTimer()
                    refreshProgress()
                    postDelayed({ toComplete() }, 450) // L145
                    return
                }
                refreshProgress()
                postDelayed(this, 90) // every 90ms (L145)
            }
        }
        progTimer = r
        postDelayed(r, 90)
    }

    private fun stopTimer() {
        progTimer?.let { removeCallbacks(it) }
        progTimer = null
    }

    private fun refreshProgress() {
        if (state != "progress") return // L147
        if (collapsed) {
            setPillProgress(progress)
        } else {
            // update title text + stop ring
            val row = card.inner.getChildAt(0) as? LinearLayout
            val txt = (row?.getChildAt(1) as? LinearLayout)
            val t = txt?.getChildAt(0) as? TextView
            t?.text = progressTitle()
            val rightSlot = row?.getChildAt(2) as? FrameLayout
            (rightSlot?.getChildAt(0) as? StopRingView)?.setProgress(progress)
        }
    }

    private fun toComplete() {
        if (collapsed) {
            state = "complete"
            setPill() // progress ring -> green check (L153)
            postDelayed({ expandFromPill { completeContent() } }, 700) // L154
        } else {
            morphTo({ completeContent() })
        }
    }

    private fun cancelTransfer() {
        if (expandLock) return // L95
        stopTimer()
        morphTo({ canceledContent() })
        postDelayed({ dismiss() }, 1100) // L96
    }

    private fun openView() {
        onOpen() // L195 (AndroidCard.openView)
    }

    // =====================================================================
    //  ANIMATIONS
    // =====================================================================

    /** generic width animator */
    private fun animW(from: Int, to: Int, dur: Long, delay: Long, interp: android.view.animation.Interpolator) {
        val a = ValueAnimator.ofInt(from, to)
        a.duration = dur
        a.startDelay = delay
        a.interpolator = interp
        a.addUpdateListener { card.setAnimWidth(it.animatedValue as Int) }
        a.start()
    }

    private fun animH(from: Int, to: Int, dur: Long, delay: Long, interp: android.view.animation.Interpolator) {
        val a = ValueAnimator.ofInt(from, to)
        a.duration = dur
        a.startDelay = delay
        a.interpolator = interp
        a.addUpdateListener { card.setAnimHeight(it.animatedValue as Int) }
        a.start()
    }

    private fun animRadius(from: Float, to: Float, dur: Long, delay: Long, interp: android.view.animation.Interpolator) {
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = dur
        a.startDelay = delay
        a.interpolator = interp
        a.addUpdateListener { card.setRadius(it.animatedValue as Float) }
        a.start()
    }

    /** animateIn (L162-181) */
    private fun animateIn() {
        card.post {
            val (fw, fh) = card.measureFull()

            val bts = findBtns()
            bts?.let {
                it.pivotX = it.width / 2f; it.pivotY = it.height / 2f
                it.scaleX = .72f; it.scaleY = .72f // bigger bounce L164
            }
            card.inner.alpha = 0f // L165

            // initial pill geometry L167
            card.setAnimWidth(dpI(PILL_W))
            card.setAnimHeight(dpI(PILL_H))
            card.setRadius(dp(PILL_R))
            card.translationY = dp(UP)
            card.alpha = 0f

            card.post {
                // 1) show pill : opacity .075s ease  (L170)
                card.animate().alpha(1f).setDuration(75).setInterpolator(easeIn).start()
                // 2) after 90ms : descend + morph (L171-179)
                postDelayed({
                    // descend transform .375s EASE (L174)
                    card.animate().translationY(0f).setDuration(375)
                        .setInterpolator(easeDescend).start()
                    // height .315s sp .105s ; width .39s sp .105s ; radius .39s sp .105s (L174)
                    animH(dpI(PILL_H), fh, 315, 105, easeMorphBounce)
                    animW(dpI(PILL_W), fw, 390, 105, easeMorphBounce)
                    animRadius(dp(PILL_R), dp(CARD_RADIUS), 390, 105, easeMorphBounce)
                    // inner opacity .315s ease .135s (L176)
                    card.inner.animate().alpha(1f).setStartDelay(135)
                        .setDuration(315).setInterpolator(easeIn).start()
                    // buttons scale->1 .44s cubic-bezier(.25,1.7,.45,1) .16s (L177)
                    bts?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(160)
                        ?.setDuration(440)?.setInterpolator(easeBtnBounce)?.start()
                    // cleanup 560ms (L178)
                    postDelayed({
                        card.setAnimWidth(fullCardWidthPx()) // keep fixed full width; no shrink-to-content jump
                        card.clearAnimHeight()
                        bts?.scaleX = 1f; bts?.scaleY = 1f
                    }, 560)
                }, 90)
            }
        }
    }

    /** morphTo (L121-140) */
    private fun morphTo(build: () -> Unit, done: (() -> Unit)? = null) {
        val h0 = card.height
        val oldBtns = findBtns()
        oldBtns?.let {
            it.pivotX = it.width / 2f; it.pivotY = it.height / 2f
            // transform .105s ease, opacity .105s ease -> scale .86 + fade (L124)
            it.animate().scaleX(.86f).scaleY(.86f).alpha(0f)
                .setDuration(105).setInterpolator(easeIn).start()
        }
        // inner opacity->0 .098s (L125)
        card.inner.animate().alpha(0f).setDuration(98).setInterpolator(easeIn).start()

        postDelayed({
            build(); done?.invoke()
            card.inner.alpha = 0f
            val (_, fh) = card.measureFull()
            val h1 = fh
            val grow = h1 >= h0 // L128
            val newBtns = findBtns()
            newBtns?.let {
                it.scaleX = .92f; it.scaleY = .92f // new buttons pop in (L129)
            }
            card.setAnimHeight(h0) // L130
            card.post {
                // card height h0->h1 .27s cubic-bezier(.2,1.3,.45,1) (L132-133)
                animH(h0, h1, 270, 0, easeHeight)
                // scale-pop keyframe cardPop / cardPopIn .315s cubic-bezier(.3,1,.4,1) (L134)
                cardPop(grow)
                // inner opacity .195s ease .038s (L135)
                card.inner.animate().alpha(1f).setStartDelay(38)
                    .setDuration(195).setInterpolator(easeIn).start()
                // new buttons scale .92->1 .24s cubic-bezier(.3,1.3,.45,1) .045s (L136)
                newBtns?.let {
                    it.pivotX = it.width / 2f; it.pivotY = it.height / 2f
                    it.animate().scaleX(1f).scaleY(1f).setStartDelay(45)
                        .setDuration(240).setInterpolator(easeNewBtns).start()
                }
                // cleanup 315ms : height auto, animation '' (L137)
                postDelayed({ card.clearAnimHeight() }, 315)
            }
        }, 105) // L139
    }

    /** cardPop / cardPopIn keyframe .315s (L43-44 / L134) : 0%1 -> 42%(1.04 or .965) -> 100%1 */
    private fun cardPop(grow: Boolean) {
        val peak = if (grow) 1.04f else .965f
        card.pivotX = card.width / 2f
        card.pivotY = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 315
        a.interpolator = easePop
        a.addUpdateListener {
            val f = it.animatedFraction
            val s = if (f <= 0.42f) {
                1f + (peak - 1f) * (f / 0.42f)
            } else {
                peak + (1f - peak) * ((f - 0.42f) / 0.58f)
            }
            card.scaleX = s
            card.scaleY = s
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                card.scaleX = 1f; card.scaleY = 1f
            }
        })
        a.start()
    }

    /** collapseToPill (L201-212) */
    private fun collapseToPill(after: (() -> Unit)? = null) {
        if (collapsed) return
        collapsed = true
        setPill()
        card.alpha = 1f
        val fw = card.width
        val fh = card.height
        com.bridge.share.diag.DiagLog.d("OverlayCard", "collapseToPill from " + fw + "x" + fh + "px -> pill " + dpI(PILL_CARD_W) + "x" + dpI(PILL_CARD_H))
        card.setAnimWidth(fw)
        card.setAnimHeight(fh)
        card.post {
            // width/height/radius .27s b ; transform .24s e -> translateY(PILL_UP) (L206)
            card.inner.visibility = View.GONE  // #card.pill #inner{display:none}
            card.pill.visibility = View.VISIBLE
            after?.invoke()
            animW(fw, dpI(PILL_CARD_W), 270, 0, easeHeight)
            animH(fh, dpI(PILL_CARD_H), 270, 0, easeHeight)
            animRadius(cardBg.cornerRadius, dp(PILL_CARD_R), 270, 0, easeHeight)
            card.animate().translationY(pillRestPx()).setDuration(240)
                .setInterpolator(easeTransform).start() // L209 (pill rides up over the camera)
            postDelayed({
                // rest on the pill class size (L210)
                card.setAnimWidth(dpI(PILL_CARD_W))
                card.setAnimHeight(dpI(PILL_CARD_H))
            }, 300)
        }
    }

    /** expandFromPill (L213-234) */
    private fun expandFromPill(after: (() -> Unit)? = null) {
        if (!collapsed) return
        collapsed = false
        com.bridge.share.diag.DiagLog.d("OverlayCard", "expandFromPill (pill -> full card)")
        expandLock = true
        postDelayed({ expandLock = false }, 450) // L214

        val sw = card.width // current pill size (L216)
        val sh = card.height

        card.pill.visibility = View.GONE
        card.inner.visibility = View.VISIBLE // remove pill (L217)
        after?.invoke() // build target content (L218)
        refreshProgress() // L219

        card.post {
            val (tw, th) = card.measureFull() // natural full-card size (L220)

            // set to pill size + radius 17 + translateY(PILL_UP) (L221)
            card.setAnimWidth(sw)
            card.setAnimHeight(sh)
            card.setRadius(dp(PILL_CARD_R))
            card.translationY = pillRestPx()

            val rowEl = card.inner.getChildAt(0)
            rowEl?.let {
                it.pivotX = it.width / 2f; it.pivotY = it.height / 2f
                it.scaleX = .72f; it.scaleY = .72f // only top row grows (L222)
            }
            val bts = findBtns()
            bts?.let {
                it.pivotX = it.width / 2f; it.pivotY = it.height / 2f
                it.scaleX = .72f; it.scaleY = .72f // L223
            }
            card.inner.alpha = 0f // L224

            card.post {
                // width/height/radius .3s b ; transform .27s e (L227-228)
                animW(sw, tw, 300, 0, easeHeight)
                animH(sh, th, 300, 0, easeHeight)
                animRadius(dp(PILL_CARD_R), dp(CARD_RADIUS), 300, 0, easeHeight)
                card.animate().translationY(0f).setDuration(270)
                    .setInterpolator(easeTransform).start()
                // inner opacity .195s ease .045s (L229)
                card.inner.animate().alpha(1f).setStartDelay(45)
                    .setDuration(195).setInterpolator(easeIn).start()
                // top row scale .72->1 .33s b .02s (L230)
                rowEl?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(20)
                    ?.setDuration(330)?.setInterpolator(easeHeight)?.start()
                // buttons scale .72->1 .42s cubic-bezier(.25,1.7,.45,1) .05s (L231)
                bts?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(50)
                    ?.setDuration(420)?.setInterpolator(easeBtnBounce)?.start()
                // cleanup 340ms (L232)
                postDelayed({
                    card.setAnimWidth(fullCardWidthPx()) // card width stays fixed (card.html: calc(100vw-40px)); no shrink-to-content jump
                    card.clearAnimHeight()
                    rowEl?.scaleX = 1f; rowEl?.scaleY = 1f
                    bts?.scaleX = 1f; bts?.scaleY = 1f
                }, 340)
            }
        }
    }

    /** collapseSideways (L236-249) */
    private fun collapseSideways(dx: Float) {
        if (collapsed) return
        collapsed = true
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        val off = (if (dx < 0) -1 else 1) * (w + dpI(80f)) // window.innerWidth+80 (L237)
        // translateX .22s cubic-bezier(.4,0,.7,1) + opacity .2s ease (L238)
        card.animate().translationX(off.toFloat()).alpha(0f)
            .setDuration(220).setInterpolator(easeSideways).start()
        postDelayed({
            setPill()
            card.inner.visibility = View.GONE
            card.pill.visibility = View.VISIBLE
            card.setAnimWidth(dpI(PILL_CARD_W))
            card.setAnimHeight(dpI(PILL_CARD_H))
            card.setRadius(dp(PILL_CARD_R))
            card.translationX = 0f
            card.translationY = pillRestPx()
            card.scaleX = .85f; card.scaleY = .85f // L242
            card.pivotX = card.width / 2f; card.pivotY = 0f
            card.post {
                // transform .28s cubic-bezier(.2,1.4,.45,1) + opacity .2s ease (L245)
                card.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(280).setInterpolator(easeSidePill).start()
            }
        }, 220) // L240
    }

    /** fadePillOut (L188-194) */
    private fun fadePillOut() {
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f // transform-origin:center (L190)
        // transform .2s cubic-bezier(.5,0,.8,.4), opacity .2s ease .03s (L191)
        card.animate().translationY(pillRestPx()).scaleX(.15f).scaleY(.15f)
            .setDuration(200).setInterpolator(easeFade).start()
        card.animate().alpha(0f).setStartDelay(30).setDuration(200)
            .setInterpolator(easeIn).start()
        postDelayed({ onDismiss() }, 230) // L193
    }

    /** dismiss (L183-187) */
    private fun dismiss() {
        com.bridge.share.diag.DiagLog.d("OverlayCard", "dismiss collapsed=" + collapsed)
        stopTimer()
        if (!collapsed) {
            collapseToPill()
            postDelayed({ fadePillOut() }, 320) // L185
            return
        }
        fadePillOut()
    }

    private fun findBtns(): LinearLayout? {
        // the .btns row is a horizontal LinearLayout that is NOT the .row (which is also horizontal).
        for (i in 0 until card.inner.childCount) {
            val c = card.inner.getChildAt(i)
            if (c is LinearLayout && c.orientation == LinearLayout.HORIZONTAL) {
                // .row has gravity CENTER_VERTICAL and 3 children incl an ImageView logo;
                // .btns children are all TextView buttons.
                if (c.childCount == 2 && c.getChildAt(0) is TextView && c.getChildAt(1) is TextView) {
                    return c
                }
            }
        }
        return null
    }

    // =====================================================================
    //  DRAG handling (L255-278)
    // =====================================================================
    private var sy = Float.NaN
    private var sx = Float.NaN
    private var dragActive = false
    private var dragX = 0f
    private var dragY = 0f
    private var dragDx = 0f
    private var dragDy = 0f
    private var dragAxis: String? = null

    init {
        card.setOnTouchListener { _, ev -> onCardTouch(ev) }
    }

    private fun onCardTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (collapsed) { // L257
                    sy = ev.rawY; sx = ev.rawX
                    return true
                }
                dragActive = true
                dragX = ev.rawX; dragY = ev.rawY
                dragDx = 0f; dragDy = 0f; dragAxis = null
                // card.style.transition='none' (L258)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragActive) return collapsed
                dragDx = ev.rawX - dragX
                dragDy = ev.rawY - dragY
                if (dragAxis == null) { // L262 : lock to axis after 8dp
                    val thr = dp(8f)
                    if (abs(dragDx) > thr || abs(dragDy) > thr) {
                        dragAxis = if (abs(dragDx) > abs(dragDy)) "x" else "y"
                    } else return true
                }
                if (dragAxis == "x") {
                    card.translationX = dragDx // L264
                    val w = if (width > 0) width else resources.displayMetrics.widthPixels
                    // opacity = 1 - min(.35, |dx| / (width*0.5)) (L265)
                    card.alpha = (1f - min(.35f, abs(dragDx) / (w * 0.5f)))
                } else {
                    if (dragDy <= -dp(UP_MAX)) { // L267 : dy<=-88dp -> pill
                        dragActive = false
                        collapseToPill()
                        return true
                    }
                    // up moves 1:1, down resists *0.3 (L268)
                    card.translationY = if (dragDy < 0) dragDy else dragDy * 0.3f
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (collapsed) { // L272 : tap-to-expand if |Δ|<16dp
                    if (!sy.isNaN()) {
                        val c16 = dp(16f)
                        if (abs(ev.rawY - sy) < c16 && abs(ev.rawX - sx) < c16) {
                            expandFromPill()
                        }
                    }
                    sy = Float.NaN; sx = Float.NaN
                    return true
                }
                if (!dragActive) return true
                val ax = dragAxis; val dx = dragDx; val dy = dragDy
                dragActive = false
                if (ax == null) { // never crossed threshold = a tap (L274)
                    card.translationX = 0f; card.translationY = 0f; card.alpha = 1f
                    return true
                }
                if (ax == "x" && abs(dx) > dp(90f)) { // L275 : fling sideways
                    collapseSideways(dx)
                } else if (ax == "y" && dy < -dp(50f)) { // L276 : swipe up -> pill
                    collapseToPill()
                } else { // snap back transform .26s cubic-bezier(.2,1.25,.4,1) (L277)
                    card.animate().translationY(0f).translationX(0f)
                        .setDuration(260).setInterpolator(easeSnap).start()
                    card.animate().alpha(1f).setDuration(200).setInterpolator(easeIn).start()
                }
                return true
            }
        }
        return true
    }

    // =====================================================================
    //  Custom canvas views : thumb, stop ring, pill ring (+green check)
    // =====================================================================

    /** .thumb 46x46 radius 11 bg #3a3a3d ; preview_image (L31/L74) */
    inner class ThumbView(ctx: Context) : ImageView(ctx) {
        private val clip = Path()
        private val r = dp(11f)
        init {
            scaleType = ScaleType.CENTER_CROP // object-fit:cover
            setImageResource(R.drawable.preview_image)
            setBackgroundColor(cThumbBg)
        }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            clip.reset()
            clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        }
        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipPath(clip)
            super.draw(canvas)
            canvas.restore()
        }
    }

    /** .stop : ring r=21.5 sw3 + center square 16 radius4 #00A1FF (L32-36/L83) */
    inner class StopRingView(ctx: Context) : View(ctx) {
        // drawnP is the animated value; target jumps, drawnP tweens to it over .12s
        // linear (card.html L35: transition:stroke-dashoffset .12s linear).
        private var drawnP = 0f
        private var arcAnim: ValueAnimator? = null
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = cTrack; strokeWidth = dp(3f) // L34
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = cArc; strokeWidth = dp(3f) // L35
            strokeCap = Paint.Cap.ROUND // stroke-linecap:round
        }
        private val sq = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cStopSquare }
        private val arcRect = RectF() // reused per frame (no onDraw allocation)
        fun setProgress(v: Int) { // L91 (animated .12s linear)
            val target = v.coerceIn(0, 100).toFloat()
            arcAnim?.cancel()
            arcAnim = ValueAnimator.ofFloat(drawnP, target).apply {
                duration = 120                      // .12s (L35)
                interpolator = android.view.animation.LinearInterpolator() // linear (L35)
                addUpdateListener { drawnP = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val rr = dp(21.5f) // r=21.5 (L83)
            arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawCircle(cx, cy, rr, track)
            // arc starts 12 o'clock (rotate -90) sweep = 360*p/100 (L33/L91)
            c.drawArc(arcRect, -90f, 360f * drawnP / 100f, false, arc)
            // center square 16x16 radius 4 (L36)
            val hs = dp(16f) / 2f; val sr = dp(4f)
            c.drawRoundRect(cx - hs, cy - hs, cx + hs, cy + hs, sr, sr, sq)
        }
    }

    /** pill right 24x24 : progress ring r=10.5 sw2.5  OR  green check (L20-24/L107-110) */
    inner class PillRingView(ctx: Context) : View(ctx) {
        var complete = false
        // drawnP tweens to the target over .12s linear (card.html L24).
        private var drawnP = 0f
        private var arcAnim: ValueAnimator? = null
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = cTrack; strokeWidth = dp(2.5f) // L23
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = cArc; strokeWidth = dp(2.5f) // L24
            strokeCap = Paint.Cap.ROUND
        }
        private val checkRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = cGreenCheck; strokeWidth = dp(2.2f) // L108
        }
        private val checkPath = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = cGreenCheck; strokeWidth = dp(2.4f) // L108
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun setProgress(v: Int) { // animated .12s linear (L24)
            val target = v.coerceIn(0, 100).toFloat()
            arcAnim?.cancel()
            arcAnim = ValueAnimator.ofFloat(drawnP, target).apply {
                duration = 120                      // .12s (L24)
                interpolator = android.view.animation.LinearInterpolator() // linear (L24)
                addUpdateListener { drawnP = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        // Reused across frames (no per-frame allocation in onDraw → less GC jank).
        private val checkP = Path()
        private val arcRect = RectF()
        override fun onDraw(c: Canvas) {
            // viewBox 0 0 24 24, r=10.5 centered at 12,12 (L108/L110)
            val s = width / 24f // scale 24-unit box to view px
            val cx = 12f * s; val cy = 12f * s
            val rr = 10.5f * s
            if (complete) {
                c.drawCircle(cx, cy, rr, checkRing)
                // check path M7.5 12.4 L10.6 15.3 L16.5 8.7 (L108)
                checkP.reset()
                checkP.moveTo(7.5f * s, 12.4f * s)
                checkP.lineTo(10.6f * s, 15.3f * s)
                checkP.lineTo(16.5f * s, 8.7f * s)
                c.drawPath(checkP, checkPath)
            } else {
                arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                c.drawCircle(cx, cy, rr, track)
                c.drawArc(arcRect, -90f, 360f * drawnP / 100f, false, arc) // 12 o'clock (L22/L117)
            }
        }
    }
}
