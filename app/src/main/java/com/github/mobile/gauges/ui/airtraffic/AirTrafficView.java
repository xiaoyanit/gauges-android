package com.github.mobile.gauges.ui.airtraffic;

import static android.graphics.Bitmap.createScaledBitmap;
import static java.lang.Math.PI;
import static java.lang.System.currentTimeMillis;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.github.mobile.gauges.R.color;
import com.github.mobile.gauges.R.drawable;
import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.AnimatorListenerAdapter;
import com.nineoldandroids.animation.ObjectAnimator;
import com.nineoldandroids.animation.ValueAnimator;
import com.nineoldandroids.animation.ValueAnimator.AnimatorUpdateListener;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * View to display an Air Traffic map
 */
public class AirTrafficView extends View {

    /**
     * The maximum numbers of hits to retain
     */
    private static final int MAX_HITS = 500;

    /**
     * Size scales the ring does through while being animated
     */
    private static final float[] RING_SIZES = new float[] { .2F, .3F, .4F, .5F, .6F };

    /**
     * Ring Animation
     */
    public class RingAnimation {

        private int state;

        private Hit hit;

        /**
         * Create animation for hit
         *
         * @param hit
         */
        public RingAnimation(final Hit hit) {
            this.hit = hit;
        }

        /**
         * @param state
         */
        public void setState(final int state) {
            this.state = state;
        }

        /**
         * @return size
         */
        public int getState() {
            return state;
        }

        /**
         * Draw ring on canvas
         *
         * @param canvas
         * @param location
         * @param paint
         */
        public void onDraw(final Canvas canvas, final PointF location, final Paint paint) {
            if (state >= RING_SIZES.length)
                return;

            int key = resourceProvider.getKey(hit.siteId);
            if (key == -1)
                return;

            getLocation(hit, location);

            Rect source = new Rect();
            RectF destination = new RectF();
            Bitmap ring = resourceProvider.getRing(key);
            int width = Math.round(innerRingWidth * RING_SIZES[state]);
            int height = Math.round(innerRingHeight * RING_SIZES[state]);
            destination.top = location.y - height / 2;
            destination.left = location.x - width / 2;
            destination.right = destination.left + width;
            destination.bottom = destination.top + height;
            source.right = ring.getWidth();
            source.bottom = ring.getHeight();
            int rename = mapPaint.getAlpha();
            try {
                mapPaint.setAlpha(Math.round(255F - (((float) state / RING_SIZES.length) * 255F)));
                canvas.drawBitmap(ring, source, destination, mapPaint);
            } finally {
                mapPaint.setAlpha(rename);
            }
        }
    }

    private static final String MAP_LABEL = "AirTraffic Live";

    /**
     * Divisor used to compute the scaling value
     * <p>
     * Constant taken from gaug.es site
     */
    private static final double SCALE_DIVISOR = 720.0;

    /**
     * Corrector used to adjust X position
     */
    private static final double X_CORRECTOR = 1.1;

    /**
     * Corrector used to adjust Y position
     */
    private static final double Y_CORRECTOR = 70.0;

    /**
     * Multiplier used to compute the scaling value
     * <p>
     * Constant taken from gaug.es site
     */
    private static final double SCALE_MULTIPLIER = 0.169;

    /**
     * Constant taken from gaug.es site
     */
    private static final double PIXELS_PER_LONGITUDE_DEGREE = 16.0 / 360.0;

    /**
     * Constant taken from gaug.es site
     */
    private static final double NEGATIVE_PIXELS_PER_LONGITUDE_RADIAN = -(16.0 / (2.0 * PI));

    /**
     * Constant taken from gaug.es site
     */
    private static final double BITMAP_ORIGIN = 16.0 / 2.0;

    private AirTrafficResourceProvider resourceProvider;

    /**
     * Scale value used based on map image dimensions
     */
    private double scale;

    /**
     * Correction value used to adjust scaled y position
     */
    private double yCorrector;

    /**
     * Correction value used to adjust scaled x position
     */
    private double xCorrector;

    private int pinHeight;

    private int pinWidth;

    private int innerRingHeight;

    private int innerRingWidth;

    private float mapLabelWidth;

    private final Collection<ObjectAnimator> rings = Collections.synchronizedSet(new HashSet<ObjectAnimator>());

    private double xMapScale;
    private double yMapScale;
    private Bitmap map, fittedMap;
    private Paint mapPaint;

    private final ConcurrentLinkedQueue<Hit> hits = new ConcurrentLinkedQueue<Hit>();

    /**
     * Constructor. Create objects used throughout the life of the View: the Paint and the animator
     *
     * @param context
     * @param attrs
     */
    public AirTrafficView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final Resources resources = getResources();
        map = BitmapFactory.decodeResource(resources, drawable.map);

        mapPaint = new Paint();
        mapPaint.setColor(resources.getColor(color.text));
    }

    /**
     * Set the height to use when drawing text on the map
     *
     * @param height
     * @return this view
     */
    public AirTrafficView setLabelHeight(final float height) {
        mapPaint.setTextSize(height);
        mapLabelWidth = mapPaint.measureText(MAP_LABEL);
        return this;
    }

    /**
     * Set resource provider
     *
     * @param provider
     * @return this view
     */
    public AirTrafficView setResourceProvider(final AirTrafficResourceProvider provider) {
        this.resourceProvider = provider;

        pinHeight = provider.getPinHeight() / 2;
        pinWidth = provider.getPinWidth() / 2;

        innerRingHeight = provider.getRingHeight();
        innerRingWidth = provider.getRingWidth();

        return this;
    }

    @Override
    protected void onSizeChanged(final int width, final int height, final int oldw, final int oldh) {
        super.onSizeChanged(width, height, oldw, oldh);

        xMapScale = (double) width / map.getWidth();
        yMapScale = (double) height / map.getHeight();

        double relativeWidth = map.getWidth() / SCALE_DIVISOR;
        scale = relativeWidth * SCALE_MULTIPLIER;

        xCorrector = X_CORRECTOR * relativeWidth;
        yCorrector = Y_CORRECTOR * relativeWidth;

        fittedMap = createScaledBitmap(map, width, height, true);

        hits.clear();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (fittedMap != null)
            canvas.drawBitmap(fittedMap, 0, 0, mapPaint);

        if (mapLabelWidth > 0)
            canvas.drawText(MAP_LABEL, fittedMap.getWidth() / 2 - mapLabelWidth / 2,
                    fittedMap.getHeight() - mapPaint.getTextSize(), mapPaint);

        long now = currentTimeMillis();
        PointF point = new PointF();
        for (Hit hit : hits)
            draw(hit, canvas, getLocation(hit, point), now);

        for (ObjectAnimator ring : rings)
            ((RingAnimation) ring.getTarget()).onDraw(canvas, point, mapPaint);
    }

    /**
     * Get location of hit on map
     *
     * @param hit
     * @param point
     * @return point
     */
    protected PointF getLocation(final Hit hit, final PointF point) {
        // Determine the x and y positions to draw the hit at.
        // This code was taken from the gaug.es site
        double globalX = (BITMAP_ORIGIN + hit.lon * PIXELS_PER_LONGITUDE_DEGREE) * 256.0;
        double e = Math.sin(hit.lat * (PI / 180.0));
        e = Math.max(Math.min(e, 0.9999), -0.9999);
        double globalY = (BITMAP_ORIGIN + 0.5 * Math.log((1.0 + e) / (1.0 - e)) * NEGATIVE_PIXELS_PER_LONGITUDE_RADIAN) * 256.0;

        float x = (float) ((globalX * scale) - xCorrector);
        float y = (float) ((globalY * scale) - yCorrector);

        // Take absolute positions on actual map and scale to actual screen size since map image may have been
        // scaled
        point.x = (float) (x * xMapScale);
        point.y = (float) (y * yMapScale);

        return point;
    }

    private void draw(Hit hit, Canvas canvas, PointF location, long now) {
        // Find the color index for the given site id
        int key = resourceProvider.getKey(hit.siteId);
        if (key == -1)
            return;

        Bitmap pin = resourceProvider.getPin(key);
        Rect source = new Rect();
        RectF destination = new RectF();
        destination.top = location.y - pinHeight / 2;
        destination.left = location.x - pinWidth / 2;
        destination.right = destination.left + pinWidth;
        destination.bottom = destination.top + pinHeight;
        source.right = pin.getWidth();
        source.bottom = pin.getHeight();
        canvas.drawBitmap(pin, source, destination, mapPaint);
    }

    /**
     * Add hit to view
     *
     * @param newHit
     */
    public void addHit(Hit newHit) {
        hits.add(newHit);
        while (hits.size() >= MAX_HITS)
            hits.poll();

        ObjectAnimator animator = ObjectAnimator.ofInt(new RingAnimation(newHit), "state", 0, RING_SIZES.length);
        animator.setDuration(500);
        animator.addListener(new AnimatorListenerAdapter() {

            public void onAnimationEnd(Animator animation) {
                rings.remove(animation);
                postInvalidate();
            }
        });
        animator.addUpdateListener(new AnimatorUpdateListener() {

            public void onAnimationUpdate(ValueAnimator animation) {
                postInvalidate();
            }
        });
        animator.start();
        rings.add(animator);

        invalidate();
    }

    /**
     * Pause the animated view
     */
    public void pause() {
        for (ObjectAnimator animator : rings)
            animator.end();
    }
}
