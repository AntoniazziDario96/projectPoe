package it.poemeta.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Coppia skill + ascendancy segnata come preferita: si aggiorna da sola. */
@Entity
@Table(name = "favorite_combo",
        uniqueConstraints = @UniqueConstraint(name = "uk_favorite", columnNames = {"league", "ascendancy", "skill"}))
public class FavoriteComboEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String league;
    private String ascendancy;
    private String skill;
    private Instant createdAt;

    protected FavoriteComboEntity() {
    }

    public FavoriteComboEntity(String league, String ascendancy, String skill) {
        this.league = league;
        this.ascendancy = ascendancy;
        this.skill = skill;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getLeague() {
        return league;
    }

    public String getAscendancy() {
        return ascendancy;
    }

    public String getSkill() {
        return skill;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
