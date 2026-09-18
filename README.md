# Quarkus 1Password

<!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
<!-- ALL-CONTRIBUTORS-BADGE:END -->

Resolve runtime configuration secrets from [1Password](https://1password.com) through the `op` CLI.

```properties
quarkus.datasource.password=${op::op://Development/Postgres/password}
my.api-key=${op::op://Personal/My API/credential}
```

No plaintext secrets in your properties files.

## Documentation

The documentation for this extension is in the `docs/` directory and follows
[Quarkiverse Antora](https://github.com/quarkiverse/quarkiverse-docs) conventions:

- [Getting Started](docs/modules/ROOT/pages/index.adoc)
- [Configuration Reference](docs/modules/ROOT/pages/config.adoc)
- [Defaults and Fallback](docs/modules/ROOT/pages/fallback.adoc)
- [Authentication](docs/modules/ROOT/pages/authentication.adoc)

## Quick Start

Requirements: Java 17+, [1Password CLI](https://developer.1password.com/docs/cli/get-started/) (`op`), desktop app with CLI integration enabled.

```xml
<dependency>
    <groupId>io.quarkiverse.onepassword</groupId>
    <artifactId>quarkus-onepassword</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```properties
my.secret=${op::op://Development/Demo/credential}
```

```java
@ConfigProperty(name = "my.secret")
String secret;
```

## Build

```sh
mvn clean install
```

Tests use fake CLI executables — no vault required. See `VALIDATION.md` for manual native-image and desktop auth verification.

## Layout

- `runtime/` — resolver, CLI process handling, cache, SmallRye handler
- `deployment/` — Quarkus feature and runtime builder registration
- `integration-tests/` — command-mode example and packaged-app test
- `docs/` — Antora documentation

## Contributors ✨

Thanks goes to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!
