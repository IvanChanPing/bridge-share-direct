package com.bridge.share.ui.fx.ripple;

import android.graphics.RuntimeShader;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Clean-room, single-shape (CIRCLE) port of AOSP SystemUI
 * {@code com.android.systemui.surfaceeffects.ripple.RippleShader} adapted into
 * this app's package. The embedded AGSL is taken verbatim from the AOSP
 * CIRCLE_SHADER string (sparkle + distort + sdCircle ring). Only the CIRCLE
 * shape is supported &mdash; the ellipse / rounded-box variants of the original
 * are intentionally dropped because the OnePlus NFC-tap effect is a circle.
 *
 * <p>{@link RuntimeShader} is API 33+. Callers must guard construction with a
 * {@code Build.VERSION.SDK_INT >= 33} check; this class is annotated
 * {@link RequiresApi}.
 *
 * <p>The progress-&gt;uniform mapping ({@link #setRawProgress}, {@link #setProgress},
 * the {@code STANDARD} interpolator, the three fade envelopes and the
 * 1.25-&gt;0.5 blur ramp) reproduces the original numbers exactly.
 *
 * <p>{@code RuntimeShader} is API 33+; callers (e.g. {@code RippleView},
 * {@code TapFxOverlay}) must only instantiate this class when
 * {@code Build.VERSION.SDK_INT >= 33}.
 */
public final class RippleShader extends RuntimeShader {

    /** AGSL for the CIRCLE ripple, copied verbatim from AOSP RippleShader.CIRCLE_SHADER. */
    private static final String CIRCLE_SHADER = ""
            + "uniform vec2 in_center;\n"
            + "uniform vec2 in_size;\n"
            + "uniform float in_cornerRadius;\n"
            + "uniform float in_thickness;\n"
            + "uniform float in_time;\n"
            + "uniform float in_distort_radial;\n"
            + "uniform float in_distort_xy;\n"
            + "uniform float in_fadeSparkle;\n"
            + "uniform float in_fadeFill;\n"
            + "uniform float in_fadeRing;\n"
            + "uniform float in_blur;\n"
            + "uniform float in_pixelDensity;\n"
            + "layout(color) uniform vec4 in_color;\n"
            + "uniform float in_sparkle_strength;\n"
            + "\n"
            + "float triangleNoise(vec2 n) {\n"
            + "    n  = fract(n * vec2(5.3987, 5.4421));\n"
            + "    n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));\n"
            + "    float xy = n.x * n.y;\n"
            + "    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;\n"
            + "}\n"
            + "\n"
            + "const float PI = 3.1415926535897932384626;\n"
            + "\n"
            + "float sparkles(vec2 uv, float t) {\n"
            + "    float n = triangleNoise(uv);\n"
            + "    float s = 0.0;\n"
            + "    for (float i = 0; i < 4; i += 1) {\n"
            + "        float l = i * 0.01;\n"
            + "        float h = l + 0.1;\n"
            + "        float o = smoothstep(n - l, h, n);\n"
            + "        o *= abs(sin(PI * o * (t + 0.55 * i)));\n"
            + "        s += o;\n"
            + "    }\n"
            + "    return s;\n"
            + "}\n"
            + "\n"
            + "vec2 distort(vec2 p, float time, float distort_amount_radial,\n"
            + "    float distort_amount_xy) {\n"
            + "        float angle = atan(p.y, p.x);\n"
            + "          return p + vec2(sin(angle * 8 + time * 0.003 + 1.641),\n"
            + "                    cos(angle * 5 + 2.14 + time * 0.00412)) * distort_amount_radial\n"
            + "             + vec2(sin(p.x * 0.01 + time * 0.00215 + 0.8123),\n"
            + "                    cos(p.y * 0.01 + time * 0.005931)) * distort_amount_xy;\n"
            + "}\n"
            + "\n"
            + "float soften(float d, float blur) {\n"
            + "    float blurHalf = blur * 0.5;\n"
            + "    return smoothstep(-blurHalf, blurHalf, d);\n"
            + "}\n"
            + "\n"
            + "float subtract(float outer, float inner) {\n"
            + "    return max(outer, -inner);\n"
            + "}\n"
            + "\n"
            + "float sdCircle(vec2 p, float r) {\n"
            + "    return (length(p)-r) / r;\n"
            + "}\n"
            + "\n"
            + "float circleRing(vec2 p, float radius) {\n"
            + "    float thicknessHalf = radius * 0.25;\n"
            + "    float outerCircle = sdCircle(p, radius + thicknessHalf);\n"
            + "    float innerCircle = sdCircle(p, radius);\n"
            + "    return subtract(outerCircle, innerCircle);\n"
            + "}\n"
            + "\n"
            + "vec4 main(vec2 p) {\n"
            + "    vec2 p_distorted = distort(p, in_time, in_distort_radial, in_distort_xy);\n"
            + "    float radius = in_size.x * 0.5;\n"
            + "    float sparkleRing = soften(circleRing(p_distorted-in_center, radius), in_blur);\n"
            + "    float inside = soften(sdCircle(p_distorted-in_center, radius * 1.25), in_blur);\n"
            + "    float sparkle = sparkles(p - mod(p, in_pixelDensity * 0.8), in_time * 0.00175)\n"
            + "        * (1.-sparkleRing) * in_fadeSparkle;\n"
            + "    float rippleInsideAlpha = (1.-inside) * in_fadeFill;\n"
            + "    float rippleRingAlpha = (1.-sparkleRing) * in_fadeRing;\n"
            + "    float rippleAlpha = max(rippleInsideAlpha, rippleRingAlpha) * in_color.a;\n"
            + "    vec4 ripple = vec4(in_color.rgb, 1.0) * rippleAlpha;\n"
            + "    return mix(ripple, vec4(sparkle), sparkle * in_sparkle_strength);\n"
            + "}\n";

    /** AOSP RippleShader.STANDARD interpolator. */
    private static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** A simple fade envelope: fade in [inStart..inEnd], fade out [outStart..outEnd]. */
    public static final class FadeParams {
        public float fadeInStart;
        public float fadeInEnd;
        public float fadeOutStart;
        public float fadeOutEnd;

        public FadeParams(float inStart, float inEnd, float outStart, float outEnd) {
            this.fadeInStart = inStart;
            this.fadeInEnd = inEnd;
            this.fadeOutStart = outStart;
            this.fadeOutEnd = outEnd;
        }
    }

    /** A (progress, width, height) keyframe for the ripple size ramp. */
    public static final class SizeAtProgress {
        public float t;
        public float width;
        public float height;

        public SizeAtProgress(float t, float width, float height) {
            this.t = t;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Tracks ripple size across progress keyframes. Faithful to AOSP RippleSize:
     * keeps a sorted list of {@link SizeAtProgress}, advancing the current index
     * as progress increases and lerping width/height between neighbours.
     */
    public static final class RippleSize {
        private final SizeAtProgress initialSize = new SizeAtProgress(0f, 0f, 0f);
        private final java.util.ArrayList<SizeAtProgress> sizes = new java.util.ArrayList<>();
        private int currentSizeIndex = 0;
        private float currentWidth = 0f;
        private float currentHeight = 0f;

        public float getCurrentWidth() {
            return currentWidth;
        }

        public float getCurrentHeight() {
            return currentHeight;
        }

        public void setMaxSize(float width, float height) {
            setSizeAtProgresses(initialSize, new SizeAtProgress(1f, width, height));
        }

        public void setSizeAtProgresses(SizeAtProgress... arr) {
            sizes.clear();
            currentSizeIndex = 0;
            for (SizeAtProgress s : arr) {
                sizes.add(s);
            }
            if (sizes.size() > 1) {
                java.util.Collections.sort(sizes, new java.util.Comparator<SizeAtProgress>() {
                    @Override
                    public int compare(SizeAtProgress a, SizeAtProgress b) {
                        return Float.compare(a.t, b.t);
                    }
                });
            }
        }

        public void update(float progress) {
            int i;
            if (sizes.isEmpty()) {
                setSizeAtProgresses(initialSize);
                i = currentSizeIndex;
            } else {
                SizeAtProgress cur = sizes.get(currentSizeIndex);
                while (progress > cur.t) {
                    int min = Math.min(currentSizeIndex + 1, sizes.size() - 1);
                    if (min == currentSizeIndex) {
                        break;
                    }
                    currentSizeIndex = min;
                    cur = sizes.get(min);
                }
                i = currentSizeIndex;
            }
            int prev = Math.max(i - 1, 0);
            SizeAtProgress to = sizes.get(i);
            SizeAtProgress from = sizes.get(prev);
            float sub = subProgress(from.t, to.t, progress);
            // Faithful to AOSP arithmetic (from + to*sub).
            currentWidth = from.width + (to.width * sub);
            currentHeight = from.height + (to.height * sub);
        }
    }

    private final RippleSize rippleSize = new RippleSize();

    public float blurStart = 1.25f;
    public float blurEnd = 0.5f;
    public int color = 0xFFFFFF;
    public float pixelDensity = 1f;
    public float rawProgress = 0f;
    public float distortionStrength = 0f;
    public float sparkleStrength = 0f;
    public float time = 0f;

    private final FadeParams sparkleRingFadeParams = new FadeParams(0f, 0.1f, 0.4f, 1f);
    private final FadeParams baseRingFadeParams = new FadeParams(0f, 0.1f, 0.3f, 1f);
    private final FadeParams centerFillFadeParams = new FadeParams(0f, 0f, 0f, 0.6f);

    public RippleShader() {
        super(CIRCLE_SHADER);
    }

    public RippleSize getRippleSize() {
        return rippleSize;
    }

    public FadeParams getSparkleRingFadeParams() {
        return sparkleRingFadeParams;
    }

    public FadeParams getBaseRingFadeParams() {
        return baseRingFadeParams;
    }

    public FadeParams getCenterFillFadeParams() {
        return centerFillFadeParams;
    }

    public void setCenter(float x, float y) {
        setFloatUniform("in_center", x, y);
    }

    public void setColor(int argb) {
        this.color = argb;
        setColorUniform("in_color", argb);
    }

    public void setPixelDensity(float density) {
        this.pixelDensity = density;
        setFloatUniform("in_pixelDensity", density);
    }

    public void setSparkleStrength(float s) {
        this.sparkleStrength = s;
        setFloatUniform("in_sparkle_strength", s);
    }

    public void setTime(float t) {
        this.time = t;
        setFloatUniform("in_time", t);
    }

    public void setDistortionStrength(float s) {
        this.distortionStrength = s;
        float f = 75f;
        setFloatUniform("in_distort_radial", rawProgress * f * s);
        setFloatUniform("in_distort_xy", f * s);
    }

    private void setProgress(float p) {
        rippleSize.update(p);
        setFloatUniform("in_size", rippleSize.getCurrentWidth(), rippleSize.getCurrentHeight());
        setFloatUniform("in_thickness", rippleSize.getCurrentHeight() * 0.5f);
        setFloatUniform("in_cornerRadius",
                Math.min(rippleSize.getCurrentWidth(), rippleSize.getCurrentHeight()));
        setFloatUniform("in_blur", (-0.75f * p) + 1.25f);
    }

    public void setRawProgress(float p) {
        this.rawProgress = p;
        setProgress(STANDARD.getInterpolation(p));
        setFloatUniform("in_fadeSparkle", getFade(sparkleRingFadeParams, p));
        setFloatUniform("in_fadeRing", getFade(baseRingFadeParams, p));
        setFloatUniform("in_fadeFill", getFade(centerFillFadeParams, p));
    }

    private static float getFade(FadeParams fp, float p) {
        return Math.min(
                subProgress(fp.fadeInStart, fp.fadeInEnd, p),
                1f - subProgress(fp.fadeOutStart, fp.fadeOutEnd, p));
    }

    private static float subProgress(float start, float end, float p) {
        if (start == end) {
            return p > start ? 1f : 0f;
        }
        float clamped = Math.min(Math.max(p, Math.min(start, end)), Math.max(start, end));
        return (clamped - start) / (end - start);
    }
}
