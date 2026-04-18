# Kestra Ollama Plugin

## What

- Provides plugin components under `io.kestra.plugin.ollama.cli`.
- Includes classes such as `OllamaCLI`.

## Why

- This plugin integrates Kestra with Ollama CLI.
- It provides tasks that execute Ollama CLI commands for model management and generation.

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
