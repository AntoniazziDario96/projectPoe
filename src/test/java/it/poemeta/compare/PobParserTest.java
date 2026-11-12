package it.poemeta.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.poemeta.compare.ImportedCharacter.EquippedItem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PobParserTest {

    /** Albero finto: nessun download nei test. */
    static final TreeData TREE = new TreeData(null, new ObjectMapper()) {
        @Override
        public List<String> keystones(List<Integer> nodes) {
            return nodes.contains(100) ? List.of("Chaos Inoculation") : List.of();
        }

        @Override
        public List<String> masteries(List<Integer> effectIds) {
            return effectIds.contains(555) ? List.of("+30 to maximum Life") : List.of();
        }

        @Override
        public boolean available() {
            return true;
        }
    };

    static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PathOfBuilding>
              <Build level="92" className="Witch" ascendClassName="Occultist" mainSocketGroup="2"
                     pantheonMajorGod="Lunaris" pantheonMinorGod="None">
                <PlayerStat stat="Life" value="1"/>
                <PlayerStat stat="EnergyShield" value="7012.4"/>
                <PlayerStat stat="FireResist" value="47"/>
                <PlayerStat stat="ColdResist" value="80"/>
                <PlayerStat stat="LightningResist" value="75"/>
                <PlayerStat stat="ChaosResist" value="-36"/>
                <PlayerStat stat="EffectiveSpellSuppressionChance" value="12"/>
              </Build>
              <Tree activeSpec="1">
                <Spec nodes="100,200,300" masteryEffects="{12125,555},{1,2}">
                  <Sockets><Socket nodeId="61419" itemId="4"/></Sockets>
                </Spec>
              </Tree>
              <Skills activeSkillSet="1">
                <SkillSet id="1">
                  <Skill enabled="true">
                    <Gem nameSpec="Frostblink" gemId="Metadata/Items/Gems/SkillGemFrostblink" enabled="true"/>
                  </Skill>
                  <Skill enabled="true">
                    <Gem nameSpec="Winter Orb" gemId="Metadata/Items/Gems/SkillGemWinterOrb" enabled="true"/>
                    <Gem nameSpec="Infused Channelling" gemId="Metadata/Items/Gems/SupportGemInfusedChannelling" enabled="true"/>
                    <Gem nameSpec="Hypothermia" gemId="Metadata/Items/Gems/SupportGemHypothermia" enabled="false"/>
                  </Skill>
                </SkillSet>
              </Skills>
              <Items activeItemSet="1">
                <Item id="1">
                  Rarity: RARE
            Doom Crown
            Hubris Circlet
            Energy Shield: 300
            Item Level: 84
            Quality: 20
            Implicits: 1
            {enchant}Winter Orb has +2 Maximum Stages
            {range:0.5}+93 to maximum Energy Shield
            {crafted}+(20-25)% to Fire Resistance
            {fractured}+42% to Cold Resistance
            Corrupted
                  <ModRange id="1" range="0.5"/>
                </Item>
                <Item id="2">
                  Rarity: UNIQUE
            Badge of the Brotherhood
            Turquoise Amulet
            Implicits: 1
            +28 to Dexterity and Intelligence
            Your Maximum Frenzy Charges is equal to your Maximum Power Charges
                </Item>
                <Item id="3">
                  Rarity: MAGIC
            Seething Divine Life Flask of Staunching
            Implicits: 0
            Grants Immunity to Bleeding for 6 seconds if used while Bleeding
                </Item>
                <Item id="4">
                  Rarity: UNIQUE
            Watcher's Eye
            Prismatic Jewel
            Implicits: 0
            5% increased maximum Energy Shield
                </Item>
                <ItemSet id="1">
                  <Slot name="Helmet" itemId="1"/>
                  <Slot name="Amulet" itemId="2"/>
                  <Slot name="Flask 1" itemId="3"/>
                  <Slot name="Boots" itemId="0"/>
                </ItemSet>
              </Items>
            </PathOfBuilding>
            """;

    @Test
    void parsesCharacterFromXml() {
        ImportedCharacter ch = new PobParser(TREE).parse(XML.strip());

        assertThat(ch.source()).isEqualTo("pob");
        assertThat(ch.mainSkill()).isEqualTo("Winter Orb");
        assertThat(ch.className()).isEqualTo("Witch");
        assertThat(ch.profile().ascendancy()).isEqualTo("Occultist");
        assertThat(ch.profile().level()).isEqualTo(92);
        assertThat(ch.profile().energyShield()).isEqualTo(7012);
        assertThat(ch.profile().pantheonMajor()).isEqualTo("Lunaris");
        assertThat(ch.profile().pantheonMinor()).isNull();
        assertThat(ch.profile().keystones()).containsExactly("Chaos Inoculation");
        assertThat(ch.profile().masteries()).containsExactly("+30 to maximum Life");
        // supporto disabilitato escluso, nome come su poe.ninja
        assertThat(ch.profile().supportsOf("Winter Orb")).containsExactly("Infused Channelling Support");

        assertThat(ch.profile().defence().fireRes()).isEqualTo(47);
        assertThat(ch.profile().defence().coldMax()).isEqualTo(80);
        assertThat(ch.profile().defence().chaosRes()).isEqualTo(-36);
        assertThat(ch.profile().defence().suppression()).isEqualTo(12);

        EquippedItem helm = ch.items().stream().filter(i -> "Helm".equals(i.slot())).findFirst().orElseThrow();
        assertThat(helm.isRare()).isTrue();
        assertThat(helm.baseType()).isEqualTo("Hubris Circlet");
        // niente implicit/enchant né "Corrupted"; intervalli sostituiti dal valore medio
        assertThat(helm.mods()).containsExactly("+93 to maximum Energy Shield", "+22.5% to Fire Resistance",
                "+42% to Cold Resistance");

        assertThat(ch.items()).extracting(EquippedItem::slot).containsExactlyInAnyOrder("Helm", "Amulet", "Flask", "Jewel");
        assertThat(ch.profile().uniques()).extracting(u -> u.name())
                .containsExactlyInAnyOrder("Badge of the Brotherhood", "Watcher's Eye");
        assertThat(ch.profile().rares()).hasSize(1);
    }

    @Test
    void decodesBase64Code() {
        String code = encode(XML.strip());
        String xml = PobCode.decode(code);
        assertThat(xml).contains("<PathOfBuilding>");
        assertThat(new PobParser(TREE).parse(xml).mainSkill()).isEqualTo("Winter Orb");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> PobCode.decode("not a pob code!!"))
                .isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> PobCode.decode(encode("<html>nope</html>")))
                .isInstanceOf(ImportException.class);
        // niente entità esterne
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<PathOfBuilding><Build level=\"&e;\"/></PathOfBuilding>";
        assertThatThrownBy(() -> new PobParser(TREE).parse(xxe)).isInstanceOf(ImportException.class);
    }

    @Test
    void recognisesShareLinks() {
        assertThat(PobCode.rawUrl("https://pobb.in/AbC_12-x")).contains("https://pobb.in/AbC_12-x/raw");
        assertThat(PobCode.rawUrl("https://pastebin.com/xYz123")).contains("https://pastebin.com/raw/xYz123");
        assertThat(PobCode.rawUrl("https://poe.ninja/pob/1a2b")).contains("https://poe.ninja/pob/raw/1a2b");
        assertThat(PobCode.rawUrl("https://example.com/x")).isEmpty();
    }

    static String encode(String xml) {
        Deflater d = new Deflater();
        d.setInput(xml.getBytes(StandardCharsets.UTF_8));
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        return Base64.getUrlEncoder().encodeToString(out.toByteArray());
    }
}
