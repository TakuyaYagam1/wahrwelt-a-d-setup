package ru.serega6531.packmate.service.optimization.tls.records.handshakes;

import org.pcap4j.util.ByteArrays;
import ru.serega6531.packmate.service.optimization.tls.numbers.CipherSuite;
import ru.serega6531.packmate.service.optimization.tls.numbers.CompressionMethod;

import static org.pcap4j.util.ByteArrays.BYTE_SIZE_IN_BYTES;
import static org.pcap4j.util.ByteArrays.SHORT_SIZE_IN_BYTES;

public class ServerHelloHandshakeRecordContent extends HelloHandshakeRecordContent {

    /*
    0x0          - Случайное значение сервера
    0x20         - Длина идентификатора сеанса (sidl)
    0x21         - Идентификатор сеанса
    0x21+si      - Набор шифров
    0x23+sidl    - Метод сжатия
    0x24+sidl    - Длина списка расширений (el)
    0x26+sidl    - Расширения 1..N
    0x26+sidl+el - Конец записи
     */

    private static final int CIPHER_SUITE_OFFSET = HelloHandshakeRecordContent.SESSION_ID_OFFSET;  // + длина идентификатора сеанса
    private static final int COMPRESSION_METHOD_OFFSET = CIPHER_SUITE_OFFSET + SHORT_SIZE_IN_BYTES;  // + длина идентификатора сеанса
    private static final int EXTENSIONS_LENGTH_OFFSET = COMPRESSION_METHOD_OFFSET + BYTE_SIZE_IN_BYTES;  // + длина идентификатора сеанса
    private static final int EXTENSIONS_OFFSET = EXTENSIONS_LENGTH_OFFSET + SHORT_SIZE_IN_BYTES;  // + длина идентификатора сеанса

    private CipherSuite cipherSuite;
    private CompressionMethod compressionMethod;

    public static ServerHelloHandshakeRecordContent newInstance(byte[] rawData, int offset, int length) {
        ByteArrays.validateBounds(rawData, offset, length);
        return new ServerHelloHandshakeRecordContent(rawData, offset);
    }

    public ServerHelloHandshakeRecordContent(byte[] rawData, int offset) {
        readCommonPart(rawData, offset);

        this.cipherSuite = CipherSuite.getInstance(ByteArrays.getShort(rawData,
                CIPHER_SUITE_OFFSET + sessionIdLength + offset));
        this.compressionMethod = CompressionMethod.getInstance(ByteArrays.getByte(rawData,
                COMPRESSION_METHOD_OFFSET + sessionIdLength + offset));

        this.extensionsLength = ByteArrays.getShort(rawData,
                EXTENSIONS_LENGTH_OFFSET + sessionIdLength + offset);
        readExtensions(rawData, EXTENSIONS_OFFSET + sessionIdLength + offset, false);
    }

    public CipherSuite getCipherSuite() {
        return cipherSuite;
    }

    public CompressionMethod getCompressionMethod() {
        return compressionMethod;
    }

    @Override
    public String toString() {
        return super.toString() + "\n" +
                "    Набор шифров: " + cipherSuite.toString() + "\n" +
                "    Метод сжатия: " + compressionMethod.toString();
    }

}
