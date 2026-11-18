package szserver.net;

import java.math.BigInteger;
import java.util.*;

/** Old administrator time/weather syntax; values are normalized before durable admission. */
public final class EnvironmentCommands {
    public enum Weather {
        AUTO(null),CLEAR("ClearWeather"),RAIN("HeavyRain"),THUNDER("Thunderstorm"),SNOW("Snowstorm"),FOG("ThickFog"),STORM("Storm"),
        LIGHT_RAIN("LightRain"),CLOUDY("Cloudy"),CLOUDY_RAINY("CloudyRainy"),DRIZZLE("DrizzlingRain"),DOWNPOUR("Downpour"),LIGHT_FOG("LightFog"),
        EJECTION("Ejection"),PSY_EJECTION("PsyEjection"),ELECTRIC_STORM("Electricstorm");
        private final String phenomenon;
        Weather(String phenomenon){this.phenomenon=phenomenon;}
        public String phenomenon(){return phenomenon;}
        public String wireName(){return name().toLowerCase(Locale.ROOT);}
        public static Weather read(String value){for(Weather weather:values())if(weather.wireName().equalsIgnoreCase(value)||weather.phenomenon!=null&&weather.phenomenon.equalsIgnoreCase(value))return weather;throw new IllegalArgumentException("invalid_weather");}
    }
    public record Request(Long time,Long delta,Weather weather,int durationSeconds,Double clockSpeed){
        public Request(Long time,Long delta,Weather weather,int durationSeconds){this(time,delta,weather,durationSeconds,null);}
        public Request{if(clockSpeed!=null&&(!Double.isFinite(clockSpeed)||clockSpeed<0||clockSpeed>10000))throw new IllegalArgumentException("invalid_time_speed");if(time==null&&delta==null&&weather==null&&clockSpeed==null)throw new IllegalArgumentException("empty_world_environment_update");if(time!=null&&delta!=null)throw new IllegalArgumentException("conflicting_time_update");if(time!=null&&(time<0||time>=24000))throw new IllegalArgumentException("invalid_time");if(delta!=null&&(delta< -2147483647L||delta>2147483647L))throw new IllegalArgumentException("time_delta_out_of_bounds");if(durationSeconds<1||durationSeconds>86400)throw new IllegalArgumentException("weather_duration_out_of_bounds");}
    }
    private static final Map<String,Long> PRESETS=Map.of("sunrise",0L,"day",0L,"noon",6000L,"sunset",12000L,"night",13000L,"midnight",18000L);
    private EnvironmentCommands(){}
    private static String[] words(String text){String value=AccountIdentity.strip(text);if(value.length()>16384)throw new IllegalArgumentException("environment_command_limit");return value.split("[\\p{javaWhitespace}\\p{Z}\\x{85}]+");}
    /** Python int accepts Unicode decimal digits and internal underscore separators. */
    private static BigInteger integer(String value,String error){return CommandNumbers.integer(value,error);}
    public static boolean matches(String text){String first=words(text)[0];return first.equalsIgnoreCase("/time")||first.equalsIgnoreCase("/weather");}
    public static Request parse(String text){String[] parts=words(text);String verb=parts[0].toLowerCase(Locale.ROOT);
        if(verb.equals("/time")){if(parts.length==3&&parts[1].equalsIgnoreCase("speed")){try{return new Request(null,null,null,600,Double.valueOf(parts[2]));}catch(NumberFormatException error){throw new IllegalArgumentException("invalid_time_speed");}}String operation,value;if(parts.length==2&&!Set.of("set","add").contains(parts[1].toLowerCase(Locale.ROOT))){operation="set";value=parts[1];}else if(parts.length==3&&Set.of("set","add").contains(parts[1].toLowerCase(Locale.ROOT))){operation=parts[1].toLowerCase(Locale.ROOT);value=parts[2];}else throw new IllegalArgumentException("usage_time_set_or_add");
            Long preset=operation.equals("set")?PRESETS.get(value.toLowerCase(Locale.ROOT)):null;BigInteger ticks=preset==null?integer(value,"invalid_time"):BigInteger.valueOf(preset);if(operation.equals("set"))return new Request(ticks.mod(BigInteger.valueOf(24000)).longValue(),null,null,300);if(ticks.abs().compareTo(BigInteger.valueOf(2147483647))>0)throw new IllegalArgumentException("time_delta_out_of_bounds");return new Request(null,ticks.longValue(),null,300);
        }
        if(verb.equals("/weather")){if(parts.length!=2&&parts.length!=3)throw new IllegalArgumentException("usage_weather_type_seconds");Weather weather=Weather.read(parts[1]);BigInteger seconds=parts.length==2?BigInteger.valueOf(600):integer(parts[2],"invalid_weather_duration");if(seconds.compareTo(BigInteger.ONE)<0||seconds.compareTo(BigInteger.valueOf(86400))>0)throw new IllegalArgumentException("weather_duration_out_of_bounds");return new Request(null,null,weather,seconds.intValue());}
        throw new IllegalArgumentException("not_environment_command");
    }
}
