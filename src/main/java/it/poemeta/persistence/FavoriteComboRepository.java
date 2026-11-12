package it.poemeta.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteComboRepository extends JpaRepository<FavoriteComboEntity, Long> {

    List<FavoriteComboEntity> findByLeagueOrderByAscendancyAscSkillAsc(String league);

    Optional<FavoriteComboEntity> findByLeagueAndAscendancyAndSkill(String league, String ascendancy, String skill);
}
