package io.kestra.plugin.ollama.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.*;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.kestra.plugin.scripts.runner.docker.Docker;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run Ollama CLI commands in flows",
    description = "Executes rendered Ollama CLI commands inside the configured task runner. Starts a transient `ollama serve` when no remote host is provided and can mount a Docker volume for model caching when the server allows Docker volume mounts."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Pull a model and generate text completion",
            code = """
                id: ollama_flow
                namespace: company.team

                inputs:
                  - id: prompt
                    type: STRING
                    defaults: Tell me a joke about AI

                tasks:
                  - id: ollama_cli
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    outputFiles:
                      - completion.txt
                    commands:
                      - ollama pull llama2
                      - ollama run llama2 "{{ inputs.prompt }}" > completion.txt
                """
        ),
        @Example(
            full = true,
            title = "List available models and output as JSON",
            code = """
                id: ollama_list_models
                namespace: company.team

                tasks:
                  - id: list_models
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    outputFiles:
                      - models.json
                    commands:
                      - ollama list --json > models.json
                """
        ),
        @Example(
            full = true,
            title = "Enable model caching between executions",
            code = """
                    id: ollama_caching
                    namespace: company.team

                    inputs:
                      - id: prompt
                        type: STRING
                        defaults: Tell me a joke about AI

                    tasks:
                      - id: ask_ai
                        type: io.kestra.plugin.ollama.cli.OllamaCLI
                        enableModelCaching: true
                        modelCachePath: /tmp/.ollama
                        outputFiles:
                          - completion.txt
                        commands:
                          - ollama run gemma3:1b "{{ inputs.prompt }}" > completion.txt
                """
        ),
        @Example(
            full = true,
            title = "List models from a remote Ollama server",
            code = """
                    id: ollama_plugin
                    namespace: company.team

                    tasks:
                      - id: list_models
                        type: io.kestra.plugin.ollama.cli.OllamaCLI
                        host: host.docker.internal:11434
                        commands:
                          - ollama list

                """
        )
    }
)
public class OllamaCLI extends Task implements RunnableTask<ScriptOutput>, NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {
    private static final String DEFAULT_IMAGE = "ollama/ollama";
    private static final String OLLAMA_CONTAINER_MODELS_PATH = "/root/.ollama";
    private static final String OLLAMA_CLOUD_HOST = "https://ollama.com";
    static final String VOLUME_ENABLED_CONFIG = "volume-enabled";

    @Schema(
        title = "Commands executed by Ollama CLI",
        description = "Rendered then executed with `/bin/sh -c` in order. Include any `sync` calls before cleanup commands if output files need to be persisted."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<List<String>> commands;

    @Schema(
        title = "Additional environment variables",
        description = "Optional key/value pairs merged into the process environment. When `host` is set, `OLLAMA_HOST` is injected automatically."
    )
    @PluginProperty(group = "execution")
    protected Property<Map<String, String>> env;

    @Schema(
        title = "Task runner used for execution",
        description = """
            Defaults to `io.kestra.plugin.ollama.cli.OllamaDocker`.
            The stock `io.kestra.plugin.scripts.runner.docker.Docker` runner has no task-level `volume-enabled` property; enable Docker volume mounts in Kestra plugin configuration instead.
            """
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    @Valid
    private TaskRunner<?> taskRunner = OllamaDocker.instance();

    @Schema(
        title = "Container image for the runner",
        description = "Image used when running inside Docker. Defaults to `ollama/ollama`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @PluginProperty(group = "source")
    private NamespaceFiles namespaceFiles;

    @PluginProperty(group = "source")
    private Object inputFiles;

    @PluginProperty(group = "destination")
    private Property<List<String>> outputFiles;

    @Schema(
        title = "Persist Ollama model cache",
        description = """
            Defaults to true for local runs when unset. When enabled, adds a Docker volume mount for the Ollama models directory only if the server already allows Docker volume mounts (`volume-enabled` in plugin configuration).
            Ignored when `host` is set.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> enableModelCaching;

    @Schema(
        title = "Host path for cached models",
        description = """
            Optional host path to mount for the Ollama cache when caching is enabled and Docker volume mounts are allowed server-side.
            If unset, uses named Docker volume `kestra-ollama-cache`. Ignored when `host` is set.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> modelCachePath;

    @Schema(
        title = "Remote Ollama host",
        description = "Connects to an existing Ollama server and skips starting a local `ollama serve`. Sets `OLLAMA_HOST` for commands; ensure the address is reachable from the runner."
    )
    @PluginProperty(group = "connection")
    private Property<String> host;

    @Schema(
        title = "Authentication for Ollama Cloud (Turbo)",
        description = """
            When set, the task authenticates with ollama.com.
            If no `host` is specified, it defaults to https://ollama.com.
            """
    )
    @PluginProperty(dynamic = true, group = "connection")
    private Auth auth;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        var renderedOutputFiles = runContext.render(this.outputFiles).asList(String.class);

        Map<String, String> envs = getEnv(runContext);

        TaskRunner<?> configuredTaskRunner = configureTaskRunner(runContext);

        var originalCommands = runContext.render(this.commands).asList(String.class);

        var needSync = !renderedOutputFiles.isEmpty();
        var hasCleanupCommand = originalCommands.stream().anyMatch(cmd -> cmd.contains("ollama rm"));

        if (needSync && hasCleanupCommand) {
            originalCommands = originalCommands.stream()
                .flatMap(cmd -> cmd.contains("ollama rm") ? Stream.of("sync", cmd) : Stream.of(cmd))
                .toList();

        }

        CommandsWrapper commands = new CommandsWrapper(runContext)
            .withTaskRunner(configuredTaskRunner)
            .withContainerImage(runContext.render(this.containerImage).as(String.class).orElse(DEFAULT_IMAGE))
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withBeforeCommands(
                host == null
                    ? Property.ofValue(List.of("ollama serve & sleep 5"))
                    : null
            )
            .withCommands(Property.ofValue(originalCommands))
            .withEnv(envs)
            .withNamespaceFiles(namespaceFiles)
            .withInputFiles(inputFiles)
            .withOutputFiles(renderedOutputFiles.isEmpty() ? null : renderedOutputFiles);

        return commands.run();
    }

    protected TaskRunner<?> configureTaskRunner(RunContext runContext) throws Exception {
        if (this.host != null) {
            if (this.modelCachePath != null || runContext.render(this.enableModelCaching).as(Boolean.class).orElse(false)) {
                runContext.logger().warn("Model caching options are ignored when `host` is set.");
            }
            return this.taskRunner;
        }

        if (!(this.taskRunner instanceof Docker dockerRunner)) {
            return this.taskRunner;
        }

        var cachingEnabled = runContext.render(this.enableModelCaching).as(Boolean.class).orElse(true);
        if (!cachingEnabled) {
            return dockerRunner;
        }

        if (!dockerVolumesAllowed(runContext, dockerRunner)) {
            runContext.logger().warn(
                "Model caching is enabled but Docker volume mounts are disabled in plugin configuration ({}). "
                    + "Pulled models will not persist between runs.",
                VOLUME_ENABLED_CONFIG
            );
            return dockerRunner;
        }

        var volumeSpec = resolveModelCacheVolume(runContext);
        var existingVolumes = dockerRunner.getVolumes() != null ? new ArrayList<>(dockerRunner.getVolumes()) : new ArrayList<String>();
        existingVolumes.add(volumeSpec);
        return dockerRunner.toBuilder().volumes(existingVolumes).build();
    }

    static boolean dockerVolumesAllowed(RunContext runContext, Docker dockerRunner) {
        // the runner reads `volume-enabled` from its own plugin configuration, so gate on the same one
        return runContext.cloneForPlugin(dockerRunner).<Boolean> pluginConfiguration(VOLUME_ENABLED_CONFIG).orElse(false);
    }

    private String resolveModelCacheVolume(RunContext runContext) throws IllegalVariableEvaluationException {
        if (this.modelCachePath != null) {
            var hostPath = runContext.render(this.modelCachePath).as(String.class)
                .filter(path -> !path.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("modelCachePath must not be blank"));
            var volumeSpec = hostPath + ":" + OLLAMA_CONTAINER_MODELS_PATH;
            runContext.logger().info("Using user host path for Ollama cache: {}", volumeSpec);
            return volumeSpec;
        }
        var volumeName = "kestra-ollama-cache";
        var volumeSpec = volumeName + ":" + OLLAMA_CONTAINER_MODELS_PATH;
        runContext.logger().info("Using named Docker volume for Ollama cache: {}", volumeSpec);
        return volumeSpec;
    }

    public Map<String, String> getEnv(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> envs = new HashMap<>();

        var renderedEnv = runContext.render(this.env).asMap(String.class, String.class);
        if (!renderedEnv.isEmpty()) {
            envs.putAll(renderedEnv);
        }

        String renderedHost = this.host != null ? runContext.render(this.host).as(String.class).orElse(null) : null;
        if (renderedHost != null) {
            envs.put("OLLAMA_HOST", renderedHost);
        }

        if (this.auth != null && this.auth.getApiKey() != null) {
            envs.put("OLLAMA_API_KEY", runContext.render(this.auth.getApiKey()).as(String.class).orElseThrow());
            envs.putIfAbsent("OLLAMA_HOST", OLLAMA_CLOUD_HOST);
        }

        return envs;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(
        title = "Ollama Cloud authentication configuration"
    )
    public static class Auth {
        @Schema(
            title = "API Key",
            description = "API key used to access Ollama cloud models."
        )
        @PluginProperty(secret = true)
        private Property<String> apiKey;
    }
}
