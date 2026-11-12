package it.poemeta.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Personaggio del giocatore importato per il confronto. Quelli non salvati ({@code saved = false})
 * servono solo a mostrare il risultato e vengono cancellati dopo qualche giorno.
 */
@Entity
@Table(name = "saved_character")
public class SavedCharacterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private boolean saved;
    private String label;
    /** "pob" oppure "ninja". */
    private String source;
    /** Lega poe.ninja da cui è stato importato (per riscaricarlo). */
    private String league;
    private String account;
    private String characterName;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String pobCode;

    /** Il personaggio importato ({@code ImportedCharacter}) in JSON. */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String characterJson;

    /** Ultima coppia, archetipo e tier scelti per il confronto. */
    private String ascendancy;
    private String skill;
    private Integer archetypeIndex;
    private String tier;

    /** Numero di cambi consigliati all'ultimo confronto e a quello prima dell'ultimo aggiornamento. */
    private Integer lastCount;
    private Integer previousCount;

    private Instant createdAt;
    private Instant updatedAt;

    protected SavedCharacterEntity() {
    }

    public SavedCharacterEntity(String source) {
        this.source = source;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public boolean isSaved() {
        return saved;
    }

    public void setSaved(boolean saved) {
        this.saved = saved;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getSource() {
        return source;
    }

    public String getLeague() {
        return league;
    }

    public void setLeague(String league) {
        this.league = league;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getCharacterName() {
        return characterName;
    }

    public void setCharacterName(String characterName) {
        this.characterName = characterName;
    }

    public String getPobCode() {
        return pobCode;
    }

    public void setPobCode(String pobCode) {
        this.pobCode = pobCode;
    }

    public String getCharacterJson() {
        return characterJson;
    }

    public void setCharacterJson(String characterJson) {
        this.characterJson = characterJson;
    }

    public String getAscendancy() {
        return ascendancy;
    }

    public void setAscendancy(String ascendancy) {
        this.ascendancy = ascendancy;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public Integer getArchetypeIndex() {
        return archetypeIndex;
    }

    public void setArchetypeIndex(Integer archetypeIndex) {
        this.archetypeIndex = archetypeIndex;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public Integer getLastCount() {
        return lastCount;
    }

    public void setLastCount(Integer lastCount) {
        this.lastCount = lastCount;
    }

    public Integer getPreviousCount() {
        return previousCount;
    }

    public void setPreviousCount(Integer previousCount) {
        this.previousCount = previousCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
