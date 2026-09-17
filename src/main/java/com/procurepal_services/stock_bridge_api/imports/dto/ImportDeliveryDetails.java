package com.procurepal_services.stock_bridge_api.imports.dto;

import java.time.LocalDate;

/**
 * What the upload screen asked once for a whole delivery (BULK_IMPORT_CX_PLAN.md task 1.5). Each
 * value fills the rows that leave that cell blank; a row that says otherwise keeps its own.
 *
 * @param date when the delivery arrived; null means today.
 * @param invoiceNo the waybill or invoice number, if one was given.
 * @param supplierName the supplier chosen for the delivery, if one was.
 */
public record ImportDeliveryDetails(LocalDate date, String invoiceNo, String supplierName) {

    public boolean isEmpty() {
        return date == null && invoiceNo == null && supplierName == null;
    }
}
