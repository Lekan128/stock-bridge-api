package com.procurepal_services.stock_bridge_api.product.unit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation over {@link AdditionalUnitOfMeasureRequest} directly -
 * exactly the annotations {@code UnitOfMeasureRequestController}'s {@code
 * @Valid} triggers, exercised without standing up the controller or the
 * {@code MethodArgumentNotValidException} → {@code ApiError} translation that
 * {@code UnitOfMeasureRequestExceptionHandler} covers separately.
 */
class AdditionalUnitOfMeasureRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void acceptsAMinimalWellFormedRequest() {
        var request = new AdditionalUnitOfMeasureRequest("50L Jerry Can", null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsABlankRequestedUnit() {
        var request = new AdditionalUnitOfMeasureRequest("   ", "why");

        Set<ConstraintViolation<AdditionalUnitOfMeasureRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("requestedUnit"));
    }

    @Test
    void rejectsANullRequestedUnit() {
        var request = new AdditionalUnitOfMeasureRequest(null, null);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsARequestedUnitOverTwoHundredCharacters() {
        var request = new AdditionalUnitOfMeasureRequest("x".repeat(201), null);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void acceptsARequestedUnitAtExactlyTwoHundredCharacters() {
        var request = new AdditionalUnitOfMeasureRequest("x".repeat(200), null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsANoteOverOneThousandCharacters() {
        var request = new AdditionalUnitOfMeasureRequest("50L Jerry Can", "x".repeat(1001));

        Set<ConstraintViolation<AdditionalUnitOfMeasureRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anySatisfy(
                violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("note"));
    }

    /** The note is optional - a caller who leaves it out entirely is still well-formed. */
    @Test
    void acceptsAMissingNote() {
        var request = new AdditionalUnitOfMeasureRequest("Tonne bag", null);

        assertThat(validator.validate(request)).isEmpty();
    }
}
