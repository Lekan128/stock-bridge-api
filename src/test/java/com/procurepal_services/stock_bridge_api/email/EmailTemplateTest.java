package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.email.template.AccountEmails;
import com.procurepal_services.stock_bridge_api.email.template.OrderEmails;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Templates are pure functions of their arguments, so these assert on the rendered
 * strings directly. They cover the properties that would be expensive to discover
 * from a customer's inbox: that user-supplied text cannot inject markup, that links
 * point at the right audience's route, and that no email carries a password.
 */
class EmailTemplateTest {

    private static final String BASE_URL = "https://app.procurepal.test";
    private static final List<String> TO = List.of("buyer@example.com");

    private static Order order() {
        Order order = new Order();
        order.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        order.setOrderNumber("PP-2026-000123");
        order.setCurrency("NGN");
        order.setSubtotal(new BigDecimal("12000.00"));
        order.setDeliveryFee(new BigDecimal("500.00"));
        order.setTotal(new BigDecimal("12500.00"));
        order.setStatus(OrderStatus.PLACED);
        order.setPaymentMethod(PaymentMethod.PAY_ON_DELIVERY);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setPlacedAt(OffsetDateTime.parse("2026-08-19T10:15:30+01:00"));
        order.setDeliveryContactName("Ada Obi");
        order.setDeliveryContactPhone("08030000000");
        order.setDeliveryAddressLine1("12 Marina Road");
        order.setDeliveryCity("Lagos Island");
        order.setDeliveryState("Lagos");
        return order;
    }

    private static OrderItem item(String name, int quantity, String unitPrice, String lineTotal) {
        OrderItem orderItem = new OrderItem();
        orderItem.setProductName(name);
        orderItem.setQuantity(quantity);
        orderItem.setUnitOfMeasure("bag");
        orderItem.setUnitPrice(new BigDecimal(unitPrice));
        orderItem.setLineTotal(new BigDecimal(lineTotal));
        return orderItem;
    }

    @Test
    void buyerReceiptCarriesTheOrderItsItemsAndItsDeliveryAddress() {
        EmailMessage message = OrderEmails.orderPlacedForBuyer(
                TO, order(), List.of(item("Rice 50kg", 2, "6000.00", "12000.00")), BASE_URL);

        assertThat(message.subject()).isEqualTo("Order PP-2026-000123 confirmed");
        assertThat(message.htmlBody())
                .contains("PP-2026-000123")
                .contains("Rice 50kg")
                .contains("NGN 12,500.00")
                .contains("12 Marina Road");
        assertThat(message.textBody()).contains("PP-2026-000123").contains("Rice 50kg");
    }

    /**
     * Currency is rendered as an ISO code rather than the naira sign, which Outlook
     * on a Windows-1252 default draws as a box - on the one number a customer is
     * most likely to check.
     */
    @Test
    void rendersAmountsAsAnIsoCodeAndGroupedDigits() {
        EmailMessage message = OrderEmails.orderPlacedForBuyer(TO, order(), List.of(), BASE_URL);

        assertThat(message.htmlBody()).contains("NGN 12,500.00").doesNotContain("₦");
    }

    /** A product name is user-supplied and reaches a client that will happily render markup. */
    @Test
    void escapesUserSuppliedTextInsteadOfEmittingItAsMarkup() {
        EmailMessage message = OrderEmails.orderPlacedForBuyer(
                TO, order(), List.of(item("<img src=x onerror=alert(1)>", 1, "10.00", "10.00")), BASE_URL);

        assertThat(message.htmlBody())
                .doesNotContain("<img src=x")
                .contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void escapesACompanyNameInTheOperatorsCopy() {
        EmailMessage message = OrderEmails.newOrderForOperator(
                TO, order(), List.of(), "<script>alert(1)</script> Foods Ltd", BASE_URL);

        assertThat(message.htmlBody()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    /**
     * The two audiences have different routes to the same order, and sending either
     * one the other's link lands them on a 403.
     */
    @Test
    void linksEachAudienceToItsOwnRoute() {
        Order order = order();

        assertThat(OrderEmails.orderPlacedForBuyer(TO, order, List.of(), BASE_URL).htmlBody())
                .contains(BASE_URL + "/app/orders/" + order.getId());
        assertThat(OrderEmails.newOrderForOperator(TO, order, List.of(), "Demo Retail", BASE_URL).htmlBody())
                .contains(BASE_URL + "/app/marketplace/orders/" + order.getId());
    }

    /** A blank app-base-url is the unconfigured case; a button linking to a bare path would 404. */
    @Test
    void omitsTheCallToActionWhenNoAppBaseUrlIsConfigured() {
        EmailMessage message = OrderEmails.orderPlacedForBuyer(TO, order(), List.of(), "");

        assertThat(message.htmlBody()).doesNotContain("<a href");
        assertThat(message.htmlBody()).contains("PP-2026-000123");
    }

    /**
     * DELIVERED is the one status change that asks the buyer to do something: goods
     * sit in incoming stock until receipt is confirmed.
     */
    @Test
    void deliveredEmailAsksTheBuyerToConfirmReceipt() {
        EmailMessage message = OrderEmails.orderStatusChangedForBuyer(
                TO, order(), OrderStatus.DELIVERED, null, BASE_URL);

        assertThat(message.subject()).isEqualTo("Order PP-2026-000123 was delivered");
        assertThat(message.htmlBody()).contains("Confirm receipt");
        assertThat(message.textBody()).contains("Confirm receipt");
    }

    @Test
    void cancellationEmailLeadsWithTheReason() {
        EmailMessage message = OrderEmails.orderStatusChangedForBuyer(
                TO, order(), OrderStatus.CANCELLED, "Out of stock at the warehouse", BASE_URL);

        assertThat(message.subject()).isEqualTo("Order PP-2026-000123 was cancelled");
        assertThat(message.htmlBody()).contains("Out of stock at the warehouse");
    }

    @Test
    void statusEmailHumanisesTheEnumForTheReader() {
        EmailMessage message = OrderEmails.orderStatusChangedForBuyer(
                TO, order(), OrderStatus.OUT_FOR_DELIVERY, null, BASE_URL);

        assertThat(message.htmlBody()).contains("Out for delivery").doesNotContain("OUT_FOR_DELIVERY");
    }

    /** A failed payment reads like a lost order unless the email says otherwise. */
    @Test
    void failedPaymentEmailSaysTheOrderIsStillPayable() {
        EmailMessage message = OrderEmails.paymentFailedForBuyer(
                TO, order(), "The card was declined.", BASE_URL);

        assertThat(message.htmlBody()).contains("The card was declined.").contains("still waiting");
    }

    @Test
    void failedPaymentEmailStillExplainsItselfWithNoReasonGiven() {
        EmailMessage message = OrderEmails.paymentFailedForBuyer(TO, order(), null, BASE_URL);

        assertThat(message.htmlBody()).contains("did not complete");
    }

    /**
     * The rule AccountEmails is built around: an invite names the account, never the
     * credential. Asserting on the absence is the only way this stays true through a
     * later "make it more convenient" edit.
     */
    @Test
    void inviteNamesTheAccountButNeverThePassword() {
        EmailMessage message = AccountEmails.userInvited(
                TO, "Demo Retail Co", "warehouse-lead", "MANAGER", BASE_URL);

        assertThat(message.htmlBody()).contains("warehouse-lead").contains("Demo Retail Co");
        assertThat(message.htmlBody()).contains("password is not in this email");
        assertThat(message.textBody()).doesNotContain("Password:");
    }

    @Test
    void passwordResetAlertTellsTheUserToRaiseItAndCarriesNoCredential() {
        EmailMessage message = AccountEmails.passwordChanged(TO, "warehouse-lead", "Demo Retail Co");

        assertThat(message.subject()).isEqualTo("Your ProcurePal password was changed");
        assertThat(message.htmlBody()).contains("contact your administrator immediately");
        assertThat(message.htmlBody()).contains("not included in this email");
    }

    @Test
    void welcomeEmailNamesTheCompanyAndTheLoginUsername() {
        EmailMessage message = AccountEmails.welcome(TO, "Demo Retail Co", "owner@demo.test", BASE_URL);

        assertThat(message.subject()).isEqualTo("Welcome to ProcurePal");
        assertThat(message.htmlBody()).contains("Demo Retail Co").contains("owner@demo.test");
        assertThat(message.htmlBody()).contains(BASE_URL + "/login");
    }

    /**
     * A suspended tenant's users are refused at login with no explanation the app is
     * willing to give, so this email is the only channel the decision has.
     */
    @Test
    void suspensionEmailExplainsTheLockoutAndThatDataIsIntact() {
        EmailMessage message = AccountEmails.accountStatusChanged(TO, "Demo Retail Co", false, BASE_URL);

        assertThat(message.subject()).isEqualTo("Your ProcurePal account has been suspended");
        assertThat(message.htmlBody()).contains("Nobody at your company can sign in");
        assertThat(message.htmlBody()).contains("has not been deleted");
    }

    @Test
    void reactivationEmailSaysTheAccountWorksAgain() {
        EmailMessage message = AccountEmails.accountStatusChanged(TO, "Demo Retail Co", true, BASE_URL);

        assertThat(message.subject()).isEqualTo("Your ProcurePal account has been reactivated");
        assertThat(message.htmlBody()).contains("reactivated");
    }

    /** Every template must produce both bodies - HTML alone is what gets mail filed as spam. */
    @Test
    void everyTemplateProducesAPlainTextAlternative() {
        List<EmailMessage> messages = List.of(
                OrderEmails.orderPlacedForBuyer(TO, order(), List.of(), BASE_URL),
                OrderEmails.newOrderForOperator(TO, order(), List.of(), "Demo Retail", BASE_URL),
                OrderEmails.orderStatusChangedForBuyer(TO, order(), OrderStatus.CONFIRMED, null, BASE_URL),
                OrderEmails.paymentReceivedForBuyer(TO, order(), BASE_URL),
                OrderEmails.paymentReceivedForOperator(TO, order(), "Demo Retail", BASE_URL),
                OrderEmails.paymentFailedForBuyer(TO, order(), null, BASE_URL),
                AccountEmails.welcome(TO, "Demo Retail Co", "owner@demo.test", BASE_URL),
                AccountEmails.userInvited(TO, "Demo Retail Co", "lead", "MANAGER", BASE_URL),
                AccountEmails.passwordChanged(TO, "lead", "Demo Retail Co"),
                AccountEmails.accountStatusChanged(TO, "Demo Retail Co", false, BASE_URL));

        assertThat(messages).allSatisfy(message -> {
            assertThat(message.subject()).isNotBlank();
            assertThat(message.htmlBody()).isNotBlank();
            assertThat(message.textBody()).isNotBlank();
        });
    }
}
