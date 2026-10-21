package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ClientSettingChange;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientSettingChangeRepository extends JpaRepository<ClientSettingChange, UUID> {

    /** The latest change to one setting - "turned on by Ada, 3 Oct" on the settings page. */
    Optional<ClientSettingChange> findFirstByClientIdAndSettingOrderByChangedAtDesc(UUID clientId, String setting);

    List<ClientSettingChange> findAllByClientIdAndSettingOrderByChangedAtDesc(UUID clientId, String setting);

    boolean existsByClientIdAndSettingAndNewValue(UUID clientId, String setting, String newValue);
}
