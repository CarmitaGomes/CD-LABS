package coderunner.runserver;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class ContainerExecutor {
    private static final String IMAGE = "python:3.12-slim";
    private final DockerClient docker;

    public ContainerExecutor() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        this.docker = DockerClientImpl.getInstance(config, httpClient);
    }

    public int runTask(String requestId, Path taskDir) {
        String containerName = "run-" + requestId;
        String hostPath = taskDir.toAbsolutePath().toString();

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode("none")                     // Sem rede
                .withMemory(256L * 1024 * 1024)              // Limite 256MB
                .withMemorySwap(256L * 1024 * 1024)
                .withBinds(new Bind(hostPath, new Volume("/work")));

        // Cria os ficheiros locais de input/output caso ainda não existam
        try {
            if (!Files.exists(taskDir.resolve("input.txt"))) Files.createFile(taskDir.resolve("input.txt"));
            if (!Files.exists(taskDir.resolve("out.txt"))) Files.createFile(taskDir.resolve("out.txt"));
            if (!Files.exists(taskDir.resolve("err.txt"))) Files.createFile(taskDir.resolve("err.txt"));
            if (!Files.exists(taskDir.resolve("exit.txt"))) Files.createFile(taskDir.resolve("exit.txt"));
        } catch (IOException ignored) {}

        String shellCmd = "timeout 10 python /work/prog.py < /work/input.txt > /work/out.txt 2> /work/err.txt; echo $? > /work/exit.txt";

        CreateContainerResponse container = null;
        try {
            container = docker.createContainerCmd(IMAGE)
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withWorkingDir("/work")
                    .withCmd("sh", "-c", shellCmd)
                    .exec();

            docker.startContainerCmd(container.getId()).exec();
            docker.waitContainerCmd(container.getId()).exec(new WaitContainerResultCallback()).awaitCompletion();

            Path exitFile = taskDir.resolve("exit.txt");
            if (Files.exists(exitFile)) {
                String val = Files.readString(exitFile).trim();
                return val.isEmpty() ? -1 : Integer.parseInt(val);
            }
            return -1;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        } finally {
            // Requisito obrigatório: O container é sempre removido no final
            if (container != null) {
                try {
                    docker.removeContainerCmd(container.getId()).withForce(true).exec();
                } catch (Exception ignored) {}
            }
        }
    }
}
