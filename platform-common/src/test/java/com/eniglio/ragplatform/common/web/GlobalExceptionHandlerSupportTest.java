package com.eniglio.ragplatform.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 6 found a real bug via live verification: disabling an actuator
 * endpoint on the demo profile made requests to it resolve as {@link
 * NoResourceFoundException} - which the generic {@code Exception.class} handler
 * turned into a misleading 500 instead of a real 404. This is the regression test
 * for that fix, exercised through a minimal concrete subclass since the class under
 * test is abstract.
 */
class GlobalExceptionHandlerSupportTest {

    private static final class TestHandler extends GlobalExceptionHandlerSupport {
    }

    @Test
    void aMissingRouteReturns404NotAGeneric500() {
        TestHandler handler = new TestHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/does-not-exist");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/actuator/does-not-exist"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
