package dev.mback.oneplus;

/**
 * The ColorOS OplusNavigationHandle.onDraw() pill rectangle is:
 *   top = viewHeight - mHandleBottom
 *       - (mHeight + mAdditionalHeightForAnimation * longPressAnimProgress)
 *   bottom = viewHeight - mHandleBottom
 *
 * A View scales about its pivot, so use the visual pill center rather than
 * the center of the (much taller) NavigationHandle View.
 */
final class PillGeometry {
    static float visualCenterY(float viewHeight, float bottomInset,
                               float pillHeight, float addedHeight,
                               float longPressProgress) {
        float drawnHeight = pillHeight + addedHeight * longPressProgress;
        float top = viewHeight - bottomInset - drawnHeight;
        float bottom = viewHeight - bottomInset;
        return (top + bottom) / 2f;
    }

    private PillGeometry() {}
}
