package ru.serega6531.packmate.service.optimization;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.tls.ExporterLabel;
import org.bouncycastle.tls.PRFAlgorithm;
import org.bouncycastle.tls.crypto.TlsSecret;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsSecret;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.util.ByteArrays;
import ru.serega6531.packmate.model.Packet;
import ru.serega6531.packmate.service.optimization.tls.TlsPacket;
import ru.serega6531.packmate.service.optimization.tls.keys.TlsKeyUtils;
import ru.serega6531.packmate.service.optimization.tls.numbers.CipherSuite;
import ru.serega6531.packmate.service.optimization.tls.numbers.ContentType;
import ru.serega6531.packmate.service.optimization.tls.numbers.HandshakeType;
import ru.serega6531.packmate.service.optimization.tls.records.ApplicationDataRecord;
import ru.serega6531.packmate.service.optimization.tls.records.HandshakeRecord;
import ru.serega6531.packmate.service.optimization.tls.records.handshakes.*;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class TlsDecryptor {

    private static final Pattern cipherSuitePattern = Pattern.compile("TLS_RSA_WITH_([A-Z0-9_]+)_[A-Z0-9]+");

    private final List<Packet> packets;
    private final RsaKeysHolder keysHolder;

    @Getter
    private boolean parsed = false;
    private List<Packet> result;

    private ListMultimap<Packet, TlsPacket.TlsHeader> tlsPackets;
    private byte[] clientRandom;
    private byte[] serverRandom;

    public void decryptTls() {
        tlsPackets = ArrayListMultimap.create(packets.size(), 1);

        try {
            for (Packet p : packets) {
                tlsPackets.putAll(p, createTlsHeaders(p));
            }
        } catch (IllegalRawDataException e) {
            log.warn("Не удалось разобрать TLS-пакеты", e);
            return;
        }

        var clientHelloOpt = getHandshake(HandshakeType.CLIENT_HELLO);
        var serverHelloOpt = getHandshake(HandshakeType.SERVER_HELLO);

        if (clientHelloOpt.isEmpty() || serverHelloOpt.isEmpty()) {
            return;
        }

        var clientHello = (ClientHelloHandshakeRecordContent) clientHelloOpt.get();
        var serverHello = (ServerHelloHandshakeRecordContent) serverHelloOpt.get();

        CipherSuite cipherSuite = serverHello.getCipherSuite();

        if (cipherSuite.name().startsWith("TLS_RSA_WITH_")) {
            Matcher matcher = cipherSuitePattern.matcher(cipherSuite.name());
            //noinspection ResultOfMethodCallIgnored
            matcher.find();
            String blockCipher = matcher.group(1);

            clientRandom = clientHello.getRandom();
            serverRandom = serverHello.getRandom();

            decryptTlsRsa(blockCipher);
        }
    }

    @SneakyThrows
    private void decryptTlsRsa(String blockCipher) {
        String[] blockCipherParts = blockCipher.split("_");
        String blockCipherAlgo = blockCipherParts[0];
        int blockCipherSize = Integer.parseInt(blockCipherParts[1]);
        String blockCipherMode = blockCipherParts[2];

        if (!blockCipherAlgo.equals("AES")) {
            return;
        }

        int keyLength = blockCipherSize / 8;

        Optional<RSAPublicKey> publicKeyOpt = getRsaPublicKey();

        if (publicKeyOpt.isEmpty()) {
            return;
        }

        RSAPublicKey publicKey = publicKeyOpt.get();
        RSAPrivateKey privateKey = keysHolder.getKey(publicKey.getModulus());
        if (privateKey == null) {
            log.warn("Не найден закрытый ключ RSA для модуля сертификата");
            return;
        }

        Optional<BcTlsSecret> preMasterOptional = getPreMaster(privateKey);
        if (preMasterOptional.isEmpty()) {
            return;
        }

        BcTlsSecret preMaster = preMasterOptional.get();

        byte[] randomCS = ArrayUtils.addAll(clientRandom, serverRandom);
        byte[] randomSC = ArrayUtils.addAll(serverRandom, clientRandom);

        TlsSecret masterSecret = preMaster.deriveUsingPRF(
                PRFAlgorithm.tls_prf_sha256, ExporterLabel.master_secret, randomCS, 48);
        byte[] expanded = masterSecret.deriveUsingPRF(
                PRFAlgorithm.tls_prf_sha256, ExporterLabel.key_expansion, randomSC, 72 + keyLength * 2).extract();

        byte[] clientMacKey = new byte[20];
        byte[] serverMacKey = new byte[20];
        byte[] clientEncryptionKey = new byte[keyLength];
        byte[] serverEncryptionKey = new byte[keyLength];
        byte[] clientIV = new byte[16];
        byte[] serverIV = new byte[16];

        ByteBuffer bb = ByteBuffer.wrap(expanded);
        bb.get(clientMacKey);
        bb.get(serverMacKey);
        bb.get(clientEncryptionKey);
        bb.get(serverEncryptionKey);
        bb.get(clientIV);
        bb.get(serverIV);

        Optional<Cipher> clientCipherOpt = createCipher(blockCipherMode, clientEncryptionKey, clientIV);
        Optional<Cipher> serverCipherOpt = createCipher(blockCipherMode, serverEncryptionKey, serverIV);

        if (clientCipherOpt.isEmpty() || serverCipherOpt.isEmpty()) {
            return;
        }

        Cipher clientCipher = clientCipherOpt.get();
        Cipher serverCipher = serverCipherOpt.get();

        result = new ArrayList<>(packets.size());

        for (Packet packet : packets) {
            List<TlsPacket.TlsHeader> tlsData = tlsPackets.get(packet);

            for (TlsPacket.TlsHeader tlsPacket : tlsData) {
                if (tlsPacket.getContentType() == ContentType.APPLICATION_DATA) {
                    byte[] data = ((ApplicationDataRecord) tlsPacket.getRecord()).getData();
                    boolean client = packet.isIncoming();

                    Cipher cipher = client ? clientCipher : serverCipher;
                    byte[] decoded = cipher.doFinal(data);

                    decoded = clearDecodedData(decoded);

                    result.add(
                            packet.toBuilder()
                                    .content(decoded)
                                    .tlsDecrypted(true)
                                    .build()
                    );
                }
            }
        }

        parsed = true;
    }

    @SneakyThrows(value = {NoSuchAlgorithmException.class, NoSuchPaddingException.class})
    private Optional<BcTlsSecret> getPreMaster(RSAPrivateKey privateKey) {
        Optional<HandshakeRecordContent> opt = getHandshake(HandshakeType.CLIENT_KEY_EXCHANGE);

        if (opt.isEmpty()) {
            return Optional.empty();
        }

        var clientKeyExchange = (BasicHandshakeRecordContent) opt.get();

        try {
            byte[] encryptedPreMaster = TlsKeyUtils.getClientRsaPreMaster(clientKeyExchange.getContent(), 0);

            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] preMaster = rsa.doFinal(encryptedPreMaster);
            return Optional.of(new BcTlsSecret(new BcTlsCrypto(null), preMaster));
        } catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException e) {
            log.warn("Не удалось получить предварительный ключ TLS", e);
            return Optional.empty();
        }
    }

    private Optional<RSAPublicKey> getRsaPublicKey() {
        var certificateHandshakeOpt = getHandshake(HandshakeType.CERTIFICATE);

        if (certificateHandshakeOpt.isEmpty()) {
            return Optional.empty();
        }

        var certificateHandshake = (CertificateHandshakeRecordContent) certificateHandshakeOpt.get();
        List<byte[]> chain = certificateHandshake.getRawCertificates();
        byte[] rawCertificate = chain.get(0);

        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate certificate = cf.generateCertificate(new ByteArrayInputStream(rawCertificate));
            RSAPublicKey publicKey = (RSAPublicKey) certificate.getPublicKey();
            return Optional.of(publicKey);
        } catch (CertificateException e) {
            log.warn("Не удалось прочитать сертификат TLS", e);
            return Optional.empty();
        }
    }

    @SneakyThrows(value = {NoSuchAlgorithmException.class, NoSuchPaddingException.class})
    private Optional<Cipher> createCipher(String mode, byte[] key, byte[] iv) {
        Cipher cipher = Cipher.getInstance("AES/" + mode + "/PKCS5Padding");
        SecretKeySpec serverSkeySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec serverIvParameterSpec = new IvParameterSpec(iv);

        try {
            cipher.init(Cipher.DECRYPT_MODE, serverSkeySpec, serverIvParameterSpec);

            return Optional.of(cipher);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            log.warn("Не удалось расшифровать TLS", e);
            return Optional.empty();
        }
    }

    private byte[] clearDecodedData(byte[] decoded) {
        int start = 16;
        int end = decoded.length - 21; // почему?)
        decoded = ByteArrays.getSubArray(decoded, start, end - start);
        return decoded;
    }

    private Optional<HandshakeRecordContent> getHandshake(HandshakeType handshakeType) {
        return tlsPackets.values().stream()
                .filter(p -> p.getContentType() == ContentType.HANDSHAKE)
                .map(p -> ((HandshakeRecord) p.getRecord()))
                .filter(r -> r.getHandshakeType() == handshakeType)
                .map(HandshakeRecord::getContent)
                .findFirst();
    }

    private List<TlsPacket.TlsHeader> createTlsHeaders(Packet p) throws IllegalRawDataException {
        List<TlsPacket.TlsHeader> headers = new ArrayList<>();
        TlsPacket tlsPacket = TlsPacket.newPacket(p.getContent(), 0, p.getContent().length);

        headers.add(tlsPacket.getHeader());

        while (tlsPacket.getPayload() != null) {
            tlsPacket = (TlsPacket) tlsPacket.getPayload();
            headers.add(tlsPacket.getHeader());
        }

        return headers;
    }

    public List<Packet> getParsedPackets() {
        if (!parsed) {
            throw new IllegalStateException("Данные TLS не разобраны.");
        }

        return result;
    }

}
