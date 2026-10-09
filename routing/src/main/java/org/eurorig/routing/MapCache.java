package org.eurorig.routing;

/** Anchor of a cached software map render: the camera it was drawn at and the view it covered.
 *
 *  The bitmap spans the viewport plus a quarter-view margin on every side, so one render serves a
 *  whole pan or pinch step. {@link #covers} is the one rule that decides reuse: the bitmap is
 *  blitted with the anchor scale and the current offset, so it may be reused exactly while it
 *  still covers every pixel of the view. {@link #signCell} is the placement density key.
 */
public final class MapCache {
    /** Largest enlargement that is still reused; past it a fresh render is visibly sharper. */
    public static final double MAX_SCALE=1.8;
    /** Sign placement cell size in screen pixels; one sign per cell keeps a strip readable. */
    public static final float SIGN_CELL_X=90,SIGN_CELL_Y=65;
    public double latitude,longitude,pixels,cos=1;
    public int width,height;

    /** Record the camera and view this render was drawn for. */
    public void anchor(MapCamera camera,int viewWidth,int viewHeight){
        latitude=camera.latitude;longitude=camera.longitude;pixels=camera.pixels;
        cos=Math.cos(Math.toRadians(latitude));width=viewWidth;height=viewHeight;
    }
    /** A resized view has a different bitmap size and origin, so its cache cannot be reused. */
    public boolean matches(int viewWidth,int viewHeight){return width==viewWidth&&height==viewHeight;}
    /** Whether the cached bitmap still covers the whole view under the pending blit: the current
     *  origin {@code originX,originY} shifted by {@code dx,dy} at the current camera scale. */
    public boolean covers(MapCamera current,int viewWidth,int viewHeight,double originX,double originY,double dx,double dy){
        if(!(pixels>0)||!matches(viewWidth,viewHeight))return false;
        double ratio=current.pixels/pixels;
        if(!Double.isFinite(ratio)||ratio<=0||ratio>MAX_SCALE)return false;
        double scaleX=ratio*Math.cos(Math.toRadians(current.latitude))/cos;
        int marginX=Math.round(viewWidth*.25f),marginY=Math.round(viewHeight*.25f);
        return scaleX>=needed(originX+dx,marginX+originX)&&scaleX>=needed(viewWidth-originX-dx,viewWidth+marginX-originX)
            &&ratio>=needed(originY+dy,marginY+originY)&&ratio>=needed(viewHeight-originY-dy,viewHeight+marginY-originY);
    }
    /** Density key of the cell holding a sign anchor. The overscan strips put anchors at negative
     *  screen coordinates, so each axis is floored to its own cell and the y cell is masked to its
     *  low word before packing. Without the mask the old packing
     *  {@code ((long)(x/CX)<<32)|(long)(y/CY)} sign-extended a negative y cell over the x cell
     *  ({@code (long)-3} is {@code 0xFFFFFFFFFFFFFFFD}), collapsing every negative-y column into one
     *  key; plain truncation also merged a small negative coordinate with cell zero. */
    public static long signCell(float x,float y){
        return ((long)Math.floor(x/SIGN_CELL_X)<<32)|((long)Math.floor(y/SIGN_CELL_Y)&0xffffffffL);
    }
    /** Scale below which the bitmap edge would leave the view; a non-positive span cannot cover. */
    private static double needed(double offset,double span){return span>0?offset/span:Double.POSITIVE_INFINITY;}
}
