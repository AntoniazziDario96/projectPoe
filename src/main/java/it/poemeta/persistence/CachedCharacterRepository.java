package it.poemeta.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CachedCharacterRepository extends JpaRepository<CachedCharacterEntity, Long> {

    Optional<CachedCharacterEntity> findByLeagueAndAccountAndName(String league, String account, String name);
}
