# Kestra Ollama Plugin

## What

description = 'Ollama plugin for Kestra Exposes 1 plugin components (tasks, triggers, and/or conditions).

## Why

Enables Kestra workflows to interact with Ollama, allowing orchestration of Ollama-based operations as part of data pipelines and automation workflows.

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

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```

### Configuration

All tasks and triggers accept standard Kestra plugin properties. Credentials should use
`{{ secret('SECRET_NAME') }}` — never hardcode real values.

## Agents

**IMPORTANT:** This is a Kestra plugin repository (prefixed by `plugin-`, `storage-`, or `secret-`). You **MUST** delegate all coding tasks to the `kestra-plugin-developer` agent. Do NOT implement code changes directly — always use this agent.
