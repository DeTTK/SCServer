package szserver.world;

/** Carpenter 2413 native ladder planes (258185106.implements), independent of body shapes.
 * Native ordinary ladder 65 supplies no such planes. Authored scene ladders are separate. */
public final class ActorLadders {
    public static final double EYE=1.71f,SPEED=1.2;
    public record Contact(World.Block block,boolean xAxis,double plane,int side,double x,double z){
        public World.Vec anchor(double y){return new World.Vec(x,y,z);}
    }
    public static double[][] planes(int packed){
        if(packed>>>4!=2413)return new double[0][];
        return switch(packed&15){
            case 2->new double[][]{{0,0,.8,1,0,.8,1,1,.8,0,1,.8},{0,0,1,1,0,1,1,1,1,0,1,1}};
            case 3->new double[][]{{0,0,.2,1,0,.2,1,1,.2,0,1,.2},{0,0,0,1,0,0,1,1,0,0,1,0}};
            case 4->new double[][]{{.8,0,0,.8,0,1,.8,1,1,.8,1,0},{1,0,0,1,0,1,1,1,1,1,1,0}};
            case 5->new double[][]{{.2,0,0,.2,0,1,.2,1,1,.2,1,0},{0,0,0,0,0,1,0,1,1,0,1,0}};
            default->new double[0][];
        };
    }
    /** Only already resident/captured voxels; caller owns clearance and the query budget. */
    public static Contact contact(BlockShapes.Voxels source,World.Vec p)throws InterruptedException{
        int yy=(int)Math.floor(p.y()+EYE);Contact best=null;double distance=Double.POSITIVE_INFINITY;
        for(int x=(int)Math.floor(p.x())-1;x<=(int)Math.floor(p.x())+1;x++)for(int z=(int)Math.floor(p.z())-1;z<=(int)Math.floor(p.z())+1;z++){
            var b=new World.Block(x,yy,z);if(yy<0||yy>255)continue;int packed=source.voxel(b);if(packed>>>4!=2413)continue;int meta=packed&15;if(meta<2||meta>5)continue;
            boolean axis=meta>=4;double along=axis?p.z():p.x(),low=axis?z:x;if(along<low||along>low+1)continue;
            double coordinate=axis?p.x():p.z(),root=axis?x:z;double inner=meta==2||meta==4?.8:.2,outer=meta==2||meta==4?1:0;
            for(double offset:new double[]{inner,outer}){
                double plane=root+offset;int side=coordinate<plane?-1:1;double normal=plane+side*.35;
                // Only approach the exposed face; do not attach through the ladder's body.
                if(offset==inner&&side!=(meta==2||meta==4?-1:1)||offset==outer&&side!=(meta==2||meta==4?1:-1))continue;
                double gap=Math.abs(coordinate-plane);if(gap>.46)continue;double d=Math.abs(coordinate-normal);
                if(d<distance){double lateral=Math.max(low+.35,Math.min(low+.65,along));distance=d;best=new Contact(b,axis,plane,side,axis?normal:lateral,axis?lateral:normal);}
            }
        }return best;
    }
    public static ActorLadderGuide.Contact routeContact(BlockShapes.Voxels source,ActorScene scene,World.Vec p,ActorScene.Budget budget)throws InterruptedException{
        var block=contact(source,p);if(block!=null){float yaw=block.xAxis()?(block.side()<0?-90:90):(block.side()<0?0:180);return new ActorLadderGuide.Contact(block.anchor(p.y()),yaw);}
        return scene==null?null:scene.ladderContact(p,budget);
    }
    private ActorLadders(){}
}
