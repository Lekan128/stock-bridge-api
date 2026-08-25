package com.procurepal_services.stock_bridge_api.product.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.product.unit.dto.AdditionalUnitOfMeasureRequest;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain Mockito over the two repositories and {@link EmailNotificationService}
 * - no Spring context, same shape as {@code ProfileServiceTest}-style tests
 * elsewhere in this codebase. What matters here is that the caller's own
 * tenant and identity are resolved correctly and handed to the email layer
 * unmodified; the email itself is {@code ProductEmails}' and {@code
 * UnitOfMeasureRequestEmailFlowTest}'s concern.
 */
class UnitOfMeasureRequestServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();

    private UserRepository userRepository;
    private ClientRepository clientRepository;
    private EmailNotificationService emailNotificationService;
    private UnitOfMeasureRequestService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        clientRepository = mock(ClientRepository.class);
        emailNotificationService = mock(EmailNotificationService.class);
        service = new UnitOfMeasureRequestService(userRepository, clientRepository, emailNotificationService);
    }

    @Test
    void resolvesTheCallersCompanyAndNameAndForwardsToEmailNotificationService() {
        User user = User.builder()
                .clientId(CLIENT_ID)
                .username("chidi.okafor")
                .firstName("Chidi")
                .lastName("Okafor")
                .email("chidi@ada-millers.test")
                .build();
        when(userRepository.findByIdForCurrentTenant(USER_ID)).thenReturn(Optional.of(user));
        Client client = Client.builder().id(CLIENT_ID).name("Ada Millers Ltd").build();
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));

        service.submit(new AdditionalUnitOfMeasureRequest("50L Jerry Can", "We buy palm oil in these."), USER_ID);

        verify(emailNotificationService)
                .unitOfMeasureRequested(
                        eq("Ada Millers Ltd"),
                        eq("Chidi Okafor"),
                        eq("chidi@ada-millers.test"),
                        eq("50L Jerry Can"),
                        eq("We buy palm oil in these."),
                        any());
    }

    /**
     * A sub-user with neither a name nor a contact email set is still
     * identifiable to support by their username - the same fallback {@code
     * EmailRecipients.forUser} uses for the same reason.
     */
    @Test
    void fallsBackToTheUsernameWhenNoNameOrContactEmailIsOnFile() {
        User user = User.builder().clientId(CLIENT_ID).username("warehouse-lead").build();
        when(userRepository.findByIdForCurrentTenant(USER_ID)).thenReturn(Optional.of(user));
        Client client = Client.builder().id(CLIENT_ID).name("Ada Millers Ltd").build();
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));

        service.submit(new AdditionalUnitOfMeasureRequest("Tonne bag", null), USER_ID);

        verify(emailNotificationService)
                .unitOfMeasureRequested(
                        eq("Ada Millers Ltd"),
                        eq("warehouse-lead"),
                        eq("warehouse-lead"),
                        eq("Tonne bag"),
                        eq(null),
                        any());
    }

    /**
     * Only reachable with a token whose user row has since been deleted - see
     * {@code UnitOfMeasureRequestExceptionHandler}. No email is sent for a
     * caller who cannot be identified.
     */
    @Test
    void throwsUserNotFoundWhenThePrincipalsUserRowIsGone() {
        when(userRepository.findByIdForCurrentTenant(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        service.submit(new AdditionalUnitOfMeasureRequest("50L Jerry Can", null), USER_ID))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(emailNotificationService);
    }
}
