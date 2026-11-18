package szserver.world;

/** Mine occlusion over the held native block/tile shapes. No coarse solid-voxel fallback. */
final class ActorMineBlast {
    static World.Vec departure(World.Vec start,World.Vec end){
        double x=end.x()-start.x(),y=end.y()-start.y(),z=end.z()-start.z(),length=Math.sqrt(x*x+y*y+z*z);
        if(length<1e-9)return start;
        // Exclude only the mathematical t=0 boundary when departing from the floor.
        double amount=Math.min(1e-5,length*.5)/length;return new World.Vec(start.x()+x*amount,start.y()+y*amount,start.z()+z*amount);
    }
    static boolean blocksClear(ActorWorldAccess source,World.Vec start,World.Vec end){
        World.Vec delta=new World.Vec(end.x()-start.x(),end.y()-start.y(),end.z()-start.z());
        for(int x=(int)Math.floor(Math.min(start.x(),end.x()));x<=(int)Math.floor(Math.max(start.x(),end.x()));x++)for(int y=Math.max(0,(int)Math.floor(Math.min(start.y(),end.y()))-BlockShapes.LOWER_HALO);y<=Math.min(255,(int)Math.floor(Math.max(start.y(),end.y())));y++)for(int z=(int)Math.floor(Math.min(start.z(),end.z()));z<=(int)Math.floor(Math.max(start.z(),end.z()));z++){
            source.use();var p=new World.Block(x,y,z);
            try{int packed=source.voxel(p);for(var box:BlockShapes.cells(packed,BlockShapes.tileShape(packed)?source.tile(p):null,p,source))
                if(SceneCollision.ray(start,delta,new SceneCollision.Box(x+box[0],y+box[1],z+box[2],x+box[3],y+box[4],z+box[5]),1)!=null)return false;
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new ActorScene.Deferred();}
        }return true;
    }
}
