package io.kestra.plugin.ollama.cli;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
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
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .enableModelCaching(Property.ofValue(true))
            .build();

        RunContext runContext = runContextWithVolumeEnabled(TestsUtils.mockRunContext(runContextFactory, task, Map.of()), true);
        var runner = (Docker) task.configureTaskRunner(runContext);

        assertThat(runner.getVolumes(), contains("kestra-ollama-cache:/root/.ollama"));
    }

    @Test
    void shouldNotAddCacheVolumeWhenDockerVolumesDisabledByAdmin() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .enableModelCaching(Property.ofValue(true))
            .build();

        RunContext runContext = runContextWithVolumeEnabled(TestsUtils.mockRunContext(runContextFactory, task, Map.of()), false);
        var runner = (Docker) task.configureTaskRunner(runContext);

        assertThat(runner.getVolumes(), nullValue());
    }

    @Test
    void shouldRejectBlankModelCachePathOnRun() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .modelCachePath(Property.ofValue("   "))
            .build();

        RunContext runContext = runContextWithVolumeEnabled(TestsUtils.mockRunContext(runContextFactory, task, Map.of()), true);

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(error.getMessage(), is("modelCachePath must not be blank"));
    }

    @Test
    void shouldRejectEnableModelCachingWhenHostIsSet() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .host(Property.ofValue("http://remote:11434"))
            .enableModelCaching(Property.ofValue(true))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        var error = assertThrows(IllegalArgumentException.class, () -> task.configureTaskRunner(runContext));
        assertThat(error.getMessage(), is("enableModelCaching cannot be used when host is set"));
    }

    @Test
    void shouldRejectModelCachePathWhenHostIsSet() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .host(Property.ofValue("http://remote:11434"))
            .enableModelCaching(Property.ofValue(false))
            .modelCachePath(Property.ofValue("/tmp/.ollama"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        var error = assertThrows(IllegalArgumentException.class, () -> task.configureTaskRunner(runContext));
        assertThat(error.getMessage(), is("modelCachePath cannot be used when host is set"));
    }

    private static RunContext runContextWithVolumeEnabled(RunContext runContext, boolean volumeEnabled) throws Exception {
        if (!(runContext instanceof DefaultRunContext defaultRunContext)) {
            return runContext;
        }
        var clone = defaultRunContext.clone();
        Method setPluginConfiguration = DefaultRunContext.class.getDeclaredMethod("setPluginConfiguration", Map.class);
        setPluginConfiguration.setAccessible(true);
        setPluginConfiguration.invoke(clone, Map.of(OllamaCLI.VOLUME_ENABLED_CONFIG, volumeEnabled));
        return clone;
    }
}
