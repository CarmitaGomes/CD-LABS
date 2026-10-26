package coderunner.runserver;

import coderunner.contract.registry.*;
import coderunner.contract.runner.*;
import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import redis.clients.jedis.JedisPooled;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RunServer {
    private static final String MANAGER_HOST = "localhost";
    private static final int MANAGER_PORT = 8080;
    private static int myPort = 8001;

    private static JedisPooled jedis;
    private static ContainerExecutor executor;
    private static final ExecutorService taskPool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 0) myPort = Integer.parseInt(args[0]);

        // 1. Registo junto do ManagerServer (Contrato a)
        ManagedChannel channel = ManagedChannelBuilder.forAddress(MANAGER_HOST, MANAGER_PORT).usePlaintext().build();
        ManagerRegistryGrpc.ManagerRegistryBlockingStub regStub = ManagerRegistryGrpc.newBlockingStub(channel);

        System.out.println("A registar RunServer no Manager...");
        RegisterReply reply = regStub.registerRunServer(RegisterRequest.newBuilder()
                .setIp("localhost")
                .setPort(myPort)
                .build());
        channel.shutdown();

        System.out.println("Registado com sucesso! Redis atribuído em: " + reply.getRedisIp() + ":" + reply.getRedisPort());

        // 2. Conectar ao Redis e ao Docker
        jedis = new JedisPooled(reply.getRedisIp(), reply.getRedisPort());
        executor = new ContainerExecutor();

        // 3. Iniciar servidor gRPC do RunServer (Contrato c)
        Server server = ServerBuilder.forPort(myPort)
                .addService(new RunServiceImpl())
                .build();

        server.start();
        System.out.println("RunServer ativo e à escuta no porto " + myPort);
        server.awaitTermination();
    }

    static class RunServiceImpl extends RunServiceGrpc.RunServiceImplBase {

        // c1: Submissão de ficheiros via streaming de cliente
        @Override
        public StreamObserver<FileChunk> submit(StreamObserver<SubmitReply> responseObserver) {
            String requestId = UUID.randomUUID().toString();
            Path reqDir = Paths.get("requests", requestId);

            return new StreamObserver<>() {
                private FileOutputStream fos;

                @Override
                public void onNext(FileChunk chunk) {
                    try {
                        if (!Files.exists(reqDir)) Files.createDirectories(reqDir);
                        if (chunk.hasFilename()) {
                            if (fos != null) fos.close();
                            fos = new FileOutputStream(reqDir.resolve(chunk.getFilename()).toFile());
                        } else if (chunk.hasData()) {
                            if (fos != null) fos.write(chunk.getData().toByteArray());
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onError(Throwable t) {
                    try { if (fos != null) fos.close(); } catch (IOException ignored) {}
                }

                @Override
                public void onCompleted() {
                    try { if (fos != null) fos.close(); } catch (IOException ignored) {}

                    // Regista o estado inicial PENDENTE no Redis
                    jedis.hset("req:" + requestId, Map.of(
                            "estado", "PENDENTE",
                            "ip", "localhost",
                            "port", String.valueOf(myPort)
                    ));

                    // Responde de imediato com o ID do pedido
                    responseObserver.onNext(SubmitReply.newBuilder().setRequestId(requestId).build());
                    responseObserver.onCompleted();

                    // Executa a tarefa no Docker em background
                    taskPool.submit(() -> {
                        int exitCode = executor.runTask(requestId, reqDir);
                        String finalState = switch (exitCode) {
                            case 0 -> "CONCLUIDO";
                            case 124 -> "TIMEOUT";
                            default -> "ERRO";
                        };

                        jedis.hset("req:" + requestId, Map.of(
                                "estado", finalState,
                                "exitCode", String.valueOf(exitCode)
                        ));
                    });
                }
            };
        }

        // c2: Obtenção de resultado via streaming de servidor
        @Override
        public void getResult(ResultRequest request, StreamObserver<ResultChunk> responseObserver) {
            String id = request.getRequestId();
            Map<String, String> data = jedis.hgetAll("req:" + id);

            if (data == null || data.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Pedido não encontrado.").asRuntimeException());
                return;
            }

            String estado = data.get("estado");
            String ip = data.get("ip");
            int port = Integer.parseInt(data.get("port"));

            // Se o pedido pertence a outro RunServer, envia o redirecionamento
            if (port != myPort) {
                responseObserver.onNext(ResultChunk.newBuilder()
                        .setStatus(estado)
                        .setRedirectIp(ip)
                        .setRedirectPort(port)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // Se ainda não concluiu a execução
            if ("PENDENTE".equals(estado)) {
                responseObserver.onNext(ResultChunk.newBuilder().setStatus("PENDENTE").build());
                responseObserver.onCompleted();
                return;
            }

            // Envia o conteúdo dos ficheiros out.txt e err.txt
            Path reqDir = Paths.get("requests", id);
            try {
                byte[] stdoutBytes = Files.exists(reqDir.resolve("out.txt")) ? Files.readAllBytes(reqDir.resolve("out.txt")) : new byte[0];
                byte[] stderrBytes = Files.exists(reqDir.resolve("err.txt")) ? Files.readAllBytes(reqDir.resolve("err.txt")) : new byte[0];

                responseObserver.onNext(ResultChunk.newBuilder()
                        .setStatus(estado)
                        .setStdoutData(ByteString.copyFrom(stdoutBytes))
                        .setStderrData(ByteString.copyFrom(stderrBytes))
                        .build());
                responseObserver.onCompleted();

                // Limpeza dos ficheiros locais após o cliente obter a resposta
                Files.walk(reqDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            } catch (Exception e) {
                responseObserver.onError(e);
            }
        }
    }
}