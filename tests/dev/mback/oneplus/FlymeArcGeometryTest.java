package dev.mback.oneplus;

public final class FlymeArcGeometryTest {
    static int assertions;
    public static void main(String[] args) {
        final float width = 1272f, height = 2772f, density = 3.5f;
        int radius = ArcConfig.DEFAULT_RADIUS;
        int size = ArcConfig.DEFAULT_ICON;
        check(FlymeArcGeometry.MAX_POSITIONS == 7, "six apps + More");
        check(FlymeArcGeometry.visiblePositions(0) == 1, "More when empty");
        check(FlymeArcGeometry.visiblePositions(6) == 7, "fully populated");
        check(ArcConfig.hoverVibration(11) == 10, "hover clamp upper");
        check(ArcConfig.hoverVibration(-3) == 0, "hover clamp lower");
        check(ArcConfig.DEFAULT_HOVER_VIBRATION >= 1, "default level");

        for (int count=1; count<=7; count++) {
            float step = FlymeArcGeometry.angleStep(radius,size,width,density,count);
            check(Float.isFinite(step) && step >= 0, "adaptive step valid");
            for (int i=0; i<count; i++) {
                float x=FlymeArcGeometry.x(CornerGesture.RIGHT,i,width,density,radius,size,count);
                float left=FlymeArcGeometry.x(CornerGesture.LEFT,i,width,density,radius,size,count);
                float y=FlymeArcGeometry.y(i,width,height,density,radius,size,count);
                check(x > 0 && x < width && y > 0 && y < height, "onscreen");
                check(Math.abs(left+x-width) < .001f, "mirror");
                check(Math.abs(Math.hypot(width-x,height-y) - width*radius/100d) < .003,
                        "all points on circle");
                check(FlymeArcGeometry.hit(x,y,CornerGesture.RIGHT,width,height,
                        density,radius,size,count) == i,"hit right");
                check(FlymeArcGeometry.hit(left,y,CornerGesture.LEFT,width,height,
                        density,radius,size,count) == i,"hit left");
                if (i>0) {
                    check(y < FlymeArcGeometry.y(i-1,width,height,density,radius,size,count),
                            "ascending");
                }
            }
        }
        // Geometry responds to density, screen width, radius and icon count.
        float defaultStep=FlymeArcGeometry.angleStep(radius,size,width,density,7);
        check(Math.abs(FlymeArcGeometry.angleStep(radius,size,width,density,3)-defaultStep) > .01,
                "dynamic spacing when fewer icons");
        check(Math.abs(FlymeArcGeometry.angleStep(radius,64,width,density,3)
                -FlymeArcGeometry.angleStep(radius,28,width,density,3))>.002,
                "icon dimensions influence adaptive spacing");
        check(Math.abs(FlymeArcGeometry.x(2,3,width,density,50,size,7)
                -FlymeArcGeometry.x(2,3,width,density,86,size,7))>50,
                "radius changes actual layout");
        float x0=FlymeArcGeometry.x(2,0,width,density,radius,size,7);
        float x3=FlymeArcGeometry.x(2,3,width,density,radius,size,7);
        float x6=FlymeArcGeometry.x(2,6,width,density,radius,size,7);
        float y0=FlymeArcGeometry.y(0,width,height,density,radius,size,7);
        float y3=FlymeArcGeometry.y(3,width,height,density,radius,size,7);
        float y6=FlymeArcGeometry.y(6,width,height,density,radius,size,7);
        check(Math.abs((x3-x0)*(y6-y0)-(x6-x0)*(y3-y0))>5000,
                "circular curvature not straight line");
        check(FlymeArcGeometry.hit(width*.5f,height*.5f,2,width,height,density,
                radius,size,7) == -1,"empty space");
        for (int r : new int[]{50,58,72,86}) {
            for (int s : new int[]{28,44,64}) {
                for (int n=1; n<=7; n++) {
                    for (int i=0; i<n; i++) {
                        float x=FlymeArcGeometry.x(2,i,width,density,r,s,n);
                        float y=FlymeArcGeometry.y(i,width,height,density,r,s,n);
                        float half=s*density*.5f;
                        check(x-half>=0 && x+half<=width+1 && y-half>=height-width*.97f &&
                                y+half<=height,"no clipped icons for all configurations");
                    }
                }
            }
        }
        System.out.println("FlymeArcGeometryTest passed ("+assertions+" assertions)");
    }
    static void check(boolean ok,String why) {
        if(!ok) throw new AssertionError(why);
        assertions++;
    }
}
