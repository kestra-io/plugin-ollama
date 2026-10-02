# How to use the Ollama plugin

Run Ollama models from Kestra flows against a local or remote Ollama server.

## Authentication

No API key is needed for a local Ollama server. To use Ollama Cloud models, set `auth.apiKey` to your Ollama Cloud API key and store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`cli.OllamaCLI` runs any Ollama CLI command inside a container. Set `commands` to a list of Ollama commands (e.g., `["ollama pull llama3.2", "ollama run llama3.2 'Summarize this text'"]`). The task starts a transient `ollama serve` automatically when no `host` is set — set `host` to skip this and connect to an existing Ollama server instead.

Model caching is enabled by default (`enableModelCaching: true`): pulled models are stored in a Docker volume named `kestra-ollama-cache` and reused across executions, so you only pay the pull cost once. Set `modelCachePath` to use a specific host directory instead of the named volume.

## Per-task Docker volume mounts

Docker volume mounts are disabled globally by default. Enabling them only in server configuration (`plugins.configurations` for `io.kestra.plugin.scripts.runner.docker.Docker` with `volume-enabled: true`) affects every Docker-backed task.

**The stock `io.kestra.plugin.scripts.runner.docker.Docker` task runner does not define a task-level `volume-enabled` (or `volumeEnabled`) property.** A flow YAML that sets `taskRunner.type: io.kestra.plugin.scripts.runner.docker.Docker` with `volume-enabled: true` will not enable mounts.

For Ollama model caching without global configuration, use one of these approaches:

- Rely on the default task runner (`io.kestra.plugin.ollama.cli.OllamaDocker`), which `OllamaCLI` selects automatically and which enables volumes when model caching is on.
- Set `volumeEnabled: true` on `io.kestra.plugin.ollama.cli.OllamaDocker` under `taskRunner`.
- Set `volumeEnabled` on the `OllamaCLI` task to override the server `volume-enabled` plugin configuration for that execution only.
