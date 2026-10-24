package cz.rychter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure Unit Test for GreetingResource.
 * Executed during 'mvn test'. Runs instantly without starting Quarkus or PostgreSQL.
 */
class GreetingResourceTest {

    @Test
    void testHello() {
        GreetingResource resource = new GreetingResource();
        String result = resource.hello();
        assertEquals("Hello from Quarkus REST", result);
    }
}
