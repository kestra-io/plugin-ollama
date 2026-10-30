package io.kestra.plugin.ollama.cli;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.scripts.runner.docker.Docker;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class OllamaCLITaskRunnerTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldAddCacheVolumeWhenCachingEnabledAndVolumesAllowed() throws Exception {
        var task = OllamaCLI.builder()
            .id(IdUtils.create())
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .taskRunner(OllamaDocker.instance())
            .enableModelCaching(Property.ofValue(true))
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var runner = (Docker) task.configureTaskRunner(runContext);

        assertThat(runner.getVolumes(), contains("kestra-ollama-cache:/root/.ollama"));
    }

    @Test
    void shouldNotAddCacheVolumeWhenDockerVolumesDisabledByAdmin() throws Exception {
        var task = OllamaCLI.builder()
            .id(IdUtils.create())
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .taskRunner(Docker.instance())
            .enableModelCaching(Property.ofValue(true))
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var runner = (Docker) task.configureTaskRunner(runContext);

        assertThat(runner.getVolumes(), nullValue());
    }

    @Test
    void shouldRejectBlankModelCachePathOnRun() throws Exception {
        var task = OllamaCLI.builder()
            .id(IdUtils.create())
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .taskRunner(OllamaDocker.instance())
            .modelCachePath(Property.ofValue("   "))
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(error.getMessage(), is("modelCachePath must not be blank"));
    }

    @Test
    void shouldAllowHostOnlyWhenEnableModelCachingIsUnset() throws Exception {
        var task = OllamaCLI.builder()
            .id(IdUtils.create())
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .host(Property.ofValue("http://remote:11434"))
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var runner = task.configureTaskRunner(runContext);

        assertThat(runner, is(task.getTaskRunner()));
    }

    @Test
    void shouldIgnoreCachingOptionsWhenHostIsSet() throws Exception {
        var task = OllamaCLI.builder()
            .id(IdUtils.create())
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .host(Property.ofValue("http://remote:11434"))
            .enableModelCaching(Property.ofValue(true))
            .modelCachePath(Property.ofValue("/tmp/.ollama"))
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var runner = task.configureTaskRunner(runContext);

        assertThat(runner, is(task.getTaskRunner()));
    }

    @Test
    void shouldPreserveUserVolumesNextToCacheVolume() throws Exception {
        var task = OllamaCLI.builder()
            .id(IdUtils.create())
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .taskRunner(OllamaDocker.builder().type(OllamaDocker.class.getName()).volumes(List.of("/data:/data")).build())
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var runner = (Docker) task.configureTaskRunner(runContext);

        assertThat(runner.getVolumes(), contains("/data:/data", "kestra-ollama-cache:/root/.ollama"));
    }
}
