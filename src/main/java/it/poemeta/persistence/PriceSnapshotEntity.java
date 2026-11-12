package it.poemeta.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Un prezzo salvato in un certo momento: serve per lo storico oltre i 7 giorni di poe.ninja. */
@Entity
@Table(name = "price_snapshot", indexes = {
        @Index(name = "idx_price_lookup", columnList = "league,category,name,takenAt"),
        @Index(name = "idx_price_taken", columnList = "takenAt")
})
public class PriceSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String league;
    private String category;
    private String name;
    private double chaos;
    private Instant takenAt;

    protected PriceSnapshotEntity() {
    }

    public PriceSnapshotEntity(String league, String category, String name, double chaos, Instant takenAt) {
        this.league = league;
        this.category = category;
        this.name = name;
        this.chaos = chaos;
        this.takenAt = takenAt;
    }

    public Long getId() {
        return id;
    }

    public String getLeague() {
        return league;
    }

    public String getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public double getChaos() {
        return chaos;
    }

    public Instant getTakenAt() {
        return takenAt;
    }
}
