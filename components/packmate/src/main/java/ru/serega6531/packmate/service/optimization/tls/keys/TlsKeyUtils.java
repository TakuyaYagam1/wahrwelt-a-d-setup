package ru.serega6531.packmate.service.optimization.tls.keys;

import ru.serega6531.packmate.service.optimization.tls.keys.enums.CurveType;
import ru.serega6531.packmate.service.optimization.tls.keys.enums.NamedCurve;
import ru.serega6531.packmate.service.optimization.tls.keys.enums.SignatureScheme;

import java.nio.ByteBuffer;

/**
 * Формат ключа нельзя определить только по записи KeyExchange.
 * Этот класс разбирает параметры ключей в TLS-трафике.
 */
public final class TlsKeyUtils {

    // https://wiki.osdev.org/TLS_Handshake

    public static DhClientParams parseServerDH(byte[] rawData, int offset) {
        ByteBuffer bb = ByteBuffer.wrap(rawData).position(offset);

        short pLength = bb.getShort();
        byte[] p = new byte[pLength];
        bb.get(p);

        short gLength = bb.getShort();
        byte[] g = new byte[gLength];
        bb.get(g);

        short pubKeyLength = bb.getShort();
        byte[] pubKey = new byte[pubKeyLength];  // Значение Ys.
        bb.get(pubKey);

        SignatureScheme signatureScheme = SignatureScheme.findByValue(bb.getShort());

        if (signatureScheme == null) {
            throw new IllegalArgumentException("Неизвестный алгоритм подписи.");
        }

        short signatureLength = bb.getShort();
        byte[] signature = new byte[signatureLength];

        bb.get(signature);

        return new DhClientParams(p, g, pubKey, signatureScheme, signature);
    }

    /**
     * @param rawData содержимое записи рукопожатия
     */
    public static EcdheServerParams parseServerECDHE(byte[] rawData, int offset) {
        ByteBuffer bb = ByteBuffer.wrap(rawData).position(offset);

        byte curveTypeId = bb.get();
        if(curveTypeId != 0x03) {
            throw new IllegalArgumentException("Тип эллиптической кривой не поддерживается.");
        }

        CurveType curveType = CurveType.NAMED;
        NamedCurve namedCurve = NamedCurve.findByValue(bb.getShort());

        if (namedCurve == null) {
            throw new IllegalArgumentException("Именованная эллиптическая кривая не поддерживается.");
        }

        byte pubkeyLength = bb.get();
        byte[] pubkey = new byte[pubkeyLength];
        bb.get(pubkey);

        SignatureScheme signatureScheme = SignatureScheme.findByValue(bb.getShort());

        if (signatureScheme == null) {
            throw new IllegalArgumentException("Неизвестный алгоритм подписи.");
        }

        short signatureLength = bb.getShort();
        byte[] signature = new byte[signatureLength];

        bb.get(signature);

        return new EcdheServerParams(curveType, namedCurve, pubkey, signatureScheme, signature);
    }

    // https://ldapwiki.com/wiki/ClientKeyExchange

    /**
     * Подходит для DH и ECDHE.
     * @param rawData содержимое записи рукопожатия
     */
    public static byte[] getClientDHPubkey(byte[] rawData, int offset) {
        ByteBuffer bb = ByteBuffer.wrap(rawData).position(offset);

        byte length = bb.get();
        byte[] pubkey = new byte[length];
        bb.get(pubkey);

        return pubkey;
    }

    public static byte[] getClientRsaPreMaster(byte[] rawData, int offset) {
        ByteBuffer bb = ByteBuffer.wrap(rawData).position(offset);

        int length = bb.getShort();
        byte[] encryptedPreMaster = new byte[length];
        bb.get(encryptedPreMaster);

        return encryptedPreMaster;
    }

}
