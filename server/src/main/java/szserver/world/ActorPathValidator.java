package szserver.world;

/** Worker-owned gameplay controller used to execute a candidate against its captured map. */
public interface ActorPathValidator extends AutoCloseable {
    record Start(int locomotion,float yaw,java.util.UUID zipline,int direction){public Start(int locomotion,float yaw){this(locomotion,yaw,null,0);}}
    Start start(ActorControllerState state)throws Exception;
    final class Blocked extends IllegalArgumentException {
        private final ActorNavigation.Edge edge;
        public Blocked(ActorNavigation.Edge edge){this(edge,"");}
        public Blocked(ActorNavigation.Edge edge,String detail){super("native_route_not_executable:"+edge+(detail.isEmpty()?"":" "+detail));this.edge=edge;}
        public ActorNavigation.Edge edge(){return edge;}
    }
    ActorNavigation.Path validate(ActorNavigation.Path path,ActorControllerState state,ActorScene scene)throws Exception;
    @Override void close()throws Exception;
}
