package it.poemeta.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Profilo di un personaggio già scaricato (in forma ridotta, JSON),
 * così non lo riscarichiamo a ogni analisi.
 */
@Entity
@Table(name = "cached_character",
        uniqueConstraints = @UniqueConstraint(name = "uk_character", columnNames = {"league", "account", "name"}))
public class CachedCharacterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String league;
    private String account;
    private String name;
    private Instant fetchedAt;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String profileJson;

    protected CachedCharacterEntity() {
    }

    public CachedCharacterEntity(String league, String account, String name) {
        this.league = league;
        this.account = account;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getLeague() {
        return league;
    }

    public String getAccount() {
        return account;
    }

    public String getName() {
        return name;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getProfileJson() {
        return profileJson;
    }

    public void setProfileJson(String profileJson) {
        this.profileJson = profileJson;
    }
}
