package dev.mback.oneplus;

public final class FlymeArcGeometryTest {
    static int assertions;
    public static void main(String[] args) {
        final float width = 1272f, height = 2772f, density = 3.5f;
        int radius = ArcConfig.DEFAULT_RADIUS;
        int size = ArcConfig.DEFAULT_ICON;
        int side = ArcConfig.DEFAULT_SIDE_MARGIN, bottom = ArcConfig.DEFAULT_BOTTOM_MARGIN;
        check(FlymeArcGeometry.MAX_POSITIONS == 7, "six apps + More");
        check(FlymeArcGeometry.visiblePositions(0) == 1, "More when empty");
        check(FlymeArcGeometry.visiblePositions(6) == 7, "fully populated");
        check(ArcConfig.hoverVibration(11) == 10, "hover clamp upper");
        check(ArcConfig.hoverVibration(-3) == 0, "hover clamp lower");
        check(ArcConfig.DEFAULT_HOVER_VIBRATION >= 1, "default level");
        float center = FlymeArcGeometry.angle(0, radius, size, width, density, 1, side);
        for (int count = 2; count <= 7; count++) {
            float first = FlymeArcGeometry.angle(0, radius, size, width, density, count, side);
            float last = FlymeArcGeometry.angle(count - 1, radius, size, width, density, count, side);
            check(Math.abs((first + last) * .5f - center) < .001f,
                    "icons spread symmetrically from arc center");
        }

        for (int count=1; count<=7; count++) {
            float step = FlymeArcGeometry.angleStep(radius,size,width,density,count,side);
            check(Float.isFinite(step) && step >= 0, "adaptive step valid");
            for (int i=0; i<count; i++) {
                float x=FlymeArcGeometry.x(CornerGesture.RIGHT,i,width,density,radius,size,count,side);
                float left=FlymeArcGeometry.x(CornerGesture.LEFT,i,width,density,radius,size,count,side);
                float y=FlymeArcGeometry.y(i,width,height,density,radius,size,count,side,bottom);
                check(x > 0 && x < width && y > 0 && y < height, "onscreen");
                check(Math.abs(left+x-width) < .001f, "mirror");
                check(Math.abs(Math.hypot(width-side*density-x,height-bottom*density-y) - width*radius/100d) < .003,
                        "all points on circle");
                check(FlymeArcGeometry.hit(x,y,CornerGesture.RIGHT,width,height,
                        density,radius,size,count,side,bottom) == i,"hit right");
                check(FlymeArcGeometry.hit(left,y,CornerGesture.LEFT,width,height,
                        density,radius,size,count,side,bottom) == i,"hit left");
                if (i>0) {
                    check(y < FlymeArcGeometry.y(i-1,width,height,density,radius,size,count,side,bottom),
                            "ascending");
                }
            }
        }
        // Geometry responds to density, screen width, radius and icon count.
        float defaultStep=FlymeArcGeometry.angleStep(radius,size,width,density,7,side);
        check(Math.abs(FlymeArcGeometry.angleStep(radius,size,width,density,3,side)-defaultStep) > .01,
                "dynamic spacing when fewer icons");
        check(Math.abs(FlymeArcGeometry.angleStep(radius,64,width,density,3,side)
                -FlymeArcGeometry.angleStep(radius,28,width,density,3,side))>.002,
                "icon dimensions influence adaptive spacing");
        check(Math.abs(FlymeArcGeometry.x(2,3,width,density,50,size,7,side)
                -FlymeArcGeometry.x(2,3,width,density,86,size,7,side))>50,
                "radius changes actual layout");
        float x0=FlymeArcGeometry.x(2,0,width,density,radius,size,7,side);
        float x3=FlymeArcGeometry.x(2,3,width,density,radius,size,7,side);
        float x6=FlymeArcGeometry.x(2,6,width,density,radius,size,7,side);
        float y0=FlymeArcGeometry.y(0,width,height,density,radius,size,7,side,bottom);
        float y3=FlymeArcGeometry.y(3,width,height,density,radius,size,7,side,bottom);
        float y6=FlymeArcGeometry.y(6,width,height,density,radius,size,7,side,bottom);
        check(Math.abs((x3-x0)*(y6-y0)-(x6-x0)*(y3-y0))>5000,
                "circular curvature not straight line");
        check(FlymeArcGeometry.hit(width*.5f,height*.5f,2,width,height,density,
                radius,size,7,side,bottom) == -1,"empty space");
        for (int r : new int[]{50,58,72,86}) {
            for (int s : new int[]{28,44,64}) {
                for (int n=1; n<=7; n++) {
                    for (int i=0; i<n; i++) {
                        float x=FlymeArcGeometry.x(2,i,width,density,r,s,n,side);
                        float y=FlymeArcGeometry.y(i,width,height,density,r,s,n,side,bottom);
                        float half=s*density*.5f;
                        check(x-half>=side*density && x+half<=width-side*density+1
                                && y-half>=height-width*.97f-bottom*density
                                && y+half<=height-bottom*density,"no clipped icons for all configurations");
                    }
                }
            }
        }
        check(ArcConfig.margin(-2) == 0 && ArcConfig.margin(99) == 64, "margin limits");
        check(ArcConfig.margin(13) == 14, "margin step");
        float originalX = FlymeArcGeometry.x(2,0,width,density,radius,size,2,0);
        float insetX = FlymeArcGeometry.x(2,0,width,density,radius,size,2,12);
        check(Math.abs(originalX-insetX-12*density)<.003, "side margin moves icons inward");
        float originalY = FlymeArcGeometry.y(0,width,height,density,radius,size,2,side,0);
        float insetY = FlymeArcGeometry.y(0,width,height,density,radius,size,2,side,24);
        check(Math.abs(originalY-insetY-24*density)<.003, "bottom margin moves icons upward");
        for (int r : new int[]{50,58,72,86}) {
            for (int sizeDp : new int[]{28,44,64}) {
                for (int sideDp : new int[]{0,12,64}) {
                    for (int bottomDp : new int[]{0,24,64}) {
                        for (int count=1; count<=7; count++) {
                            for (int i=0; i<count; i++) {
                                float x=FlymeArcGeometry.x(2,i,width,density,r,sizeDp,count,sideDp);
                                float y=FlymeArcGeometry.y(i,width,height,density,r,sizeDp,count,sideDp,bottomDp);
                                float half=sizeDp*density*.565f;
                                check(x-half>=sideDp*density-.01f && x+half<=width-sideDp*density+.01f,
                                        "side margins also protect hovered icons");
                                check(y+half<=height-bottomDp*density+.01f &&
                                        y-half>=height-width*.97f-bottomDp*density,
                                        "bottom margin and overlay protect hovered icons");
                            }
                        }
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
