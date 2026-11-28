package szserver.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Same compact player label for token issuance and the native proximity HUD. */
public final class VoiceAccountNames {
    private VoiceAccountNames() {}

    public static String label(String player) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(player.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String scopedAccount(String namespace, String player) {
        if (namespace == null || !namespace.matches("[a-zA-Z0-9-]{1,60}\\.[a-f0-9]{32}")) return null;
        return "." + namespace + "." + label(player) + ".";
    }
}
