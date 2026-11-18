package szserver.world;

/** Worker query of current physical scene geometry and exact legacy voxel traversal. */
public final class MapObstruction {
    public enum Hit { CLEAR,SCENE,BLOCK,UNKNOWN }
    private final CurrentSceneCollision.View scenes;private final BlockReadView.Cursor cursor;
    public MapObstruction(CurrentSceneCollision.View scenes){SceneCollision.worker();this.scenes=scenes;cursor=scenes.blocks().cursor();}
    public void checkCurrent(){scenes.checkCurrent();}
    public Hit ray(World.Vec origin,World.Vec direction,double distance)throws InterruptedException{
        SceneCollision.worker();checkCurrent();if(!Double.isFinite(distance)||distance<0||distance>1024)throw new IllegalArgumentException("invalid_collision_ray");Hit result=Hit.CLEAR;
        if(scenes.hit(origin,direction,distance))result=Hit.SCENE;
        else for(var cell:SceneCollision.voxels(origin,direction,distance)){
            if(cell.x()<-szserver.world.MapBounds.HORIZONTAL||cell.x()>szserver.world.MapBounds.HORIZONTAL||cell.z()<-szserver.world.MapBounds.HORIZONTAL||cell.z()>szserver.world.MapBounds.HORIZONTAL){result=Hit.UNKNOWN;break;}
            int id=cursor.nativeVoxel(cell)>>>4;if(id!=0&&id!=8&&id!=9&&id!=10&&id!=11){result=Hit.BLOCK;break;}
        }
        checkCurrent();return result;
    }
}
