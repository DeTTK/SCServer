package szserver.net;

import java.util.*;

/** Entitlement is server configuration; a client-selected mode never grants it. */
public final class PlayerRules {
    public enum Mode { ADMIN, STALKER, DISABLED }
    private final boolean adminDefault;private final Set<UUID> administrators;private final boolean explicitAdministrators;private final Mode initial;
    private final boolean friendlyFire;
    public PlayerRules(boolean adminDefault,Set<UUID> administrators,boolean explicitAdministrators,Mode initial){this(adminDefault,administrators,explicitAdministrators,initial,false);}
    private PlayerRules(boolean adminDefault,Set<UUID> administrators,boolean explicitAdministrators,Mode initial,boolean friendlyFire){if(initial==Mode.DISABLED)throw new IllegalArgumentException("initial_mode_disabled");this.adminDefault=adminDefault;this.administrators=Set.copyOf(administrators);this.explicitAdministrators=explicitAdministrators;this.initial=initial;this.friendlyFire=friendlyFire;}
    public static PlayerRules development(){return new PlayerRules(true,Set.of(),false,Mode.STALKER);}
    public static PlayerRules configured(Properties config){String permission=config.getProperty("game.playerPermission","admin");if(!Set.of("admin","stalker").contains(permission))throw new IllegalArgumentException("player_permission");Set<UUID> admins=new HashSet<>();for(String value:config.getProperty("game.administratorCharacters","").split(","))if(!value.isBlank())admins.add(UUID.fromString(value.strip()));String mode=config.getProperty("game.initialMode","stalker");if(!Set.of("admin","stalker").contains(mode))throw new IllegalArgumentException("initial_gameplay_mode");return new PlayerRules(permission.equals("admin"),admins,config.containsKey("game.administratorCharacters"),mode.equals("admin")?Mode.ADMIN:Mode.STALKER,Boolean.parseBoolean(config.getProperty("game.friendlyFire","false").strip()));}
    public boolean friendlyFire(){return friendlyFire;}
    public boolean entitled(UUID character){return explicitAdministrators?administrators.contains(character):adminDefault;}
    public Mode initial(UUID character){return entitled(character)?initial:Mode.STALKER;}
    public Mode requested(UUID character,Mode requested){if(!entitled(character)||requested==Mode.DISABLED)throw new IllegalArgumentException("gameplay_mode_not_permitted");return requested;}
    /** This custom realm exposes developer scene tools to every authenticated character. */
    public boolean editor(UUID character,Set<UUID> editors){return character!=null;}
    public static Mode decode(byte[] body){if(body.length!=4)throw new IllegalArgumentException("gameplay_mode_size");int value=java.nio.ByteBuffer.wrap(body).getInt();if(value<0||value>=Mode.values().length)throw new IllegalArgumentException("gameplay_mode_ordinal");return Mode.values()[value];}
}
