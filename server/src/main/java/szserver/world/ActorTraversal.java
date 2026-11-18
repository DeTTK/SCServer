package szserver.world;

/** Original ledge animation values in world coordinates; no transport or native objects. */
public record ActorTraversal(Edge edge,Transition transition,Cable cable,Seat seat) {
    public ActorTraversal(Edge edge,Transition transition,Cable cable){this(edge,transition,cable,null);}
    public ActorTraversal(Edge edge,Transition transition){this(edge,transition,null);}
    public record Seat(java.util.UUID object,int index,World.Vec point,float facing,String animation,boolean animated,boolean leaving){}
    public record Cable(java.util.UUID object,byte[] payload,float angle){
        public Cable{java.util.Objects.requireNonNull(object);payload=payload.clone();if(payload.length<16||payload.length>2048||!Float.isFinite(angle))throw new IllegalArgumentException("actor_zipline_animation");}
        @Override public byte[] payload(){return payload.clone();}
        @Override public boolean equals(Object other){return other instanceof Cable c&&object.equals(c.object)&&java.util.Arrays.equals(payload,c.payload)&&Float.floatToIntBits(angle)==Float.floatToIntBits(c.angle);}
        @Override public int hashCode(){return java.util.Objects.hash(object,java.util.Arrays.hashCode(payload),angle);}
    }
    public static final ActorTraversal EMPTY=new ActorTraversal(null,null);
    public record Edge(double x1,double y1,double z1,double x2,double y2,double z2,float phase) {}
    public record Transition(double x,double y,double z,float phase,double a,double b,float p,float q,float r,boolean active,int previous) {}
    public ActorTraversal translated(double x,double z){
        return new ActorTraversal(edge==null?null:new Edge(edge.x1+x,edge.y1,edge.z1+z,edge.x2+x,edge.y2,edge.z2+z,edge.phase),
            transition==null?null:new Transition(transition.x+x,transition.y,transition.z+z,transition.phase,transition.a,transition.b,transition.p,transition.q,transition.r,transition.active,transition.previous),cable,seat==null?null:new Seat(seat.object(),seat.index(),new World.Vec(seat.point().x()+x,seat.point().y(),seat.point().z()+z),seat.facing(),seat.animation(),seat.animated(),seat.leaving()));
    }
}
