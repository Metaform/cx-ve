package com.metaform.dxonboarding.adapter.in.web;

import com.metaform.dxonboarding.adapter.out.document.InMemoryDocumentStore;
import com.metaform.dxonboarding.adapter.out.persistence.InMemoryOnboardingRequestRepository;
import com.metaform.dxonboarding.application.AutomaticReview;
import com.metaform.dxonboarding.application.DefaultOnboardingRequestService;
import com.metaform.dxonboarding.config.ReviewConfig;
import com.metaform.dxonboarding.config.SecurityConfig;
import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.port.DidResolver;
import com.metaform.dxonboarding.domain.port.OnboardingEventPublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/** The stand-in serves the TSP's specification ({@code dataspace-api-docs.yaml}): every operation, every problem code. */
@WebMvcTest(controllers = OnboardingRequestController.class)
@Import({SecurityConfig.class, ReviewConfig.class, UnknownProperties.class, DefaultOnboardingRequestService.class,
        InMemoryOnboardingRequestRepository.class, InMemoryDocumentStore.class})
class SpecConformanceTest {

    private static final String BASE_PATH = "/api/v1/onboarding-requests";
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private AutomaticReview review;

    @MockitoBean
    private OnboardingEventPublisher events;

    @MockitoBean
    private DidResolver didResolver;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() throws IOException {
        return new Yaml().load(Files.readString(Path.of("dataspace-api-docs.yaml")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyOperationOfTheSpec_isServed() throws IOException {
        var specified = new ArrayList<String>();
        var paths = (Map<String, Map<String, Object>>) spec().get("paths");
        paths.forEach((path, operations) -> operations.keySet().stream().filter(METHODS::contains).forEach(method ->
                specified.add(method.toUpperCase() + " " + BASE_PATH + (path.equals("/") ? "" : path))));
        var served = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPathPatternsCondition().getPatternValues().stream()
                        .flatMap(pattern -> mapping.getMethodsCondition().getMethods().stream()
                                .map(method -> method + " " + pattern)))
                .collect(Collectors.toSet());

        assertThat(specified).hasSize(10);
        assertThat(served).containsAll(specified);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyProblemCodeOfTheSpec_isOneOfOurs() throws IOException {
        var schemas = (Map<String, Map<String, Object>>) ((Map<String, Object>) spec().get("components")).get("schemas");
        var properties = (Map<String, Map<String, Object>>) schemas.get("Problem").get("properties");
        var description = (String) properties.get("type").get("description");
        var specified = Pattern.compile("`([a-z]+(?:-[a-z]+)*)` \\(").matcher(description).results()
                .map(match -> match.group(1)).toList();

        assertThat(specified).hasSize(19);
        assertThat(Arrays.stream(ProblemCode.values()).map(ProblemCode::code)).containsExactlyInAnyOrderElementsOf(specified);
    }
}
