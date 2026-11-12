package it.poemeta.ninja;

import it.poemeta.analysis.ModText;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NinjaFormatsTest {

    @Test
    void parsesDictionary() {
        List<String> words = List.of("Ascendant", "Assassin", "Berserker");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("NDIC".getBytes(StandardCharsets.US_ASCII));
        header.putInt(2).putInt(0).putInt(words.size()).putLong(123L).putInt(16).putInt(1)
                .putInt(words.size()).putInt(0).putInt(0);
        out.writeBytes(header.array());
        words.forEach(w -> out.write(w.length()));
        words.forEach(w -> out.writeBytes(w.getBytes(StandardCharsets.UTF_8)));

        NinjaDictionary dict = NinjaDictionary.parse(out.toByteArray());
        assertThat(dict.size()).isEqualTo(3);
        assertThat(dict.get(2)).isEqualTo("Berserker");
        assertThat(dict.get(5)).isNull();
    }

    @Test
    void readsProtobufFields() {
        // campo 1 varint 150, campo 2 stringa "ab"
        byte[] data = {0x08, (byte) 0x96, 0x01, 0x12, 0x02, 'a', 'b'};
        List<ProtoReader.Field> fields = ProtoReader.read(data);
        assertThat(ProtoReader.firstVarint(fields, 1)).isEqualTo(150);
        assertThat(ProtoReader.firstString(fields, 2)).isEqualTo("ab");
    }

    @Test
    void flagsThinMarkets() {
        // caso reale: Fragment of Terror 12.427 c con 2.071 c di volume
        assertThat(NinjaEconomyClient.isThinMarket(12_427, 2_071)).isTrue();
        // Fragment of Knowledge 124 c con 248 c di volume: limite, almeno due pezzi scambiati
        assertThat(NinjaEconomyClient.isThinMarket(124, 249)).isFalse();
        assertThat(NinjaEconomyClient.isThinMarket(1, 50_000)).isFalse();
    }

    @Test
    void normalizesMods() {
        assertThat(ModText.normalize("+112 to maximum Life")).isEqualTo("+# to maximum Life");
        assertThat(ModText.normalize("Adds 10 to 20 Fire Damage to Attacks")).isEqualTo("Adds # to # Fire Damage to Attacks");
        assertThat(ModText.firstNumber("+14.5% to Fire Resistance")).isEqualTo(14.5);
    }
}
