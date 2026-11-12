package it.poemeta.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface SavedCharacterRepository extends JpaRepository<SavedCharacterEntity, Long> {

    List<SavedCharacterEntity> findBySavedTrueOrderByUpdatedAtDesc();

    @Transactional
    long deleteBySavedFalseAndCreatedAtBefore(Instant limit);
}
