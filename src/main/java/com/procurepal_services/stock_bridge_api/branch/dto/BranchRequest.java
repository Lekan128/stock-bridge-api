package com.procurepal_services.stock_bridge_api.branch.dto;

import jakarta.validation.constraints.Size;

/**
 * Add a branch ({@code name} required) or change one (any field; absent leaves it as it is).
 *
 * @param name what the company calls it - "Oshodi", "Bolade Depot". Unique in the company.
 * @param phone the branch's own number, optional.
 * @param address where it is, optional (UX section 5.11, V59).
 */
public record BranchRequest(@Size(max = 255) String name, @Size(max = 50) String phone, @Size(max = 300) String address) {

    public BranchRequest(String name, String phone) {
        this(name, phone, null);
    }
}
