package it.poemeta.ninja;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dizionario di stringhe di poe.ninja (formato binario "NDIC").
 * Nella ricerca delle build classi, gemme, ecc. sono indicate con un indice:
 * il dizionario trasforma l'indice nel nome.
 *
 * Struttura (little endian):
 * <pre>
 *  0  "NDIC"
 *  4  int32 versione
 *  12 int32 numero di stringhe N
 *  16 8 byte hash
 *  24 int32 dimensione del blocco
 *  28 int32 numero di blocchi B
 *  32 int32 N, 36 int32, 40 int32
 *  44 (B-1) coppie int32 (offset, indice) per l'accesso a blocchi
 *  ... N lunghezze (varint), poi le N stringhe UTF-8 concatenate
 * </pre>
 */
public final class NinjaDictionary {

    private final List<String> values;

    private NinjaDictionary(List<String> values) {
        this.values = values;
    }

    public static NinjaDictionary parse(byte[] data) {
        if (data.length < 44 || data[0] != 'N' || data[1] != 'D' || data[2] != 'I' || data[3] != 'C') {
            throw new NinjaException("Dizionario poe.ninja in formato sconosciuto");
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = bb.getInt(12);
        int blocks = bb.getInt(28);
        int[] pos = {44 + 8 * Math.max(0, blocks - 1)};
        int[] lengths = new int[count];
        for (int i = 0; i < count; i++) {
            lengths[i] = (int) ProtoReader.readVarint(data, pos);
        }
        List<String> values = new ArrayList<>(count);
        int p = pos[0];
        for (int len : lengths) {
            if (p + len > data.length) {
                throw new NinjaException("Dizionario poe.ninja troncato");
            }
            values.add(new String(data, p, len, StandardCharsets.UTF_8));
            p += len;
        }
        return new NinjaDictionary(values);
    }

    public String get(int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    public int size() {
        return values.size();
    }
}
