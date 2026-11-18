package scvehicles;

/** Acoustic/drivetrain presentation; never changes vehicle forces. */
record VehicleSoundProfile(String family,String bank,double idle,double limit,double[] anchors,
                           double[] gearTops,double volume,double tone,boolean airBrakes,boolean tracks,boolean rear) {
    private static final double[] PETROL={1000,2000,3000,4000,5000},DIESEL={630,1000,1500,2000,2350};
    static VehicleSoundProfile forModel(int id){
        VehicleFleet.Type t=VehicleFleet.get(id);String family,bank;double idle,limit,volume,tone;boolean air=false,rear=false;
        if(id==24){family="electric-toy";bank="toy";idle=500;limit=2800;volume=.55;tone=1;}
        else if(id==4){family="air-cooled-petrol";bank="su-low";idle=850;limit=4400;volume=.88;tone=1.04;rear=true;}
        else if(id==10||id==11){family=id==11?"diesel-bus":"diesel-truck";bank="ds8";idle=630;limit=2350;volume=1.05;tone=id==11?.95:1;air=true;rear=id==11;}
        else if(id==13||id==16){family="tractor-diesel";bank="ds8";idle=680;limit=2200;volume=.95;tone=id==13?1.08:.94;}
        else if(id==18||id==21||id==22){family="tracked-v12-diesel";bank="v12d";idle=650;limit=id==22?2400:2700;volume=1.08;tone=id==18?.93:id==22?1.04:.98;rear=true;}
        else if(id==20){family="heavy-v12-petrol";bank="v12d";idle=550;limit=3000;volume=1.05;tone=.74;rear=true;}
        else if(id==23){family="industrial-diesel";bank="ds8";idle=700;limit=2350;volume=.98;tone=1.08;rear=true;}
        else if(t.tracked()){family="heavy-tracked-diesel";bank="ds8";idle=630;limit=id==19?2100:2350;volume=1.1;tone=id==19?.82:.89;rear=true;}
        else if(t.kind.equals("truck")||t.kind.equals("bus")||t.kind.equals("armored")){
            family=t.kind.equals("armored")?"armored-petrol":"truck-petrol-v8";bank="gv8";idle=650;limit=3900;volume=1;tone=id==15?.92:1;air=t.kind.equals("truck")||t.kind.equals("bus");
        }else{family=id==5||id==6?"utility-petrol":"sedan-petrol";bank="tsu";idle=id==5||id==6?750:850;limit=id==5||id==6?4400:4900;volume=.88;tone=switch(id){case 0->.96;case 2,3->.93;case 5,6->.90;case 25->1.03;default->1;};}
        // Gear spacing is based on the current speed profile (including 0.8.2).
        double top=t.maxSpeed*.61;double[] gears=id==24?new double[]{top}:new double[]{top*.18,top*.32,top*.51,top*.74,top*1.07};
        return new VehicleSoundProfile(family,bank,idle,limit,bank.equals("ds8")?DIESEL:PETROL,gears,volume,tone,air,t.tracked(),rear);
    }
    boolean diesel(){return family.contains("diesel");}
    boolean electric(){return bank.equals("toy");}
    boolean hasDoor(int model){return model==0||VehicleFleet.get(model).door!=null;}
}
