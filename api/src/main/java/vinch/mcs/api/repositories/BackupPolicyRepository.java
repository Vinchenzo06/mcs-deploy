package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.BackupPolicy;

import java.util.List;
import java.util.Optional;

@Repository
public interface BackupPolicyRepository extends JpaRepository<BackupPolicy, Long> {

    Optional<BackupPolicy> findByScope(String scope);

    List<BackupPolicy> findByScopeStartingWith(String prefix);

    void deleteByScope(String scope);
}
