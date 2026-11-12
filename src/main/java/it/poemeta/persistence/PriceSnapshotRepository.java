package it.poemeta.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshotEntity, Long> {

    List<PriceSnapshotEntity> findByLeagueAndCategoryAndNameAndTakenAtAfterOrderByTakenAtAsc(
            String league, String category, String name, Instant after);

    Optional<PriceSnapshotEntity> findTopByLeagueOrderByTakenAtDesc(String league);

    @Modifying
    @Transactional
    @Query("delete from PriceSnapshotEntity p where p.takenAt < :before")
    int deleteOlderThan(Instant before);
}
