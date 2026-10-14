package ru.serega6531.packmate.service.optimization.tls.keys;

import ru.serega6531.packmate.service.optimization.tls.keys.enums.CurveType;
import ru.serega6531.packmate.service.optimization.tls.keys.enums.NamedCurve;
import ru.serega6531.packmate.service.optimization.tls.keys.enums.SignatureScheme;

public class EcdheServerParams {

    private final CurveType curveType;
    private final NamedCurve namedCurve;
    private final byte[] pubkey;
    private final SignatureScheme signatureScheme;
    private final byte[] signature;

    public EcdheServerParams(CurveType curveType, NamedCurve namedCurve, byte[] pubkey,
                             SignatureScheme signatureScheme,
                             byte[] signature) {
        this.curveType = curveType;
        this.namedCurve = namedCurve;
        this.pubkey = pubkey;
        this.signatureScheme = signatureScheme;
        this.signature = signature;
    }

    public CurveType getCurveType() {
        return curveType;
    }

    public NamedCurve getNamedCurve() {
        return namedCurve;
    }

    public byte[] getPubkey() {
        return pubkey;
    }

    public SignatureScheme getSignatureScheme() {
        return signatureScheme;
    }

    public byte[] getSignature() {
        return signature;
    }
}
