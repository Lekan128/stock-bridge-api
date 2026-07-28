package com.procurepal_services.stock_bridge_api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers the annotated bean only when EVERY listed property resolves to a
 * non-blank value. Used by the two bootstrap runners so that an unconfigured
 * deployment never even instantiates them: no bean means no constructor
 * injection, no ApplicationRunner callback, and - the point of the exercise -
 * no startup database query at all. A cheap {@code if} at the top of
 * {@code run()} would still cost a bean, its dependencies and an invocation on
 * every container start; a bean condition costs nothing.
 *
 * <h2>Why this exists instead of {@code @ConditionalOnProperty}</h2>
 * {@code @ConditionalOnProperty} is the obvious first choice and it does support
 * "all of these names must be present" via its {@code name} array, but its
 * notion of "present" is the wrong one here. Its condition is, in effect,
 * "the property exists and is not literally the string {@code false}", so an
 * <em>empty</em> value matches. That matters because every one of our properties
 * is declared in {@code application.yml} as a placeholder with an empty default
 * ({@code ${SUPERADMIN_PASSWORD:}}), which is what makes an unset environment
 * variable resolve to blank rather than blowing up placeholder resolution at
 * startup. With {@code @ConditionalOnProperty} that arrangement would report
 * "configured" for every deployment, which is precisely the failure mode this
 * whole change exists to remove.
 *
 * <h2>Why not {@code @ConditionalOnExpression}</h2>
 * {@code @ConditionalOnExpression("!'${app.super-admin.password:}'.isBlank()")}
 * expresses the rule in one line and needs no new code - but it interpolates the
 * secret into a SpEL expression string. A password containing an apostrophe
 * would then produce a SpEL parse error, and that error message quotes the
 * expression, which means the plaintext password would land in the startup log
 * and in the stack trace. That is exactly the outcome the password-safety
 * requirement forbids, so the twenty lines below are the cheaper option.
 *
 * <p>This condition never touches the value beyond {@code isBlank()} - it does
 * not log it, and it deliberately implements the plain {@link Condition}
 * interface rather than Spring Boot's {@code SpringBootCondition}, whose
 * {@code ConditionOutcome} messages are recorded in the condition evaluation
 * report and surfaced by the actuator {@code /conditions} endpoint.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnNonBlankProperties.OnNonBlankPropertiesCondition.class)
public @interface ConditionalOnNonBlankProperties {

    /** Fully-qualified property keys. All of them must be non-blank for the bean to be registered. */
    String[] value();

    class OnNonBlankPropertiesCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes =
                    metadata.getAnnotationAttributes(ConditionalOnNonBlankProperties.class.getName());
            if (attributes == null) {
                return false;
            }
            for (String key : (String[]) attributes.get("value")) {
                String value = context.getEnvironment().getProperty(key);
                if (value == null || value.isBlank()) {
                    return false;
                }
            }
            return true;
        }
    }
}
