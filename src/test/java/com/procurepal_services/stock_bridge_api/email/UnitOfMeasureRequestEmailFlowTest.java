package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.EmailSuppressionRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Wires {@link EmailNotificationService} to real {@link EmailDispatcher},
 * {@link EmailRecipients} and {@link EmailEligibility} instances - only
 * {@link EmailSender} and the repositories are mocked - so this exercises the
 * actual policy decision that gets a unit-of-measure request out of the
 * building, the same level {@link EmailEligibilityTest} tests the rest of the
 * eligibility rules at.
 *
 * <p>No Spring context: every collaborator here is plain Mockito or a real
 * constructor call, on the same reasoning {@code EmailEligibilityTest}
 * documents - what matters is the POLICY (does this address, this kind,
 * survive {@code EmailDispatcher}), not whether a repository's SQL is right.
 */
class UnitOfMeasureRequestEmailFlowTest {

    private static final String COMPANY_NAME = "Ada Millers Ltd";
    private static final String REQUESTER_NAME = "Chidi Okafor";
    private static final String REQUESTER_EMAIL = "chidi@ada-millers.test";
    private static final String REQUESTED_UNIT = "50L Jerry Can";
    private static final String NOTE = "We buy palm oil in these, not bags.";

    private EmailSender emailSender;
    private EmailNotificationService emailNotificationService;

    @BeforeEach
    void setUp() {
        UserRepository userRepository = mock(UserRepository.class);
        ClientRepository clientRepository = mock(ClientRepository.class);
        SuperAdminRepository superAdminRepository = mock(SuperAdminRepository.class);
        EmailSuppressionRepository suppressionRepository = mock(EmailSuppressionRepository.class);
        OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);

        // vendorWaitlistAddress left null so EmailProperties' own compact
        // constructor substitutes its real default - support@procurepaddy.com -
        // exactly as production does, rather than a test double address that
        // would leave the actual default unexercised.
        EmailProperties properties = new EmailProperties(
                true, "us-east-1", "no-reply@procurepal.test", "ProcurePal",
                null, null, "https://app.procurepal.test", null, null);

        EmailEligibility eligibility =
                new EmailEligibility(userRepository, clientRepository, properties, suppressionRepository);
        EmailRecipients recipients =
                new EmailRecipients(clientRepository, userRepository, superAdminRepository, properties);
        emailSender = mock(EmailSender.class);
        EmailDispatcher dispatcher = new EmailDispatcher(emailSender, eligibility);

        emailNotificationService =
                new EmailNotificationService(dispatcher, recipients, properties, orderItemRepository, clientRepository);
    }

    /**
     * The acceptance bar in full: a well-formed request reaches
     * support@procurepaddy.com (via the real, unconfigured default) with the
     * requester's identifying info and what they asked for, as a TRANSACTIONAL
     * message.
     */
    @Test
    void submittingARequestSendsAnEmailToProcurePaddysSupportInbox() {
        emailNotificationService.unitOfMeasureRequested(
                COMPANY_NAME, REQUESTER_NAME, REQUESTER_EMAIL, REQUESTED_UNIT, NOTE, OffsetDateTime.now());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).sendAsync(captor.capture());
        EmailMessage sent = captor.getValue();

        assertThat(sent.to()).containsExactly("support@procurepaddy.com");
        assertThat(sent.kind()).isEqualTo(EmailKind.TRANSACTIONAL);
        assertThat(sent.subject()).contains(REQUESTED_UNIT);
        assertThat(sent.htmlBody())
                .contains(COMPANY_NAME)
                .contains(REQUESTER_NAME)
                .contains(REQUESTER_EMAIL)
                .contains(REQUESTED_UNIT)
                .contains(NOTE);
        assertThat(sent.textBody())
                .contains(COMPANY_NAME)
                .contains(REQUESTER_NAME)
                .contains(REQUESTED_UNIT)
                .contains(NOTE);
    }

    /** No transaction bound in this test, so EmailDispatcher takes its synchronous path. */
    @Test
    void sendsSynchronouslyWhenNoTransactionIsBound() {
        emailNotificationService.unitOfMeasureRequested(
                COMPANY_NAME, REQUESTER_NAME, REQUESTER_EMAIL, REQUESTED_UNIT, NOTE, OffsetDateTime.now());

        verify(emailSender).sendAsync(any());
    }
}
