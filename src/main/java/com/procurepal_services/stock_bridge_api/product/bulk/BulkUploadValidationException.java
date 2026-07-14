package com.procurepal_services.stock_bridge_api.product.bulk;

import java.util.List;
import lombok.Getter;

/** Carries every row-level error found for a rejected bulk upload - see ProductExcelService for V1's all-or-nothing mode. */
@Getter
public class BulkUploadValidationException extends RuntimeException {

    private final List<ProductRowError> errors;

    public BulkUploadValidationException(List<ProductRowError> errors) {
        super("Bulk upload rejected: " + errors.size() + " error(s) found");
        this.errors = List.copyOf(errors);
    }
}
