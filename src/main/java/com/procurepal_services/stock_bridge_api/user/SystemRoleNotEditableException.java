package com.procurepal_services.stock_bridge_api.user;

/** OWNER, PROCUREMENT_MANAGER, INVENTORY_OFFICER, FINANCE_OFFICER and STOREKEEPER are fixed - see Role's javadoc. */
public class SystemRoleNotEditableException extends RuntimeException {

    public SystemRoleNotEditableException() {
        super("System roles cannot be edited or deleted. Create a custom role instead.");
    }
}
