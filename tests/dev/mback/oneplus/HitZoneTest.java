package dev.mback.oneplus;

import java.util.Arrays;

public final class HitZoneTest {
    public static void main(String[] args) {
        int[] portrait = HitZone.bounds(1272, 131, 48);
        assert Arrays.equals(portrait, new int[]{424, 83, 848, 131});
        assert contains(portrait, 636, 84) : "Blank space above the pill";
        assert contains(portrait, 636, 130) : "Blank space below the pill";
        assert !contains(portrait, 423, 110) : "Left third belongs to the system";
        assert !contains(portrait, 848, 110) : "Right third belongs to the system";
        assert !contains(portrait, 636, 82) : "App content above the navigation inset";
        assert Arrays.equals(HitZone.bounds(2772, 131, 48), new int[]{924, 83, 1848, 131});
        assert Arrays.equals(HitZone.bounds(131, 2772, 48), new int[]{0, 924, 131, 1848});
        assert Arrays.equals(HitZone.bounds(1273, 131, 48), new int[]{424, 83, 849, 131});
        assert Arrays.equals(HitZone.bounds(1272, 131, 200), new int[]{424, 0, 848, 131});
        assert Arrays.equals(HitZone.bounds(1272, 131, 0), new int[4]);
        assert Arrays.equals(HitZone.bounds(0, 131, 48), new int[4]);
        System.out.println("HitZoneTest passed: center third, full inset height, rotation and empty bounds");
    }

    private static boolean contains(int[] bounds, int x, int y) {
        return x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3];
    }
}
