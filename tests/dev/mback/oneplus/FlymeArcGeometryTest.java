package dev.mback.oneplus;

public final class FlymeArcGeometryTest {
    private static int assertions;

    public static void main(String[] args) {
        final float width = 1272f, height = 2772f;
        check(FlymeArcGeometry.MAX_APPS == 6, "6 apps maximum");
        check(FlymeArcGeometry.MAX_POSITIONS == 7, "7 icons including More");
        check(FlymeArcGeometry.MORE_POSITION == 0, "More is lowest position");
        check(FlymeArcGeometry.visiblePositions(6) == 7, "full row contains 7");
        check(FlymeArcGeometry.visiblePositions(3) == 4, "More plus actual apps");
        check(FlymeArcGeometry.visiblePositions(0) == 1, "More still available when empty");
        check(FlymeArcGeometry.visiblePositions(99) == 7, "clamped to 7");

        for (int position = 0; position < 7; position++) {
            float right = FlymeArcGeometry.x(CornerGesture.RIGHT, position, width);
            float left = FlymeArcGeometry.x(CornerGesture.LEFT, position, width);
            float y = FlymeArcGeometry.y(position, width, height);
            check(right >= 0 && right < width && y > 0 && y < height,
                    "valid on-screen coordinates");
            check(Math.abs(left + right - width) < .0001f, "mirror symmetry");
            check(FlymeArcGeometry.hit(right, y, CornerGesture.RIGHT, width, height,
                    7, 80f) == position, "hit right position " + position);
            check(FlymeArcGeometry.hit(left, y, CornerGesture.LEFT, width, height,
                    7, 80f) == position, "hit left position " + position);
            if (position > 0) {
                check(right > FlymeArcGeometry.x(CornerGesture.RIGHT, position - 1, width),
                        "rises toward right");
                check(y < FlymeArcGeometry.y(position - 1, width, height),
                        "goes up");
            }
        }

        // More is at the low end, and the highest app uses a much larger arc
        // than the old short four-point row.
        check(FlymeArcGeometry.y(0, width, height) > FlymeArcGeometry.y(1, width, height),
                "More must be the bottom icon");
        check(FlymeArcGeometry.y(6, width, height) < height - width * .70f,
                "larger diagonal span");
        check(FlymeArcGeometry.hit(width * .5f, height * .5f, CornerGesture.RIGHT,
                width, height, 7, 80f) == -1, "empty area does not select");
        check(FlymeArcGeometry.hit(FlymeArcGeometry.x(CornerGesture.RIGHT, 6, width),
                FlymeArcGeometry.y(6, width, height), CornerGesture.RIGHT,
                width, height, 2, 80f) == -1, "not select an unconfigured app");
        check(FlymeArcGeometry.hit(FlymeArcGeometry.x(CornerGesture.RIGHT, 0, width),
                FlymeArcGeometry.y(0, width, height), CornerGesture.RIGHT,
                width, height, 1, 80f) == 0, "More works with no configured apps");

        System.out.println("FlymeArcGeometryTest passed (" + assertions + " assertions)");
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        assertions++;
    }
}
