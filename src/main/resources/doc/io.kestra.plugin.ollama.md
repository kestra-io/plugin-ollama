# How to use the Ollama plugin

Run Ollama models from Kestra flows against a local or remote Ollama server.

## Authentication

No API key is needed for a local Ollama server. To use Ollama Cloud models, set `auth.apiKey` to your Ollama Cloud API key and store it in a [secret](https://kestra.io/docs/concepts/secret).

## Tasks

`cli.OllamaCLI` runs any Ollama CLI command inside a container. Set `commands` to a list of Ollama commands (e.g., `["ollama pull llama3.2", "ollama run llama3.2 'Summarize this text'"]`). The task starts a transient `ollama serve` automatically when no `host` is set — set `host` to skip this and connect to an existing Ollama server instead.

Model caching is enabled by default (`enableModelCaching: true`) for local runs: when the Kestra server allows Docker volume mounts, pulled models are stored in a Docker volume named `kestra-ollama-cache` and reused across executions. Set `modelCachePath` to use a specific host directory instead of the named volume.

## Docker volume mounts and model caching

Docker volume mounts are disabled globally by default. A Kestra administrator enables them in plugin configuration:

```yaml
kestra:
  plugins:
    configurations:
      - type: io.kestra.plugin.ollama.cli.OllamaDocker
        values:
          volume-enabled: true
```

**The stock `io.kestra.plugin.scripts.runner.docker.Docker` task runner does not define a task-level `volume-enabled` (or `volumeEnabled`) property.** A flow that sets `taskRunner.type: io.kestra.plugin.scripts.runner.docker.Docker` with `volume-enabled: true` will not enable mounts.

`modelCachePath` is a free-form host path. It is only honored when an administrator has enabled `volume-enabled` for the runner, so restrict that setting accordingly.

`OllamaCLI` does not override an administrator `volume-enabled: false` policy. Model cache mounts are added only when volume mounts are already allowed server-side. The default task runner is the stock `Docker` runner, so existing plugin configuration for it keeps applying. Set `io.kestra.plugin.ollama.cli.OllamaDocker` as the task runner type to use an Ollama-only plugin configuration.

Remote `host` connections do not use local model caching; `enableModelCaching` and `modelCachePath` are ignored (a warning is logged) when `host` is set.
