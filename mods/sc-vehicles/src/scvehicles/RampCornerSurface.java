package scvehicles;

import java.util.*;

/** Continuous two-axis profiles fitted to the native upright collision cells. */
final class RampCornerSurface {
    enum Kind { CONCAVE, CONVEX, DIAGONAL_HIGH, DIAGONAL_LOW, PYRAMID, NATIVE_GRID }
    record Patch(int x,int y,int z,Kind kind,int sx,int sz,double[][] grid,RampGridIntegral integral) {
        Patch(int x,int y,int z,Kind kind,int sx,int sz){this(x,y,z,kind,sx,sz,null,null);}
        Patch(int x,int y,int z,Kind kind,int sx,int sz,double[][] grid){this(x,y,z,kind,sx,sz,grid,grid==null?null:new RampGridIntegral(grid));}
        double u(double x){return sx>0?x-this.x:1-x+this.x;}
        double v(double z){return sz>0?z-this.z:1-z+this.z;}
        double raw(double x,double z){
            double u=clamp(u(x),0,1),v=clamp(v(z),0,1);
            return y+switch(kind){
                case CONCAVE->Math.max(u,v);case CONVEX->Math.min(u,v);
                case DIAGONAL_HIGH->clamp(u+v,0,1);case DIAGONAL_LOW->clamp(u+v-1,0,1);
                case PYRAMID->Math.min(Math.min(u,1-u),Math.min(v,1-v));
                case NATIVE_GRID->grid[Math.min(7,(int)(u*8))][Math.min(7,(int)(v*8))];
            };
        }
        double smooth(double x,double z){
            double u=u(x),v=v(z),a=soft(u,1.05),b=soft(v,1.05);
            return y+switch(kind){
                case CONCAVE->roundedMax(a,b,.12);case CONVEX->-roundedMax(-a,-b,.12);
                case DIAGONAL_HIGH->soft(soft(u,.16)+soft(v,.16),1.25);
                case DIAGONAL_LOW->soft(soft(u,.16)+soft(v,.16)-1,1.25);
                case PYRAMID->-roundedMax(roundedMax(-a,-soft(1-u,1.05),.06),roundedMax(-b,-soft(1-v,1.05),.06),.06);
                case NATIVE_GRID->filtered(u,v);
            };
        }
        private double filtered(double u,double v){
            return integral.average(u,v);
        }
        double distance(double x,double z){return Math.hypot(Math.max(0,Math.max(this.x-x,x-this.x-1)),Math.max(0,Math.max(this.z-z,z-this.z-1)));}
        double weight(double x,double z){double d=distance(x,z)/RampSurface.RADIUS;return d>=1?0:1-d*d*(3-2*d);}
    }
    private List<Patch> patches=List.of();
    private final RampIndex<Patch> index=new RampIndex<>();
    private static final Map<Long,Patch> TEMPLATES=new LinkedHashMap<>(64,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<Long,Patch> e){return size()>512;}
    };
    private static long templateHits,templateFits;
    static Map<String,Object> templateStats(){synchronized(TEMPLATES){return Map.of("size",TEMPLATES.size(),"hits",templateHits,"fits",templateFits);}}
    void set(Collection<Patch> values){patches=List.copyOf(values);index.clear();for(Patch p:patches)index.add(p,p.x-RampSurface.RADIUS-.001,p.z-RampSurface.RADIUS-.001,p.x+1+RampSurface.RADIUS+.001,p.z+1+RampSurface.RADIUS+.001);}
    int size(){return patches.size();}
    void dependencies(Map<Object,RampProfileDependencies.Region> out){
        double r=RampSurface.RADIUS+.00001;
        for(Patch p:patches)out.put(p,new RampProfileDependencies.Region(p.x-r,p.z-r,p.x+1+r,p.z+1+r));
    }
    /** Reject ceilings, vertical diagonal walls, steps and unsupported profiles. */
    static Patch fit(int x,int y,int z,List<double[]> boxes){
        for(double[] b:boxes)if(Math.abs(b[1]-y)>1e-5)return null;
        long key=1469598103934665603L;
        for(double[] b:boxes)for(int n=0;n<6;n++){key^=Math.round((b[n]-(n%3==0?x:n%3==1?y:z))*4096);key*=1099511628211L;}
        synchronized(TEMPLATES){if(TEMPLATES.containsKey(key)){templateHits++;Patch p=TEMPLATES.get(key);return p==null?null:new Patch(x,y,z,p.kind,p.sx,p.sz,p.grid,p.integral);}templateFits++;}
        double[][] height=new double[8][8];
        for(int ix=0;ix<8;ix++)for(int iz=0;iz<8;iz++){
            double u=x+(ix+.5)/8,v=z+(iz+.5)/8,h=y;
            for(double[] b:boxes)if(u>b[0]-1e-7&&u<b[3]+1e-7&&v>b[2]-1e-7&&v<b[5]+1e-7)h=Math.max(h,b[4]);
            height[ix][iz]=h;
        }
        for(Kind kind:Kind.values())if(kind!=Kind.NATIVE_GRID)for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1}){
            Patch p=new Patch(x,y,z,kind,sx,sz);boolean matches=true;
            for(int ix=0;ix<8&&matches;ix++)for(int iz=0;iz<8;iz++){
                // Native cells enclose the upper edge of a 1/8m tread. For a
                // diagonal both coordinates may contribute one tread of error.
                double u=x+(ix+.5)/8,v=z+(iz+.5)/8;
                double expected=0;
                for(double dx:new double[]{-.06249,.06249})for(double dz:new double[]{-.06249,.06249})expected=Math.max(expected,p.raw(u+dx,v+dz)-y);
                expected=y+Math.ceil((expected-1e-6)*8)/8;
                if(Math.abs(height[ix][iz]-expected)>.001){matches=false;break;}
            }
            if(matches){remember(key,p);return p;}
        }
        boolean intermediate=false;double gx=0,gz=0;
        for(int i=0;i<8;i++)for(int j=0;j<8;j++){
            intermediate|=height[i][j]>y+.001&&height[i][j]<y+.999;
            if(i>0)gx=Math.max(gx,Math.abs(height[i][j]-height[i-1][j]));
            if(j>0)gz=Math.max(gz,Math.abs(height[i][j]-height[i][j-1]));
        }
        for(int i=0;i<8;i++)for(int j=0;j<8;j++)height[i][j]-=y;
        Patch result=intermediate&&gx>.01&&gz>.01?new Patch(x,y,z,Kind.NATIVE_GRID,1,1,height):null;
        remember(key,result);return result;
    }
    private static void remember(long key,Patch p){synchronized(TEMPLATES){TEMPLATES.put(key,p);}}
    double shift(double x,double z,double top,double fallback){
        double weights=0,correction=0;
        for(Patch p:index.at(x,z)){
            double w=p.weight(x,z);if(w==0)continue;
            double raw=p.raw(x,z);
            boolean inside=p.distance(x,z)<1e-7&&top>p.y+.001&&top<=raw+.26;
            if(!inside&&Math.abs(top-raw)>.16)continue;
            correction+=w*(p.smooth(x,z)-top);weights+=w;
        }
        if(weights==0)return fallback;
        double blend=Math.min(1,weights);
        return clamp(fallback*(1-blend)+correction/weights*blend,-RampSurface.LIMIT,RampSurface.LIMIT);
    }
    RampSurface.Contact contact(double x,double z,double bottom,RampSurface.Contact fallback){
        List<Patch> matched=new ArrayList<>();
        for(Patch p:index.at(x,z)){
            if(bottom<p.y-1||bottom>p.y+2)continue;
            double w=p.weight(x,z);if(w==0)continue;
            double h=p.smooth(x,z);if(Math.abs(h-bottom)>.32)continue;
            matched.add(p);
        }
        if(matched.isEmpty())return fallback;
        double e=.01,h=height(matched,x,z,x,z,fallback);
        double gx=(height(matched,x+e,z,x,z,fallback)-height(matched,x-e,z,x,z,fallback))/(2*e);
        double gz=(height(matched,x,z+e,x,z,fallback)-height(matched,x,z-e,x,z,fallback))/(2*e);
        double n=Math.sqrt(1+gx*gx+gz*gz);
        return new RampSurface.Contact(h,-gx/n,1/n,-gz/n);
    }
    private static double height(List<Patch> matched,double x,double z,double cx,double cz,RampSurface.Contact fallback){
        double sum=0,raw=0,weights=0;
        for(Patch p:matched){double w=p.weight(x,z);sum+=w*p.smooth(x,z);raw+=w*p.raw(x,z);weights+=w;}
        if(weights<1e-9)return fallback!=null?fallback.height():matched.get(0).raw(x,z);
        double base=fallback!=null?fallback.height()-(fallback.nx()*(x-cx)+fallback.nz()*(z-cz))/fallback.ny():raw/weights;
        double blend=Math.min(1,weights);return base*(1-blend)+sum/weights*blend;
    }
    private static double soft(double t,double width){return (integral(t+width)-integral(t-width))/(2*width);}
    private static double integral(double t){return t<0?0:t<1?t*t*.5:t-.5;}
    private static double roundedMax(double a,double b,double width){double t=Math.max(width-Math.abs(a-b),0);return Math.max(a,b)+t*t/(4*width);}
    private static double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
}
