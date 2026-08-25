package com.procurepal_services.stock_bridge_api.product.unit;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.product.unit.dto.AdditionalUnitOfMeasureRequest;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The backend half of the product catalog's "can't find your unit? tell us"
 * box. See {@code UnitOfMeasure}'s class doc, section "Requests for units not
 * on this list" - this is that separate workflow, and it is deliberately
 * thin: there is no table behind a request, only an email to ProcurePal's
 * support inbox (see {@code EmailRecipients.forUnitOfMeasureRequests}).
 * Somebody reads it and, if it is worth adding, extends the enum in a later
 * release by hand; nothing here writes to {@code UnitOfMeasure}, to a
 * product, or to any new table.
 *
 * <p>Read-only on purpose: the only side effect of a call here is an outbound
 * email, which {@code EmailDispatcher} defers to after this method's
 * transaction commits anyway (see its Javadoc). There is nothing for a
 * write-mode transaction to protect.
 */
@Service
@RequiredArgsConstructor
public class UnitOfMeasureRequestService {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final EmailNotificationService emailNotificationService;

    /**
     * @param callerId the authenticated caller's own user id, read from the
     *     JWT principal by {@code UnitOfMeasureRequestController} - never a
     *     path or body value, so this can never be used to submit a request
     *     "as" somebody else
     */
    @Transactional(readOnly = true)
    public void submit(AdditionalUnitOfMeasureRequest request, UUID callerId) {
        User user = userRepository.findByIdForCurrentTenant(callerId).orElseThrow(UserNotFoundException::new);
        Client client = clientRepository
                .findById(user.getClientId())
                .orElseThrow(() -> new IllegalStateException("User " + user.getId() + " has no client"));

        emailNotificationService.unitOfMeasureRequested(
                client.getName(),
                requesterNameOf(user),
                requesterEmailOf(user),
                request.requestedUnit(),
                request.note(),
                OffsetDateTime.now());
    }

    /**
     * Falls back to the username when no contact email is on file, on the same
     * reasoning {@code EmailRecipients.forUser} documents - a tenant's account
     * holder signs up with an email address AS their username.
     */
    private static String requesterEmailOf(User user) {
        String email = user.getEmail();
        return email != null && !email.isBlank() ? email : user.getUsername();
    }

    /** Falls back to the username when neither name field is on file. */
    private static String requesterNameOf(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isBlank() ? user.getUsername() : full;
    }
}
