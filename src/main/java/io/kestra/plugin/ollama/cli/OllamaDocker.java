package io.kestra.plugin.ollama.cli;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.TaskCommands;
import io.kestra.core.models.tasks.runners.TaskRunnerResult;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.runner.docker.Docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Docker task runner with per-task volume mounting for Ollama",
    description = """
        Same as the standard Docker task runner, with optional `volumeEnabled` on the task runner.
        Use this type instead of `io.kestra.plugin.scripts.runner.docker.Docker` when a flow needs volume mounts
        (for example Ollama model caching) without enabling `volume-enabled` globally in Kestra plugin configuration.
        The stock Docker runner class does not accept `volume-enabled` as a task property.
        When `volumeEnabled` is set here, it overrides the server `volume-enabled` plugin configuration for this task only.
        When unset, the server plugin configuration applies.
        """
)
public class OllamaDocker extends Docker {

    private static final String VOLUME_ENABLED_CONFIG = "volume-enabled";

    @Schema(
        title = "Enable Docker volume mounts for this task",
        description = """
            When true, custom volume mounts (including Ollama model cache mounts) are applied for this task.
            When false, volume mounts are disabled for this task even if enabled globally.
            When unset, only the [Docker plugin configuration](https://kestra.io/docs/configuration-guide/plugins) applies.
            """
    )
    @PluginProperty(group = "execution")
    private Property<Boolean> volumeEnabled;

    public static OllamaDocker instance() {
        return OllamaDocker.builder().type(OllamaDocker.class.getName()).build();
    }

    static OllamaDockerBuilder<?, ?> builderFrom(Docker docker) {
        OllamaDockerBuilder<?, ?> builder = OllamaDocker.builder().type(OllamaDocker.class.getName());
        if (docker instanceof OllamaDocker ollamaDocker) {
            builder.volumeEnabled(ollamaDocker.getVolumeEnabled());
        }
        builder
            .host(docker.getHost())
            .config(docker.getConfig())
            .credentials(docker.getCredentials())
            .image(docker.getImage())
            .user(docker.getUser())
            .entryPoint(docker.getEntryPoint())
            .extraHosts(docker.getExtraHosts())
            .networkMode(docker.getNetworkMode())
            .portBindings(docker.getPortBindings())
            .volumes(docker.getVolumes())
            .pullPolicy(docker.getPullPolicy())
            .deviceRequests(docker.getDeviceRequests())
            .cpu(docker.getCpu())
            .memory(docker.getMemory())
            .shmSize(docker.getShmSize())
            .privileged(docker.getPrivileged())
            .fileHandlingStrategy(docker.getFileHandlingStrategy())
            .delete(docker.getDelete())
            .wait(docker.getWait())
            .killGracePeriod(docker.getKillGracePeriod())
            .resume(docker.getResume());
        return builder;
    }

    static OllamaDocker from(Docker docker) {
        if (docker instanceof OllamaDocker ollamaDocker) {
            return ollamaDocker;
        }
        return builderFrom(docker).build();
    }

    @Override
    public TaskRunnerResult<DockerTaskRunnerDetailResult> run(
        RunContext runContext,
        TaskCommands taskCommands,
        List<String> filesToDownload) throws Exception {
        RunContext effectiveRunContext = withVolumeEnabled(runContext);
        return super.run(effectiveRunContext, taskCommands, filesToDownload);
    }

    RunContext withVolumeEnabled(RunContext runContext) throws Exception {
        if (this.volumeEnabled == null) {
            return runContext;
        }
        Optional<Boolean> rendered = runContext.render(this.volumeEnabled).as(Boolean.class);
        if (rendered.isEmpty()) {
            return runContext;
        }
        if (!(runContext instanceof DefaultRunContext defaultRunContext)) {
            return runContext;
        }
        Map<String, Object> merged = new HashMap<>(defaultRunContext.pluginConfigurations());
        merged.put(VOLUME_ENABLED_CONFIG, rendered.get());
        DefaultRunContext clone = defaultRunContext.clone();
        // ponytail: setPluginConfiguration is package-private on DefaultRunContext; reflection avoids mutating shared server config maps.
        Method setPluginConfiguration = DefaultRunContext.class.getDeclaredMethod("setPluginConfiguration", Map.class);
        setPluginConfiguration.setAccessible(true);
        setPluginConfiguration.invoke(clone, merged);
        return clone;
    }
}
