package com.procurepal_services.stock_bridge_api.demo;

import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one place that answers "is this the shared demo company?" (V86), for the guards that keep a demo
 * from reaching the outside world: supplier emails, marketplace orders, payments, and one prospect
 * changing another prospect's account.
 *
 * <p>Read from the database each time rather than from the token: SuperAdmin can turn the flag on for a
 * company whose users are signed in, and the guards must hold from that moment.
 */
@Component
@RequiredArgsConstructor
public class DemoCompanyGuard {

    /** Said wherever a demo refuses something that would leave the account. */
    public static final String NOTHING_LEAVES =
            "This is a demo company, so nothing here is sent to real suppliers, ordered or paid for.";

    private final ClientRepository clientRepository;

    public boolean isDemo(UUID clientId) {
        return clientId != null && clientRepository.findDemoFlagById(clientId).orElse(false);
    }

    /** The company of the current request, if it has one. */
    public boolean currentIsDemo() {
        return isDemo(TenantContext.get());
    }

    /** Refuses with {@link DemoActionNotAllowedException} when the company is the demo. */
    public void refuseIfDemo(UUID clientId, String message) {
        if (isDemo(clientId)) {
            throw new DemoActionNotAllowedException(message);
        }
    }
}
