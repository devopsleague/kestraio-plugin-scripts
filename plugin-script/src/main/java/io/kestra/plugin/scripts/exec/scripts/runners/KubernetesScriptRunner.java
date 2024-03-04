package io.kestra.plugin.scripts.exec.scripts.runners;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class KubernetesScriptRunner implements ScriptRunner {
    private final DockerOptions dockerOptions;

    public KubernetesScriptRunner(DockerOptions dockerOptions) {
        this.dockerOptions = dockerOptions;
    }

    @Override
    public RunnerResult run(CommandsWrapper commands) throws Exception {
        if (dockerOptions == null) {
            throw new IllegalArgumentException("Missing required docker properties");
        }

        RunContext runContext = commands.getRunContext();
        Logger logger = commands.getRunContext().logger();
        String image = runContext.render(dockerOptions.getImage(), commands.getAdditionalVars());
        AbstractLogConsumer defaultLogConsumer = commands.getLogConsumer();

        Container container = createContainer(image, commands);
        Pod pod = createPod(container);

        try (var client = new KubernetesClientBuilder().build()) {
            PodResource resource = client.pods().resource(pod);
            resource.create();
            logger.info("Pod '{}' is created ", pod.getMetadata().getName());

            try (var unused = resource.watch(new PodWatcher(logger))) {
                // wait for terminated
                //TODO externalize the duration
                Pod completed = waitForCompletion(resource, Duration.ofMinutes(1), p -> p.getStatus().getContainerStatuses().stream().allMatch(s -> s.getState().getTerminated() != null));

                // get logs TODO ideally in realtime but as it's a poc it's OK to do it like this
                logger.info(resource.getLog());

                if (completed.getStatus() != null && completed.getStatus().getPhase().equals("Failed")) {
                    throw failedMessage(completed);
                }

                return new RunnerResult(
                    completed.getStatus().getContainerStatuses().get(0).getState().getTerminated().getExitCode(),
                    defaultLogConsumer
                );
            } finally {
                try {
                    resource.delete();
                    logger.info("Pod '{}' is deleted ", pod.getMetadata().getName());
                } catch (Throwable e) {
                    logger.warn("Unable to delete pod {}", pod.getFullResourceName(), e);
                }
            }
        }
    }

    private Container createContainer(String image, CommandsWrapper commands) {
        return new ContainerBuilder()
            .withName(IdUtils.create().toLowerCase()) //TODO we may find a better name
            .withImage(image)
            .withImagePullPolicy(convert(dockerOptions.getPullPolicy()))
            .withCommand(commands.getCommands())
            .build();
    }

    private String convert(DockerOptions.PullPolicy pullPolicy) {
        return switch (pullPolicy) {
            case ALWAYS -> "Always";
            case IF_NOT_PRESENT -> "IfNotPresent";
            case NEVER -> "Never";
        };
    }

    private Pod createPod(Container container) {
        return new PodBuilder()
            .withSpec(
                new PodSpecBuilder()
                    .withContainers(container)
                    .withRestartPolicy("Never") // TODO maybe use OnFailure?
                    .build()
            )
            .withMetadata(
                new ObjectMetaBuilder()
                    .withName(IdUtils.create().toLowerCase()) //TODO we may find a better name
                    .build()
            )
            .build();
    }

    public static Pod waitForCompletion(PodResource resource, Duration waitRunning, Predicate<Pod> condition) {
        Pod ended = null;
        while (ended == null) {
            ended = resource
                .waitUntilCondition(
                    condition,
                    waitRunning.toSeconds(),
                    TimeUnit.SECONDS
                );
        }

        return ended;
    }

    public static IllegalStateException failedMessage(Pod pod) throws IllegalStateException {
        if (pod.getStatus() == null) {
            return new IllegalStateException("Pods terminated without any status !");
        }

        return (pod.getStatus().getContainerStatuses() == null ? new ArrayList<ContainerStatus>() : pod.getStatus().getContainerStatuses())
            .stream()
            .filter(containerStatus -> containerStatus.getState() != null && containerStatus.getState().getTerminated() != null)
            .map(containerStatus -> containerStatus.getState().getTerminated())
            .findFirst()
            .map(containerStateTerminated -> new IllegalStateException(
                "Pods terminated with status '" + pod.getStatus().getPhase() + "', " +
                    "exitcode '" + containerStateTerminated.getExitCode() + "' & " +
                    "message '" + containerStateTerminated.getMessage() + "'"
            ))
            .orElse(new IllegalStateException("Pods terminated without any containers status !"));
    }

    private static class PodWatcher implements Watcher<Pod> {
        private final Logger logger;

        private PodWatcher(Logger logger){
            this.logger = logger;
        }

        @Override
        public void eventReceived(Action action, Pod pod) {
            String msg = String.join(
                ", ",
                "Type: " + pod.getClass().getSimpleName(),
                "Namespace: " + pod.getMetadata().getNamespace(),
                "Name: " + pod.getMetadata().getName(),
                "Uid: " + pod.getMetadata().getUid(),
                "Phase: " + pod.getStatus().getPhase()
            );
            logger.debug("Received action '{}' on [{}]", action, msg);
        }

        @Override
        public void onClose(WatcherException e) {
            logger.debug("Received close on [Type: {}]", this.getClass().getSimpleName());
        }
    }
}
