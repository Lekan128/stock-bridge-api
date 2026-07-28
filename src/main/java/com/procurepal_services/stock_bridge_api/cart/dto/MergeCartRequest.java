package com.procurepal_services.stock_bridge_api.cart.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The anonymous localStorage cart (contract §8) handed to the server on login.
 *
 * Quantities are SUMMED into any existing company line rather than replacing it:
 * the two carts were built by two different people (a visitor browsing before
 * signing in, and whoever already filled the company cart), so discarding either
 * side loses someone's work. Unknown or unlisted ids are skipped silently - a
 * week-old localStorage cart naming a discontinued product must not make login's
 * merge call fail.
 */
public record MergeCartRequest(@NotNull @Valid List<AddCartItemRequest> items) {
}
