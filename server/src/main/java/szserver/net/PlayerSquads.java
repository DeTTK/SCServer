package szserver.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

final class PlayerSquads {
    private static final int CAPACITY = 5;
    private static final long INVITE_NANOS = 60_000_000_000L;
    private final Map<UUID, Squad> memberships = new LinkedHashMap<>();
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();

    record Player(UUID session, String name) {}
    private record Invitation(Squad squad, UUID inviter, long deadline) {}
    private static final class Squad {
        final Map<UUID, Player> players = new LinkedHashMap<>();
    }

    void create(Player owner) {
        if (memberships.containsKey(owner.session())) throw new IllegalArgumentException("Вы уже в отряде.");
        join(new Squad(), owner);
    }

    void invite(Player owner, Player target, long now) {
        Squad squad = memberships.get(owner.session());
        if (squad == null) throw new IllegalArgumentException("Сначала создайте отряд.");
        if (!leader(squad).equals(owner.session())) throw new IllegalArgumentException("Приглашать может командир отряда.");
        if (memberships.containsKey(target.session())) throw new IllegalArgumentException("Игрок уже состоит в отряде.");
        if (squad.players.size() >= CAPACITY) throw new IllegalArgumentException("В отряде нет свободных мест.");
        var current = invitations.get(target.session());
        if (current != null && now < current.deadline() && current.squad() != squad)
            throw new IllegalArgumentException("У игрока уже есть активное приглашение.");
        invitations.put(target.session(), new Invitation(squad, owner.session(), now+INVITE_NANOS));
    }

    String accept(Player player, long now) {
        if (memberships.containsKey(player.session())) throw new IllegalArgumentException("Вы уже в отряде.");
        Invitation invitation = invitations.remove(player.session());
        if (invitation == null || now >= invitation.deadline()
                || memberships.get(invitation.inviter()) != invitation.squad()
                || !leader(invitation.squad()).equals(invitation.inviter()))
            throw new IllegalArgumentException("Активного приглашения нет.");
        if (invitation.squad().players.size() >= CAPACITY) throw new IllegalArgumentException("В отряде нет свободных мест.");
        join(invitation.squad(), player);
        return roster(player);
    }

    void leave(Player player, BiConsumer<UUID,String> notify) {
        invitations.remove(player.session());
        Squad squad = memberships.remove(player.session());
        if (squad == null) return;
        squad.players.remove(player.session());
        invitations.values().removeIf(invite -> invite.inviter().equals(player.session()));
        if (squad.players.isEmpty()) return;
        String roster = roster(squad.players.get(leader(squad)));
        for (UUID session : new ArrayList<>(squad.players.keySet())) notify.accept(session, roster);
    }

    List<UUID> members(Player player) {
        Squad squad = memberships.get(player.session());
        return squad == null ? List.of() : List.copyOf(squad.players.keySet());
    }

    String roster(Player player) {
        Squad squad = memberships.get(player.session());
        return squad == null ? "Вы вне отряда." : "Отряд: "+String.join(", ",squad.players.values().stream()
            .map(member -> member.name()+(member.session().equals(leader(squad)) ? " (командир)" : "")).toList());
    }

    private static UUID leader(Squad squad) { return squad.players.keySet().iterator().next(); }

    private void join(Squad squad, Player player) {
        invitations.remove(player.session());
        squad.players.put(player.session(), player);
        memberships.put(player.session(), squad);
    }
}
