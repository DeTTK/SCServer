package szserver.world;

/** One immutable block snapshot plus the same native decoration geometry used by actors. */
public final class ItemCollisionScene implements AutoCloseable {
    private final ActorScene objects,blocks;
    public ItemCollisionScene(ActorScene objects,ActorScene blocks){this.objects=objects;this.blocks=blocks;}
    public void checkCurrent(){objects.checkCurrent();if(blocks!=null)blocks.checkCurrent();}
    public double sweep(SceneCollision.Box body,int axis,double delta,ActorScene.Budget budget){checkCurrent();var area=new SceneCollision.Box(body.x()+Math.min(0,axis==0?delta:0),body.y()+Math.min(0,axis==1?delta:0),body.z()+Math.min(0,axis==2?delta:0),body.xx()+Math.max(0,axis==0?delta:0),body.yy()+Math.max(0,axis==1?delta:0),body.zz()+Math.max(0,axis==2?delta:0));if(!objects.covers(area)||blocks!=null&&!blocks.covers(area))throw new ActorScene.Deferred();double move=objects.sweep(body,axis,delta,budget);return blocks==null?move:blocks.sweep(body,axis,move,budget);}
    @Override public void close(){objects.close();if(blocks!=null)blocks.close();}
}
