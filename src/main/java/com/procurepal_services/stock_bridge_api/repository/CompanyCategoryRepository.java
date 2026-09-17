package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.CompanyCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CompanyCategoryRepository extends JpaRepository<CompanyCategory, UUID> {

    List<CompanyCategory> findAllByClientIdOrderByNameAsc(UUID clientId);

    Optional<CompanyCategory> findByIdAndClientId(UUID id, UUID clientId);

    @Query("select c from CompanyCategory c where c.clientId = :clientId and lower(c.name) = lower(:name)")
    Optional<CompanyCategory> findByClientIdAndNameIgnoreCase(UUID clientId, String name);

    /** Active products per category, for the list's counts: rows of {categoryId, count}. */
    @Query("select p.companyCategory.id, count(p) from Product p "
            + "where p.clientId = :clientId and p.active = true and p.companyCategory is not null "
            + "group by p.companyCategory.id")
    List<Object[]> countActiveProductsByCategory(UUID clientId);
}
