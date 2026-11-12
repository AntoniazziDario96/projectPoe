package it.poemeta.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** Un'analisi di una coppia skill + ascendancy (in coda, in corso o completata). */
@Entity
@Table(name = "build_analysis", indexes = @Index(name = "idx_analysis_combo", columnList = "league,ascendancy,skill"))
public class BuildAnalysisEntity {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String league;
    private String ascendancy;
    private String skill;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int sampleTarget;
    private int progress;
    private int progressTotal;
    private long comboTotal;
    private String message;
    private Instant createdAt;
    private Instant finishedAt;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String resultJson;

    protected BuildAnalysisEntity() {
    }

    public BuildAnalysisEntity(String league, String ascendancy, String skill, int sampleTarget) {
        this.league = league;
        this.ascendancy = ascendancy;
        this.skill = skill;
        this.sampleTarget = sampleTarget;
        this.status = Status.QUEUED;
        this.createdAt = Instant.now();
        this.message = "status.queued";
    }

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.RUNNING;
    }

    public int getProgressPercent() {
        return progressTotal <= 0 ? 0 : Math.min(100, progress * 100 / progressTotal);
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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getSampleTarget() {
        return sampleTarget;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public int getProgressTotal() {
        return progressTotal;
    }

    public void setProgressTotal(int progressTotal) {
        this.progressTotal = progressTotal;
    }

    public long getComboTotal() {
        return comboTotal;
    }

    public void setComboTotal(long comboTotal) {
        this.comboTotal = comboTotal;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }
}
