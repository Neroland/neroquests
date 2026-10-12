package za.co.neroland.neroquests.client.screen;

/**
 * The quest graph's camera: which world point sits at the middle of the viewport and how far it is zoomed,
 * with eased motion towards a target. Pure (no game classes) so fitting, clamping and zooming are unit-tested.
 *
 * <p>World pixels are {@link GraphGeometry}'s; a world point maps to the screen as
 * {@code viewportCentre + (world - camera) * zoom}. Every input changes the <em>target</em> camera and zoom;
 * {@link #tick(double)} eases the drawn values towards it. Drag-panning moves both at once so the graph sticks
 * to the cursor. A zoom step keeps the world point under the cursor fixed, also while it animates.
 *
 * <p>The target is always clamped to the content bounds: a graph that fits is centred, a larger one can be
 * panned until its far edge (plus {@link #FIT_MARGIN}) reaches the viewport edge, and edit mode adds slack.
 * An optional right inset (the detail drawer covering part of the canvas) shrinks the usable viewport.
 */
public final class GraphView {

    /** The zoom levels the wheel and the zoom buttons step through. */
    public static final double[] ZOOM_STEPS = {0.5D, 0.75D, 1.0D, 1.25D, 1.5D};
    /** "Fit" never magnifies past this, so small chapters stay pixel-crisp. */
    public static final double FIT_MAX_ZOOM = 1.0D;
    /** Screen pixels kept free around the content when fitting and clamping. */
    public static final int FIT_MARGIN = 16;
    /** Overflow flags. */
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int UP = 4;
    public static final int DOWN = 8;

    /** How quickly the drawn camera catches up with the target (per second). */
    private static final double EASE_RATE = 16.0D;

    private int viewX;
    private int viewY;
    private int viewW = 1;
    private int viewH = 1;
    private int insetRight;

    private boolean hasBounds;
    private double minX;
    private double minY;
    private double maxX;
    private double maxY;
    private double slack;

    private double camX;
    private double camY;
    private double zoom = 1.0D;
    private double targetX;
    private double targetY;
    private double targetZoom = 1.0D;

    private boolean anchored;
    private double anchorWorldX;
    private double anchorWorldY;
    private double anchorScreenX;
    private double anchorScreenY;

    /** The on-screen viewport. */
    public void setViewport(int x, int y, int w, int h) {
        this.viewX = x;
        this.viewY = y;
        this.viewW = Math.max(1, w);
        this.viewH = Math.max(1, h);
        this.insetRight = Math.min(this.insetRight, this.viewW - 1);
        clampTarget();
    }

    /** Screen pixels at the right of the viewport that are covered (the drawer); 0 for none. */
    public void setInsetRight(int pixels) {
        int inset = Math.max(0, Math.min(this.viewW - 1, pixels));
        if (inset != this.insetRight) {
            this.insetRight = inset;
            clampTarget();
        }
    }

    public int insetRight() {
        return this.insetRight;
    }

    /** The content's world bounds; {@code slack} world pixels of extra pan room on every side (edit mode). */
    public void setBounds(double left, double top, double right, double bottom, double extra) {
        this.hasBounds = true;
        this.minX = left;
        this.minY = top;
        this.maxX = Math.max(left, right);
        this.maxY = Math.max(top, bottom);
        this.slack = Math.max(0.0D, extra);
        clampTarget();
    }

    /** No content: the camera rests at the origin. */
    public void clearBounds() {
        this.hasBounds = false;
    }

    public double zoom() {
        return this.zoom;
    }

    public double targetZoom() {
        return this.targetZoom;
    }

    public double cameraX() {
        return this.camX;
    }

    public double cameraY() {
        return this.camY;
    }

    public double targetX() {
        return this.targetX;
    }

    public double targetY() {
        return this.targetY;
    }

    private double centreX() {
        return this.viewX + this.viewW / 2.0D;
    }

    private double centreY() {
        return this.viewY + this.viewH / 2.0D;
    }

    /** Screen x of a world x, as drawn this frame. */
    public double screenX(double worldX) {
        return centreX() + (worldX - this.camX) * this.zoom;
    }

    /** Screen y of a world y, as drawn this frame. */
    public double screenY(double worldY) {
        return centreY() + (worldY - this.camY) * this.zoom;
    }

    /** World x under a screen x, as drawn this frame. */
    public double worldX(double screenX) {
        return this.camX + (screenX - centreX()) / this.zoom;
    }

    /** World y under a screen y, as drawn this frame. */
    public double worldY(double screenY) {
        return this.camY + (screenY - centreY()) / this.zoom;
    }

    /**
     * The largest {@link #ZOOM_STEPS} entry, at most {@code maxZoom}, at which {@code contentW}&times;{@code contentH}
     * world pixels fit a {@code viewW}&times;{@code viewH} viewport with {@code margin} free on every side; the
     * smallest step when nothing fits.
     */
    public static double fitZoom(double contentW, double contentH, int viewW, int viewH, int margin, double maxZoom) {
        double availW = Math.max(1.0D, viewW - 2.0D * margin);
        double availH = Math.max(1.0D, viewH - 2.0D * margin);
        double ideal = Math.min(maxZoom, Math.min(availW / Math.max(1.0D, contentW), availH / Math.max(1.0D, contentH)));
        double best = ZOOM_STEPS[0];
        for (double step : ZOOM_STEPS) {
            if (step <= ideal + 1e-9) {
                best = step;
            }
        }
        return best;
    }

    /** Index of the zoom step closest to {@code value}. */
    public static int nearestStep(double value) {
        int best = 0;
        for (int index = 1; index < ZOOM_STEPS.length; index++) {
            if (Math.abs(ZOOM_STEPS[index] - value) < Math.abs(ZOOM_STEPS[best] - value)) {
                best = index;
            }
        }
        return best;
    }

    /**
     * One axis of the clamp: the allowed camera range for content {@code [min, max]} (world) in a view of
     * {@code view} screen pixels whose last {@code inset} pixels are covered. Content that fits is centred in the
     * uncovered part (&plusmn; {@code slack}); larger content may pan until either edge, plus the margin, reaches
     * the matching viewport edge (&plusmn; {@code slack}).
     */
    public static double clampAxis(double cam, double min, double max, int view, int inset, double zoom, double slack) {
        double margin = FIT_MARGIN / zoom;
        double nearEdge = min - margin + view / 2.0D / zoom;
        double farEdge = max + margin - (view / 2.0D - inset) / zoom;
        double lo;
        double hi;
        if (farEdge <= nearEdge) {
            double centre = (nearEdge + farEdge) / 2.0D;
            lo = centre - slack;
            hi = centre + slack;
        } else {
            lo = nearEdge - slack;
            hi = farEdge + slack;
        }
        return Math.max(lo, Math.min(hi, cam));
    }

    private void clampTarget() {
        if (!this.hasBounds) {
            return;
        }
        double x = clampAxis(this.targetX, this.minX, this.maxX, this.viewW, this.insetRight, this.targetZoom, this.slack);
        double y = clampAxis(this.targetY, this.minY, this.maxY, this.viewH, 0, this.targetZoom, this.slack);
        if (this.anchored && (x != this.targetX || y != this.targetY)) {
            // Keep the anchored animation landing exactly on the clamped target.
            this.anchorWorldX = x + (this.anchorScreenX - centreX()) / this.targetZoom;
            this.anchorWorldY = y + (this.anchorScreenY - centreY()) / this.targetZoom;
        }
        this.targetX = x;
        this.targetY = y;
    }

    /** Fit the whole content into the (uncovered) viewport and centre it (the "fit" button). */
    public void fit(boolean instant) {
        fit(instant, ZOOM_STEPS[0]);
    }

    /**
     * Fit the content, but never below {@code minZoom}: content that fits at the chosen zoom is centred; content
     * that is still too large starts at its left (top) edge &mdash; where a chapter begins &mdash; and the rest is
     * reached by panning (the overflow indicators show it is there). Opening a chapter uses a readable minimum.
     */
    public void fit(boolean instant, double minZoom) {
        this.anchored = false;
        if (!this.hasBounds) {
            this.targetX = 0.0D;
            this.targetY = 0.0D;
            this.targetZoom = 1.0D;
        } else {
            int usable = this.viewW - this.insetRight;
            double fitted = fitZoom(this.maxX - this.minX, this.maxY - this.minY, usable, this.viewH, FIT_MARGIN,
                    FIT_MAX_ZOOM);
            this.targetZoom = Math.max(fitted, Math.min(FIT_MAX_ZOOM, minZoom));
            double z = this.targetZoom;
            double margin = FIT_MARGIN / z;
            if ((this.maxX - this.minX) + 2 * margin <= usable / z) {
                this.targetX = (this.minX + this.maxX) / 2.0D + this.insetRight / (2.0D * z);
            } else {
                this.targetX = this.minX - margin + this.viewW / 2.0D / z;
            }
            if ((this.maxY - this.minY) + 2 * margin <= this.viewH / z) {
                this.targetY = (this.minY + this.maxY) / 2.0D;
            } else {
                this.targetY = this.minY - margin + this.viewH / 2.0D / z;
            }
            clampTarget();
        }
        if (instant) {
            snap();
        }
    }

    /** Jump the drawn camera to the target. */
    public void snap() {
        this.camX = this.targetX;
        this.camY = this.targetY;
        this.zoom = this.targetZoom;
        this.anchored = false;
    }

    /**
     * One zoom step in ({@code direction > 0}) or out about a screen point, keeping the world point under it
     * fixed. Returns false at either end of the range.
     */
    public boolean zoomBy(int direction, double screenX, double screenY) {
        int index = Math.max(0, Math.min(ZOOM_STEPS.length - 1, nearestStep(this.targetZoom) + Integer.signum(direction)));
        double next = ZOOM_STEPS[index];
        if (next == this.targetZoom || direction == 0) {
            return false;
        }
        double worldX = this.targetX + (screenX - centreX()) / this.targetZoom;
        double worldY = this.targetY + (screenY - centreY()) / this.targetZoom;
        this.targetZoom = next;
        this.targetX = worldX - (screenX - centreX()) / next;
        this.targetY = worldY - (screenY - centreY()) / next;
        // Anchor the animation on the drawn camera's point under the cursor so it does not drift while easing.
        this.anchored = true;
        this.anchorScreenX = screenX;
        this.anchorScreenY = screenY;
        this.anchorWorldX = worldX;
        this.anchorWorldY = worldY;
        clampTarget();
        return true;
    }

    /** Pan by a screen-pixel delta (content follows the delta). {@code instant} also moves the drawn camera. */
    public void panBy(double dxScreen, double dyScreen, boolean instant) {
        this.anchored = false;
        this.targetX -= dxScreen / this.targetZoom;
        this.targetY -= dyScreen / this.targetZoom;
        clampTarget();
        if (instant) {
            this.camX = this.targetX;
            this.camY = this.targetY;
        }
    }

    /**
     * Pan (eased) the least amount that brings the world rectangle into the uncovered viewport, keeping
     * {@code pad} screen pixels around it.
     */
    public void ensureVisible(double worldX, double worldY, double worldW, double worldH, int pad) {
        this.anchored = false;
        double z = this.targetZoom;
        double left = centreX() + (worldX - this.targetX) * z;
        double right = left + worldW * z;
        double top = centreY() + (worldY - this.targetY) * z;
        double bottom = top + worldH * z;
        double viewRight = this.viewX + this.viewW - this.insetRight - pad;
        double viewBottom = this.viewY + this.viewH - pad;
        if (right > viewRight) {
            this.targetX += (right - viewRight) / z;
        } else if (left < this.viewX + pad) {
            this.targetX -= (this.viewX + pad - left) / z;
        }
        if (bottom > viewBottom) {
            this.targetY += (bottom - viewBottom) / z;
        } else if (top < this.viewY + pad) {
            this.targetY -= (this.viewY + pad - top) / z;
        }
        clampTarget();
    }

    /** Ease the drawn camera towards the target; true while still moving. */
    public boolean tick(double seconds) {
        double k = 1.0D - Math.exp(-EASE_RATE * Math.max(0.0D, Math.min(0.25D, seconds)));
        this.zoom += (this.targetZoom - this.zoom) * k;
        if (Math.abs(this.targetZoom - this.zoom) < 0.002D) {
            this.zoom = this.targetZoom;
        }
        if (this.anchored) {
            this.camX = this.anchorWorldX - (this.anchorScreenX - centreX()) / this.zoom;
            this.camY = this.anchorWorldY - (this.anchorScreenY - centreY()) / this.zoom;
            if (this.zoom == this.targetZoom) {
                this.anchored = false;
                this.camX = this.targetX;
                this.camY = this.targetY;
            }
        } else {
            this.camX += (this.targetX - this.camX) * k;
            this.camY += (this.targetY - this.camY) * k;
            if (Math.abs(this.targetX - this.camX) < 0.05D) {
                this.camX = this.targetX;
            }
            if (Math.abs(this.targetY - this.camY) < 0.05D) {
                this.camY = this.targetY;
            }
        }
        return this.zoom != this.targetZoom || this.camX != this.targetX || this.camY != this.targetY;
    }

    /**
     * Which viewport sides the screen rectangle [{@code left}, {@code right}) &times; [{@code top}, {@code bottom})
     * extends past, as {@link #LEFT}/{@link #RIGHT}/{@link #UP}/{@link #DOWN} flags (1 px tolerance).
     */
    public static int overflow(double left, double top, double right, double bottom,
                               int viewLeft, int viewTop, int viewRight, int viewBottom) {
        int flags = 0;
        if (left < viewLeft - 1.0D) {
            flags |= LEFT;
        }
        if (right > viewRight + 1.0D) {
            flags |= RIGHT;
        }
        if (top < viewTop - 1.0D) {
            flags |= UP;
        }
        if (bottom > viewBottom + 1.0D) {
            flags |= DOWN;
        }
        return flags;
    }

    /** {@link #overflow} of the content bounds against the uncovered viewport, as drawn this frame. */
    public int overflow() {
        if (!this.hasBounds) {
            return 0;
        }
        return overflow(screenX(this.minX), screenY(this.minY), screenX(this.maxX), screenY(this.maxY),
                this.viewX, this.viewY, this.viewX + this.viewW - this.insetRight, this.viewY + this.viewH);
    }
}
