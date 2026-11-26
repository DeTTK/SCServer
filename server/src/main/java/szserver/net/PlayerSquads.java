package szserver.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class PlayerSquads {
    private static final int CAPACITY = 5;
    private static final long INVITE_NANOS = 60_000_000_000L;
    private final Map<UUID, Squad> memberships = new LinkedHashMap<>();
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();
    // Grenade areas can preserve the owner's squad across a restart.
    private long nextId=ThreadLocalRandom.current().nextLong(1L<<50);

    record Player(UUID session, String name) {}
    private record Invitation(Squad squad, Player inviter, Player target, long deadline) {}
    record Roster(long id,String leader,List<Player> members,List<String> invited) {}
    private static final class Squad {
        final Map<UUID, Player> players = new LinkedHashMap<>();
        final long id;
        Squad(long id){this.id=id;}
    }

    void create(Player owner) {
        if (memberships.containsKey(owner.session())) throw new IllegalArgumentException("Вы уже в отряде.");
        join(new Squad(++nextId), owner);
    }

    long invite(Player owner, Player target, long now) {
        if(owner.session().equals(target.session()))throw new IllegalArgumentException("Выберите другого игрока.");
        if(memberships.containsKey(target.session()))throw new IllegalArgumentException("Игрок уже состоит в отряде.");
        var current=invitations.get(target.session());
        Squad squad=memberships.get(owner.session());
        if(current!=null&&now<current.deadline()&&current.squad()!=squad)
            throw new IllegalArgumentException("У игрока уже есть активное приглашение.");
        if(squad==null){create(owner);squad=memberships.get(owner.session());}
        if (!leader(squad).equals(owner.session())) throw new IllegalArgumentException("Приглашать может командир отряда.");
        if (squad.players.size() >= CAPACITY) throw new IllegalArgumentException("В отряде нет свободных мест.");
        if(current!=null&&now<current.deadline())throw new IllegalArgumentException("Приглашение уже отправлено.");
        invitations.put(target.session(), new Invitation(squad, owner, target, now+INVITE_NANOS));
        return squad.id;
    }

    void accept(Player player,long id, long now) {
        if (memberships.containsKey(player.session())) throw new IllegalArgumentException("Вы уже в отряде.");
        Invitation invitation = invitations.get(player.session());
        if (invitation == null || now >= invitation.deadline()
                || invitation.squad().id!=id || memberships.get(invitation.inviter().session()) != invitation.squad()
                || !leader(invitation.squad()).equals(invitation.inviter().session()))
            throw new IllegalArgumentException("Активного приглашения нет.");
        if (invitation.squad().players.size() >= CAPACITY) throw new IllegalArgumentException("В отряде нет свободных мест.");
        join(invitation.squad(), player);
    }

    void leave(Player player) {
        invitations.remove(player.session());
        Squad squad = memberships.remove(player.session());
        if (squad == null) return;
        squad.players.remove(player.session());
        invitations.values().removeIf(invite -> invite.inviter().session().equals(player.session()));
    }

    List<UUID> members(Player player) {
        Squad squad = memberships.get(player.session());
        return squad == null ? List.of() : List.copyOf(squad.players.keySet());
    }
    Long id(UUID session) {
        Squad squad=memberships.get(session);
        return squad==null?null:squad.id;
    }

    Roster roster(Player player,long now) {
        Squad squad=memberships.get(player.session());
        if(squad==null)return null;
        var invited=invitations.values().stream().filter(invite->invite.squad()==squad&&now<invite.deadline())
            .map(invite->invite.target().name()).toList();
        return new Roster(squad.id,squad.players.get(leader(squad)).name(),List.copyOf(squad.players.values()),invited);
    }

    void kick(Player owner,Player target){
        Squad squad=memberships.get(owner.session());
        if(squad==null||!leader(squad).equals(owner.session())||owner.session().equals(target.session()))
            throw new IllegalArgumentException("Действие доступно командиру отряда.");
        if(memberships.get(target.session())==squad){leave(target);return;}
        var invitation=invitations.get(target.session());
        if(invitation!=null&&invitation.squad()==squad)invitations.remove(target.session());
    }

    private static UUID leader(Squad squad) { return squad.players.keySet().iterator().next(); }

    private void join(Squad squad, Player player) {
        invitations.remove(player.session());
        squad.players.put(player.session(), player);
        memberships.put(player.session(), squad);
    }
}
