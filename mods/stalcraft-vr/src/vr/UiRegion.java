package vr;

/** Logical native GUI coordinates, independent of framebuffer pixels. */
record UiRegion(int x,int y,int width,int height) {
    UiRegion {if(width<1||height<1)throw new IllegalArgumentException("Empty UI region");}
    static UiRegion full(int width,int height){return new UiRegion(0,0,width,height);}
    static UiRegion clipped(long x,long y,long right,long bottom,int width,int height){
        int left=(int)Math.max(0,Math.min(width,x)),top=(int)Math.max(0,Math.min(height,y));
        int r=(int)Math.max(0,Math.min(width,right)),b=(int)Math.max(0,Math.min(height,bottom));
        return r<=left||b<=top?null:new UiRegion(left,top,r-left,b-top);
    }
    UiRegion union(UiRegion other){int left=Math.min(x,other.x),top=Math.min(y,other.y);return new UiRegion(left,top,Math.max(x+width,other.x+other.width)-left,Math.max(y+height,other.y+other.height)-top);}
    UiRegion padded(int margin,int totalWidth,int totalHeight){return clipped((long)x-margin,(long)y-margin,(long)x+width+margin,(long)y+height+margin,totalWidth,totalHeight);}
    int nativeX(double u){return x+Math.min(width-1,Math.max(0,(int)Math.floor(u*width)));}
    int nativeY(double v){return y+Math.min(height-1,Math.max(0,(int)Math.floor(v*height)));}
    double scaleToFit(double maxWidth,double maxHeight){
        return Math.min(maxWidth/width,maxHeight/height);
    }
}
