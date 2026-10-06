package com.metaform.cxve.hub.config;

import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.adapter.out.onboarding.catenax.CatenaXOnboarding;
import com.metaform.cxve.hub.adapter.out.onboarding.decadex.DecadeXOnboarding;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * One {@link com.metaform.cxve.hub.domain.port.DataspaceOnboarding} per ENABLED dataspace. Adding
 * a dataspace means adding its implementation and a bean method here, keyed on its
 * {@code dataspaces.<id>.enabled} switch.
 */
@Configuration
public class DataspacesConfig {

    @Bean
    public DataspaceProperties dataspaceProperties(Environment environment) {
        return DataspaceProperties.bind(environment);
    }

    @Bean
    public RegistrationValidator registrationValidator(Validator validator) {
        return new RegistrationValidator(validator);
    }

    @Bean
    @ConditionalOnProperty(name = "dataspaces." + CatenaXOnboarding.DATASPACE + ".enabled", havingValue = "true")
    public CatenaXOnboarding catenaXOnboarding(DataspaceProperties properties, RegistrationValidator validator) {
        return new CatenaXOnboarding(onboarding(properties, CatenaXOnboarding.DATASPACE), validator);
    }

    @Bean
    @ConditionalOnProperty(name = "dataspaces." + DecadeXOnboarding.DATASPACE + ".enabled", havingValue = "true")
    public DecadeXOnboarding decadeXOnboarding(DataspaceProperties properties, RegistrationValidator validator) {
        return new DecadeXOnboarding(onboarding(properties, DecadeXOnboarding.DATASPACE), validator);
    }

    private static DataspaceProperties.Onboarding onboarding(DataspaceProperties properties, String dataspace) {
        return properties.get(dataspace)
                .map(DataspaceProperties.Dataspace::onboarding)
                .orElseThrow(() -> new IllegalStateException(
                        "dataspaces.%s is enabled but has no onboarding settings".formatted(dataspace)));
    }
}
