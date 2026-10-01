package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.BackupRepo;

import java.util.Optional;

@Repository
public interface BackupRepoRepository extends JpaRepository<BackupRepo, Long> {

    Optional<BackupRepo> findByName(String name);
}
