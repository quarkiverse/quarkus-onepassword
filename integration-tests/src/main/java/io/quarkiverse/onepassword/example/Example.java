package io.quarkiverse.onepassword.example;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class Example implements QuarkusApplication {
    @Inject
    @ConfigProperty(name = "demo.secret")
    String secret;

    @Override
    public int run(String... args) {
        // Demonstrate successful injection without printing the secret or its length.
        System.out.println("1Password secret loaded successfully.");
        return 0;
    }
}
