package it.poemeta.ninja;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lettore minimale del formato binario protobuf, senza schema.
 * poe.ninja restituisce la ricerca delle build in protobuf: qui leggiamo i campi
 * per numero, che è tutto quello che ci serve.
 */
public final class ProtoReader {

    /** Un campo letto: numero, tipo di codifica e valore (varint oppure intervallo di byte). */
    public record Field(int number, int wireType, long varint, byte[] buf, int start, int end) {

        public String string() {
            return new String(buf, start, end - start, StandardCharsets.UTF_8);
        }

        public List<Field> message() {
            return ProtoReader.read(buf, start, end);
        }

        public boolean isBytes() {
            return wireType == 2;
        }
    }

    private ProtoReader() {
    }

    public static List<Field> read(byte[] buf) {
        return read(buf, 0, buf.length);
    }

    public static List<Field> read(byte[] buf, int start, int end) {
        List<Field> out = new ArrayList<>();
        int[] pos = {start};
        while (pos[0] < end) {
            long tag = readVarint(buf, pos);
            int number = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (wire) {
                case 0 -> out.add(new Field(number, 0, readVarint(buf, pos), buf, 0, 0));
                case 1 -> {
                    out.add(new Field(number, 1, 0, buf, pos[0], pos[0] + 8));
                    pos[0] += 8;
                }
                case 2 -> {
                    int len = (int) readVarint(buf, pos);
                    if (len < 0 || pos[0] + len > end) {
                        throw new NinjaException("Protobuf non valido (lunghezza)");
                    }
                    out.add(new Field(number, 2, 0, buf, pos[0], pos[0] + len));
                    pos[0] += len;
                }
                case 5 -> {
                    out.add(new Field(number, 5, 0, buf, pos[0], pos[0] + 4));
                    pos[0] += 4;
                }
                default -> throw new NinjaException("Protobuf non valido (wire type " + wire + ")");
            }
        }
        return out;
    }

    public static long readVarint(byte[] buf, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= buf.length) {
                throw new NinjaException("Protobuf troncato");
            }
            int b = buf[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new NinjaException("Varint troppo lungo");
            }
        }
    }

    /** Primo campo con quel numero, oppure null. */
    public static Field first(List<Field> fields, int number) {
        for (Field f : fields) {
            if (f.number() == number) {
                return f;
            }
        }
        return null;
    }

    public static String firstString(List<Field> fields, int number) {
        Field f = first(fields, number);
        return f == null ? null : f.string();
    }

    public static long firstVarint(List<Field> fields, int number) {
        Field f = first(fields, number);
        return f == null ? 0 : f.varint();
    }
}
