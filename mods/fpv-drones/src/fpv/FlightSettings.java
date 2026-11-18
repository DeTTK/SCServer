package fpv;
import szmods.api.SettingsRegistry;

/** Independent pilot response profiles; values are read live on the client. */
final class FlightSettings {
 final SettingsRegistry.NumberSetting roll,pitch,yaw,rate,expo,deadzone,smoothing,thrust,drag,speed,throttleExpo,throttleLimit;
 final SettingsRegistry.NumberSetting tilt,climb,turn,braking;
 final SettingsRegistry.NumberSetting motorRise,motorFall,mass,response;
 final SettingsRegistry.ChoiceSetting preset;
 FlightSettings(SettingsRegistry.SettingsTab tab,boolean hold){
  String p=hold?"hold-":"";
  tab.section(hold?"hold-response":"acro-response",hold?"HOLD — управление":"ACRO — управление");
  preset=tab.choiceSetting(p+"preset","Применить профиль","Свои",java.util.List.of("Свои","Точные манёвры","Сбалансированный","Спорт"));
  roll=tab.numberSetting(p+"roll-sensitivity","Чувствительность крена, %",100.,1.,150.);
  pitch=tab.numberSetting(p+"pitch-sensitivity","Чувствительность тангажа, %",100.,1.,150.);
  yaw=tab.numberSetting(p+"yaw-sensitivity","Чувствительность рыскания, %",100.,1.,150.);
  rate=tab.numberSetting(p+"rates",hold?"Множитель скорости рыскания":"RC rate",1.15,.1,3.);
  expo=tab.numberSetting(p+"expo","Экспонента стиков",.25,0.,.95);
  deadzone=tab.numberSetting(p+"deadzone","Мёртвая зона стиков, %",8.,0.,25.);
  smoothing=tab.numberSetting(p+"smoothing","Сглаживание управления, мс",60.,0.,300.);
  thrust=tab.numberSetting(p+"thrust","Тяга моторов, %",100.,35.,150.);
  drag=tab.numberSetting(p+"drag","Сопротивление воздуха, %",100.,20.,400.);
  speed=tab.numberSetting(p+"speed-limit","Ограничение скорости с газом, км/ч (0 — выкл)",0.,0.,200.);
  throttleExpo=tab.numberSetting(p+"throttle-expo","Плавность газа",0.,0.,1.);
  throttleLimit=tab.numberSetting(p+"throttle-limit","Максимальный газ, %",100.,50.,100.);
  tilt=hold?tab.numberSetting("hold-tilt","Максимальный наклон, градусы",23.,3.,45.):null;
  climb=hold?tab.numberSetting("hold-climb","Скорость подъёма/снижения, м/с",3.,.3,12.):null;
  turn=hold?tab.numberSetting("hold-turn","Скорость поворота, градусы/с",86.,10.,300.):null;
  braking=hold?tab.numberSetting("hold-braking","Торможение и удержание, %",100.,20.,200.):null;
  motorRise=tab.numberSetting(p+"motor-rise","Разгон моторов, мс",55.,10.,250.);
  motorFall=tab.numberSetting(p+"motor-fall","Замедление моторов, мс",90.,10.,300.);
  mass=tab.numberSetting(p+"mass","Масса дрона, г",720.,350.,1200.);
  response=tab.numberSetting(p+"angular-response","Отклик вращения, %",100.,25.,200.);
  preset.onChange(value->{if(!value.equals("Свои")){applyPreset(value,hold);preset.set("Свои");}});
 }
 void applyPreset(String value,boolean hold){
  boolean precise=value.equals("Точные манёвры"),sport=value.equals("Спорт");
  roll.set(precise?40.:100.);pitch.set(precise?40.:100.);yaw.set(precise?45.:100.);
  rate.set(precise?.65:sport?1.5:1.15);expo.set(precise?.5:.25);deadzone.set(8.);smoothing.set(precise?100.:60.);
  thrust.set(precise?75.:100.);drag.set(precise?160.:100.);speed.set(precise?22.:sport?0.:65.);
  throttleExpo.set(precise?.45:0.);throttleLimit.set(100.);
  motorRise.set(55.);motorFall.set(90.);mass.set(720.);response.set(precise?70.:100.);
  if(hold){tilt.set(precise?12.:sport?35.:23.);climb.set(precise?1.:sport?6.:3.);turn.set(precise?55.:86.);braking.set(precise?140.:100.);}
 }
 void apply(FpvDroneMod.Flight flight){
  flight.smoothing=smoothing.get()/1000.;flight.thrustScale=thrust.get()/100.;flight.dragScale=drag.get()/100.;
  flight.speedLimit=speed.get()/3.6;flight.throttleLimit=throttleLimit.get()/100.;
  flight.motorRise=motorRise.get()/1000.;flight.motorFall=motorFall.get()/1000.;flight.mass=mass.get()/1000.;flight.angularResponse=response.get()/100.;
  if(tilt!=null){flight.holdTilt=Math.toRadians(tilt.get());flight.holdClimb=climb.get();flight.holdTurn=Math.toRadians(turn.get());flight.holdBrake=braking.get()/100.;}
 }
 static double shape(double value,double deadzone,double expo){
  double x=Math.max(0,Math.min(1,(Math.abs(value)-deadzone)/(1-deadzone)));
  return Math.copySign((1-expo)*x+expo*x*x*x,value);
 }
 static double throttle(double value,double expo,boolean hold){
  if(hold)return .5+shape(value*2-1,0,expo)*.5;
  return (1-expo)*value+expo*value*value;
 }
}
