# API HTTP Generator

A JetBrains IDE plugin that writes IntelliJ HTTP Client requests for you, so endpoints can be tested without leaving the IDE.

- **Copy as JSON**: a Java class as JSON with realistic fake values, either fixed or as `{{$random...}}` placeholders.
- **REST requests**: `{{HOST}}`-based requests for a Spring handler method or a whole controller.
- **SPQR GraphQL requests**: `GRAPHQL` requests for `@GraphQLQuery` and `@GraphQLMutation` resolvers.

Generated requests go to `.local/http/` in the module, which stays out of git.

## Build

Needs JDK 21.

```bash
./gradlew test buildPlugin
```

The plugin zip lands in `build/distributions/`.

## Compatibility

IntelliJ IDEA (Ultimate or Community) on build 251 (2025.1) or later, with no upper bound. It is compiled against IntelliJ IDEA 2026.2.
