package scvehicles;
/** Authored door channels on the character clip's normalized timeline (hinge angle, not a timer). */
final class VehicleDoorMotion {
    // Reviewed against the original hatchback hand path: pull frames 3..18,
    // reach back/push frames 30..40, release to the wheel after frame 43.
    private static final double[][] CAR_ENTER={{0,0},{3./78,0},{6./78,.15},{12./78,.82},{18./78,1},{28./78,1},{32./78,.92},{36./78,.65},{40./78,.13},{43./78,0},{1,0}};
    private static final double[][] CAR_EXIT={{0,0},{.035,0},{.12,.45},{.20,.95},{.26,1},{.74,1},{.89,.57},{.98,0},{1,0}};
    private static final double[][] TRUCK_ENTER={{0,0},{1./80,0},{6./80,.4},{12./80,.95},{15./80,1},{52./80,1},{56./80,.92},{61./80,.55},{65./80,.08},{67./80,0},{1,0}};
    private static final double[][] TRUCK_EXIT={{0,0},{.035,0},{.10,.35},{.20,.95},{.28,1},{.72,1},{.86,.75},{.98,0},{1,0}};
    static double sample(String clip,double phase){
        double[][] keys=switch(clip){case "car_enter"->CAR_ENTER;case "car_exit"->CAR_EXIT;case "truck_enter"->TRUCK_ENTER;case "truck_exit"->TRUCK_EXIT;default->null;};
        if(keys==null)return 0;double p=Math.max(0,Math.min(1,phase)),angle=0;
        for(int i=1;i<keys.length;i++)if(p<=keys[i][0]){double t=(p-keys[i-1][0])/(keys[i][0]-keys[i-1][0]);t=t*t*(3-2*t);angle=keys[i-1][1]+(keys[i][1]-keys[i-1][1])*t;break;}
        // Articulation applies smoothstep to a door fraction; invert it so the
        // angle channel, body phase and exit hand landmark describe one pose.
        double lo=0,hi=1;for(int i=0;i<22;i++){double t=(lo+hi)*.5;if(t*t*(3-2*t)<angle)lo=t;else hi=t;}return (lo+hi)*.5;
    }
    private VehicleDoorMotion(){}
}
