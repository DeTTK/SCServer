package scvehicles;

import java.util.*;
import scvehicles.protocol.VehicleWire;
import static scvehicles.VehiclePhysics.clamp;

/** Bounded state/event reconstruction for every audible vehicle, local or remote. */
final class VehicleSoundScene {
    static final int MAX_VEHICLES=8,MAX_EVENTS=48;
    enum Event { ENGINE_START,ENGINE_STOP,FUEL_EMPTY,GEAR_UP,GEAR_DOWN,DOOR_OPEN,DOOR_CLOSE,ENTER,EXIT,HANDBRAKE_SET,HANDBRAKE_RELEASE,AIR_BRAKE,IMPACT,LAND,SUSPENSION,SPLASH,ROLLOVER,SELECT,DENIED,SPAWN,REMOVE }
    record Acoustic(WheelEffects.Surface surface,boolean obstructed) {}
    interface Acoustics { Acoustic sample(VehicleWire state,double[] listener)throws Exception; }
    record Listener(double x,double y,double z,double yaw,double vx,double vy,double vz) {}
    record Controls(UUID vehicle,double throttle,boolean handbrake,double drift) {}
    record Voice(UUID id,int model,VehicleSoundProfile profile,double rpm,double load,double overrun,
                 double engine,double speed,double reverse,double rolling,double skid,double scrape,
                 WheelEffects.Surface surface,double gain,double pan,double doppler,double cutoff,double closedCabin) {}
    record OneShot(UUID id,Event event,double level,double pitch,double pan,int variant) {}
    record Scene(long frame,List<Voice> voices,List<OneShot> events,double master,double effects) {
        static Scene silent(){return new Scene(0,List.of(),List.of(),0,0);}
    }
    static final class State {
        final UUID id;final int model;final VehicleSoundProfile profile;final EnumMap<Event,Double> cooldown=new EnumMap<>(Event.class);
        VehicleWire previous;double rpm,load,engine,shift,probe,pan,doppler=1,rough,airborne,age;
        int gear=1,shifts;boolean handbrake;double autoDoorClose=-1;
        Acoustic acoustic=new Acoustic(WheelEffects.Surface.HARD,false);
        State(VehicleWire s){id=s.id;model=s.model;profile=VehicleSoundProfile.forModel(s.model);rpm=profile.idle();}
    }
    private final Map<UUID,State> states=new LinkedHashMap<>();
    private final List<OneShot> events=new ArrayList<>();
    private final Map<UUID,Double> impacts=new HashMap<>();
    private long frame,emitted,dropped,queryErrors;
    private double time;
    private Scene latest=Scene.silent();
    private final EnumMap<Event,Long> counts=new EnumMap<>(Event.class);
    Scene latest(){return latest;}
    void impact(UUID id,double velocityLoss){if(id!=null&&velocityLoss>.9)impacts.merge(id,Math.min(16,velocityLoss),Math::max);}
    void action(Event event){if(events.size()<MAX_EVENTS){events.add(new OneShot(null,event,.18,1,0,0));count(event);}else dropped++;}
    private void count(Event event){emitted++;counts.merge(event,1L,Long::sum);}
    private void event(State s,Event event,double level,double gain,double pan,double cooldown){
        if(time<s.cooldown.getOrDefault(event,0.))return;
        s.cooldown.put(event,time+cooldown);
        if(gain<=.001||level<=0)return;
        if(events.size()>=MAX_EVENTS){dropped++;return;}
        int seed=(int)(s.id.getLeastSignificantBits()^frame*31^event.ordinal());
        double pitch=s.profile.tone()*(.97+Math.floorMod(seed,7)*.01);
        events.add(new OneShot(s.id,event,clamp(level*gain,0,1),pitch,pan,Math.floorMod(seed,2)));count(event);
    }
    Scene update(Collection<VehicleWire> vehicles,Controls controls,Listener ears,double master,double effects,double dt,Acoustics acoustics){
        dt=clamp(dt,.001,.1);time+=dt;frame++;
        var ordered=new ArrayList<>(vehicles);ordered.sort(Comparator.comparingDouble(s->Objects.equals(s.id,controls.vehicle)?-1:distance(s,ears)+((s.flags&1)!=0?0:Math.hypot(s.vx,s.vz)>.2?20000:40000)));
        var active=new HashSet<UUID>();var voices=new ArrayList<Voice>();int probeBudget=2;
        double[] listener={ears.x,ears.y,ears.z};
        for(VehicleWire s:ordered){
            if(voices.size()>=MAX_VEHICLES||distance(s,ears)>120*120)continue;
            active.add(s.id);State a=states.get(s.id);
            if(a==null||a.model!=s.model){a=new State(s);states.put(s.id,a);}
            VehicleSoundProfile profile=a.profile;VehicleFleet.Type type=VehicleFleet.get(s.model);
            VehicleWire before=a.previous;boolean initial=before==null;
            boolean local=Objects.equals(s.id,controls.vehicle),on=(s.flags&1)!=0,grounded=(s.flags&2)!=0;
            double forward=s.vx*Math.sin(s.yaw)+s.vz*Math.cos(s.yaw),speed=Math.hypot(s.vx,s.vz);
            double travel=initial?0:Math.sqrt(Math.pow(s.x-before.x,2)+Math.pow(s.y-before.y,2)+Math.pow(s.z-before.z,2));
            boolean reset=initial||travel>Math.max(8,speed*dt*5)||Math.abs(s.yaw-(initial?s.yaw:before.yaw))>Math.PI*3;
            double acceleration=reset?0:(speed-Math.hypot(before.vx,before.vz))/dt;
            acceleration=clamp(acceleration,-20,12);
            double throttle=local?Math.abs(controls.throttle):clamp(.12+acceleration*.16+Math.abs(s.pitch)*.7,0,1);
            if(local&&controls.throttle*forward<-.3)throttle=0;
            a.load+=(throttle-a.load)*(1-Math.exp(-dt/.13));
            // A source is attached to its engine bay, including rear engine buses.
            double engineZ=type.length*(profile.rear()?-.28:.28),ex=s.x+Math.sin(s.yaw)*engineZ,ey=s.y+type.height*.4,ez=s.z+Math.cos(s.yaw)*engineZ;
            double dx=ex-ears.x,dy=ey-ears.y,dz=ez-ears.z,d=Math.sqrt(dx*dx+dy*dy+dz*dz);
            double range=profile.diesel()||profile.airBrakes()?100:75;
            double fade=clamp((range-d)/18,0,1),gain=fade/(1+Math.pow(Math.max(0,d-2)/9,2));
            double pan=(-dx*Math.cos(ears.yaw)+dz*Math.sin(ears.yaw))/Math.max(2,Math.hypot(dx,dz))*.9;
            a.probe-=dt;
            if(a.probe<=0&&gain>.002&&acoustics!=null&&probeBudget>0){probeBudget--;a.probe=.28+(Math.floorMod(s.id.hashCode(),5))*.017;
                try{Acoustic sample=acoustics.sample(s,listener);if(sample!=null)a.acoustic=sample;}
                catch(Exception error){queryErrors++;a.acoustic=new Acoustic(WheelEffects.Surface.HARD,false);}
            }
            boolean inside=d<Math.max(type.width,type.height)*.6&&Math.abs(ears.y-s.y)<type.height;
            double cabin=inside&&(s.flags&VehicleWire.DOORS_OPEN)==0?.7:0;
            gain*=a.acoustic.obstructed?.35:1;
            a.pan+=(pan-a.pan)*(1-Math.exp(-dt/.06));
            double radial=d<1?0:((s.vx-ears.vx)*dx+(s.vy-ears.vy)*dy+(s.vz-ears.vz)*dz)/d;
            double doppler=local||reset?1:clamp(343/(343+radial),.87,1.15);
            a.doppler+=(doppler-a.doppler)*(1-Math.exp(-dt/.12));
            double cutoff=(a.acoustic.obstructed?850:4800/(1+d/35))*(1-cabin*.6);
            a.shift=Math.max(0,a.shift-dt);a.age+=dt;
            if(reset){a.handbrake=local&&controls.handbrake;a.engine=on?1:0;a.airborne=0;a.autoDoorClose=-1;}
            if(!reset){
                boolean wasOn=(before.flags&1)!=0;
                if(on!=wasOn){event(a,on?Event.ENGINE_START:s.fuel<=.001?Event.FUEL_EMPTY:Event.ENGINE_STOP,.65,gain,a.pan,.18);if(on){a.engine=0;a.rpm=profile.idle();a.gear=1;a.shift=.75;}}
                boolean opened=(s.flags&VehicleWire.DOORS_OPEN)!=0,wasOpen=(before.flags&VehicleWire.DOORS_OPEN)!=0;
                if(profile.hasDoor(s.model)&&opened!=wasOpen){event(a,opened?Event.DOOR_OPEN:Event.DOOR_CLOSE,.6,gain,a.pan,.12);a.autoDoorClose=-1;}
                boolean occupied=s.driver>=0||(s.flags&VehicleWire.OCCUPIED)!=0,wasOccupied=before.driver>=0||(before.flags&VehicleWire.OCCUPIED)!=0;
                if(occupied!=wasOccupied){event(a,occupied?Event.ENTER:Event.EXIT,.35,gain,a.pan,.2);
                    if(profile.hasDoor(s.model)&&!opened){event(a,Event.DOOR_OPEN,.5,gain,a.pan,.15);a.autoDoorClose=time+.9;}}
                boolean handbrake=local?controls.handbrake:grounded&&acceleration< -5&&speed>1.5;
                if(handbrake!=a.handbrake){event(a,handbrake?Event.HANDBRAKE_SET:Event.HANDBRAKE_RELEASE,.22,gain,a.pan,.18);a.handbrake=handbrake;}
                if(profile.airBrakes()&&Math.hypot(before.vx,before.vz)>1&&speed<.6&&grounded)event(a,Event.AIR_BRAKE,.40,gain,a.pan,1.5);
                if(!grounded)a.airborne+=dt;
                if(grounded&&(before.flags&2)==0&&a.airborne>.12&&before.vy< -1.2){event(a,Event.LAND,clamp(-before.vy/9,.15,.7),gain,a.pan,.6);a.airborne=0;}
                // Ignore ordinary 1/8 m road treads and pitch changes on smooth slopes.
                double angular=Math.abs(s.pitch-before.pitch)+Math.abs(s.roll-before.roll);
                if(grounded&&speed>2&&angular>.035&&Math.abs(s.vy-before.vy)>1.7)event(a,Event.SUSPENSION,.22,gain,a.pan,.55);
                if(Math.abs(s.roll)>1.45&&Math.abs(before.roll)<1.45)event(a,Event.ROLLOVER,.65,gain,a.pan,1.5);
                int sequenceGap=s.sequence-before.sequence;
                if(!local&&sequenceGap>0&&sequenceGap<=3&&Math.hypot(s.vx-before.vx,s.vz-before.vz)>4&&Math.hypot(before.vx,before.vz)>3)event(a,Event.IMPACT,.5,gain,a.pan,.45);
                if(a.acoustic.surface==WheelEffects.Surface.WATER&&before!=null&&a.rough==0&&speed>2)event(a,Event.SPLASH,clamp(speed/24,.15,.65),gain,a.pan,1);
            }
            if(a.autoDoorClose>0&&time>=a.autoDoorClose){event(a,Event.DOOR_CLOSE,.5,gain,a.pan,.15);a.autoDoorClose=-1;}
            a.rough=a.acoustic.surface==WheelEffects.Surface.WATER?1:0;
            Double impact=impacts.remove(s.id);if(impact!=null&&!reset)event(a,Event.IMPACT,clamp(impact/12,.1,.85),gain,a.pan,.3);
            double coupled=Math.abs(forward)/profile.gearTops()[a.gear-1]*profile.limit()*.85;
            if(on&&!profile.electric()&&forward>=-.3&&grounded&&a.shift==0){int old=a.gear;
                if(coupled>profile.limit()*.86&&a.gear<profile.gearTops().length)a.gear++;
                else if(coupled<profile.limit()*(a.load>.8?.43:.30)&&a.gear>1)a.gear--;
                if(old!=a.gear){a.shift=.65;a.shifts++;if(!reset)event(a,old<a.gear?Event.GEAR_UP:Event.GEAR_DOWN,.19,gain,a.pan,.35);}
            }
            coupled=forward<-.3?Math.abs(forward)/5*profile.limit()*.7:Math.abs(forward)/profile.gearTops()[a.gear-1]*profile.limit()*.85;
            double free=profile.idle()+a.load*(profile.limit()-profile.idle())*.65;
            double clutch=grounded?clamp(Math.abs(forward)/1.9,0,1):0;
            double target=free*(1-clutch)+Math.max(profile.idle(),coupled)*clutch;
            if(local&&controls.drift>.3&&a.load>.2)target+=controls.drift*profile.limit()*.09;
            if(a.shift>.46)target*=.77;
            target=clamp(target,profile.idle()*.9,profile.limit());
            a.rpm+=(target-a.rpm)*(1-Math.exp(-dt/(target>a.rpm?.12:.19)));
            a.engine+=( (on?1:0)-a.engine)*(1-Math.exp(-dt/(on?.3:.16)));
            double side=Math.abs(s.vx*Math.cos(s.yaw)-s.vz*Math.sin(s.yaw));
            double skid=grounded?clamp((side-.8)/4.5,0,1):0;
            if(grounded&&a.handbrake&&speed>2)skid=Math.max(skid,clamp(speed/16,0,1));
            double rolling=grounded?clamp(speed/18,0,1):0;
            double overrun=on?clamp((a.rpm-profile.idle())/(profile.limit()-profile.idle()),0,1)*(1-a.load):0;
            double scrape=grounded&&Math.abs(s.roll)>1.1?clamp(speed/8,0,1):0;
            voices.add(new Voice(s.id,s.model,profile,a.rpm,a.load,overrun,a.engine*(a.shift>.46?.72:1),speed,Math.max(0,-forward),rolling,skid,scrape,a.acoustic.surface,gain,a.pan,a.doppler,cutoff,cabin));
            a.previous=s.copy();
        }
        states.keySet().retainAll(active);impacts.keySet().retainAll(active);
        latest=new Scene(frame,List.copyOf(voices),List.copyOf(events),clamp(master,0,1),clamp(effects,0,1));events.clear();return latest;
    }
    private static double distance(VehicleWire s,Listener e){return Math.pow(s.x-e.x,2)+Math.pow(s.y-e.y,2)+Math.pow(s.z-e.z,2);}
    void clear(){states.clear();events.clear();impacts.clear();latest=Scene.silent();}
    Map<String,Object> inspect(){var out=new LinkedHashMap<String,Object>();out.put("sources",states.size());out.put("events",emitted);out.put("dropped",dropped);out.put("queryErrors",queryErrors);out.put("eventCounts",counts.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().name(),Map.Entry::getValue)));out.put("vehicles",states.values().stream().map(a->Map.of("id",a.id.toString(),"model",a.model,"family",a.profile.family(),"rpm",a.rpm,"gear",a.gear,"load",a.load,"shifts",a.shifts,"occluded",a.acoustic.obstructed)).toList());return out;}
}
