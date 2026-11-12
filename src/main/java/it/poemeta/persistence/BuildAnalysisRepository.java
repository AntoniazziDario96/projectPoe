package it.poemeta.persistence;

import it.poemeta.persistence.BuildAnalysisEntity.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BuildAnalysisRepository extends JpaRepository<BuildAnalysisEntity, Long> {

    List<BuildAnalysisEntity> findByLeagueAndAscendancyAndSkillOrderByCreatedAtDesc(
            String league, String ascendancy, String skill);

    List<BuildAnalysisEntity> findByLeagueAndStatusOrderByCreatedAtDesc(String league, Status status);

    List<BuildAnalysisEntity> findByLeagueAndStatusInOrderByCreatedAtAsc(String league, Collection<Status> statuses);

    List<BuildAnalysisEntity> findByStatusIn(Collection<Status> statuses);

    Optional<BuildAnalysisEntity> findFirstByLeagueAndAscendancyAndSkillAndStatusInOrderByCreatedAtDesc(
            String league, String ascendancy, String skill, Collection<Status> statuses);
}
