package com.procurepal_services.stock_bridge_api.product.category;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.product.category.dto.CompanyCategoryRequest;
import com.procurepal_services.stock_bridge_api.product.category.dto.CompanyCategoryResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/company-categories} - a company's own product categories. Reading them is for
 * anyone who can see products; changing them is part of managing products.
 */
@RestController
@RequestMapping("/api/company-categories")
@RequiredArgsConstructor
public class CompanyCategoryController {

    private final CompanyCategoryService companyCategoryService;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public List<CompanyCategoryResponse> list() {
        return companyCategoryService.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<CompanyCategoryResponse> create(@Valid @RequestBody CompanyCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyCategoryService.create(request.name()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public CompanyCategoryResponse rename(@PathVariable UUID id, @Valid @RequestBody CompanyCategoryRequest request) {
        return companyCategoryService.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        companyCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(CompanyCategoryException.class)
    public ResponseEntity<ApiError> handle(CompanyCategoryException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError("A category needs a name of up to 80 characters."));
    }
}
