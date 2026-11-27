package szserver.voice;

import szserver.net.AccountIdentity;
import szserver.net.Wire;
import java.io.IOException;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Proof of possession for a host key. The broker only issues tokens inside that key's namespace. */
final class VoiceHostProof {
    static final String PATH = "/v1/host-token";
    static final String KEY_HEADER = "X-BSCP-Host-Key";
    static final String SIGNATURE_HEADER = "X-BSCP-Signature";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    record Request(VoicePackets.Action action, String player, UUID nonce) {}

    static KeyPair identity() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static String publicKey(KeyPair identity) {
        return ENCODER.encodeToString(identity.getPublic().getEncoded());
    }

    static byte[] encode(VoicePackets.Action action, String player) {
        return Wire.bytes(out -> {
            out.writeUTF("BSCP-VIVOX-1");
            out.writeLong(Instant.now().getEpochSecond());
            out.writeUTF(UUID.randomUUID().toString());
            out.writeUTF(action.name());
            out.writeUTF(player);
        });
    }

    static String sign(KeyPair identity, byte[] body) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(identity.getPrivate());
        signature.update(body);
        return ENCODER.encodeToString(signature.sign());
    }

    static String verify(String keyHeader, String proof, byte[] body) throws GeneralSecurityException {
        if (keyHeader == null || keyHeader.length() != 59 || proof == null || proof.length() != 86)
            throw new SignatureException("voice_host_proof");
        byte[] encoded = Base64.getUrlDecoder().decode(keyHeader);
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        if (!MessageDigest.isEqual(key.getEncoded(), encoded)) throw new SignatureException("voice_host_key");
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(key);
        signature.update(body);
        if (!signature.verify(Base64.getUrlDecoder().decode(proof))) throw new SignatureException("voice_host_signature");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded), 0, 16);
    }

    static Request decode(byte[] body, long now) throws IOException {
        var in = Wire.input(body);
        if (!in.readUTF().equals("BSCP-VIVOX-1")) throw new IOException("voice_host_version");
        long stamp = in.readLong();
        if (stamp < now - 45 || stamp > now + 45) throw new IOException("voice_host_expired");
        UUID nonce = UUID.fromString(in.readUTF());
        var action = VoicePackets.Action.valueOf(in.readUTF());
        String player = AccountIdentity.name(in.readUTF());
        if (in.available() != 0) throw new IOException("voice_host_trailing_data");
        return new Request(action, player, nonce);
    }
}
