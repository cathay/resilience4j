/*
 * Copyright 2017 Robert Winkler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.circuitbreaker;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;

import io.github.resilience4j.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.configure.CircuitBreakerAspect;
import io.github.resilience4j.circuitbreaker.monitoring.endpoint.CircuitBreakerEndpointResponse;
import io.github.resilience4j.circuitbreaker.monitoring.endpoint.CircuitBreakerEventsEndpointResponse;
import io.github.resilience4j.service.test.DummyService;
import io.github.resilience4j.service.test.TestApplication;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = TestApplication.class)
public class CircuitBreakerAutoConfigurationTest {

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    CircuitBreakerProperties circuitBreakerProperties;

    @Autowired
    CircuitBreakerAspect circuitBreakerAspect;

    @Autowired
    DummyService dummyService;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * The test verifies that a CircuitBreaker instance is created and configured properly when the DummyService is invoked and
     * that the CircuitBreaker records successful and failed calls.
     */
    @Test
    public void testCircuitBreakerAutoConfiguration() throws IOException {
        assertThat(circuitBreakerRegistry).isNotNull();
        assertThat(circuitBreakerProperties).isNotNull();

        try {
            dummyService.doSomething(true);
        } catch (IOException ex) {
            // Do nothing. The IOException is recorded by the CircuitBreaker as part of the recordFailurePredicate as a failure.
        }
        // The invocation is recorded by the CircuitBreaker as a success.
        dummyService.doSomething(false);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(DummyService.BACKEND);
        assertThat(circuitBreaker).isNotNull();

        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);

        assertThat(circuitBreaker.getCircuitBreakerConfig().getRingBufferSizeInClosedState()).isEqualTo(6);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getRingBufferSizeInHalfOpenState()).isEqualTo(2);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(70f);

        // Test Actuator endpoints

        ResponseEntity<CircuitBreakerEndpointResponse> circuitBreakerList = restTemplate.getForEntity("/circuitbreaker", CircuitBreakerEndpointResponse.class);
        assertThat(circuitBreakerList.getBody().getCircuitBreakers()).hasSize(2).containsExactly("backendA", "backendB");


        ResponseEntity<CircuitBreakerEventsEndpointResponse> circuitBreakerEventList = restTemplate.getForEntity("/circuitbreaker/events", CircuitBreakerEventsEndpointResponse.class);
        assertThat(circuitBreakerEventList.getBody().getCircuitBreakerEvents()).hasSize(2);

        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordFailurePredicate().test(new RecordedException())).isTrue();
        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordFailurePredicate().test(new IgnoredException())).isFalse();

        // expect no health indicator for backendB, as it is disabled via properties
        ResponseEntity<String> healthResponse = restTemplate.getForEntity("/health", String.class);
        assertThat(healthResponse.getBody()).isNotNull();
        assertThat(healthResponse.getBody()).contains("backendACircuitBreaker");
        assertThat(healthResponse.getBody()).doesNotContain("backendBCircuitBreaker");

        // Verify that an exception for which recordFailurePredicate returns false and it is not included in
        // recordExceptions evaluates to false.
        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordFailurePredicate().test(new Exception())).isFalse();

        assertThat(circuitBreakerAspect.getOrder()).isEqualTo(400);
    }
}
