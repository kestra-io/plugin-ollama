# Kestra Ollama Plugin

## What

- Provides plugin components under `io.kestra.plugin.ollama.cli`.
- Includes classes such as `OllamaCLI`.

## Why

- What user problem does this solve? Teams need to work with Ollama models and runtime features from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Ollama steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Ollama.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `ollama`

### Key Plugin Classes

- `io.kestra.plugin.ollama.cli.OllamaCLI`

### Project Structure

```
plugin-ollama/
├── src/main/java/io/kestra/plugin/ollama/cli/
├── src/test/java/io/kestra/plugin/ollama/cli/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
