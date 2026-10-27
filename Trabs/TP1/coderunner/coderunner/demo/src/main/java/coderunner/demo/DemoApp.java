package coderunner.demo;

import coderunner.contract.lookup.*;
import coderunner.contract.runner.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.*;

public class DemoApp {
    private static final String MANAGER_HOST = "localhost";
    private static final int MANAGER_PORT = 8080;

    // Cenários de teste exigidos pelo enunciado
    private static final String SCRIPT_OK = "print('Calculo bem-sucedido: 10 * 10 =', 10 * 10)";
    private static final String SCRIPT_LOOP = "import time\nwhile True:\n    time.sleep(1)";
    private static final String SCRIPT_MEM = "arr = []\nwhile True:\n    arr.append(b' ' * 10 * 1024 * 1024)";

    public static void main(String[] args) throws InterruptedException {
        int numClients = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        System.out.println("=== A iniciar simulador de carga concorrente (" + numClients + " clientes) ===");

        ExecutorService threadPool = Executors.newFixedThreadPool(numClients);
        CountDownLatch latch = new CountDownLatch(numClients);

        for (int i = 1; i <= numClients; i++) {
            final int clientId = i;
            String script;
            String testType;

            if (i % 3 == 1) {
                script = SCRIPT_OK;
                testType = "SUCESSO (NORMAL)";
            } else if (i % 3 == 2) {
                script = SCRIPT_LOOP;
                testType = "TIMEOUT (CICLO INFINITO)";
            } else {
                script = SCRIPT_MEM;
                testType = "ERRO (EXCESSO MEMORIA)";
            }

            threadPool.submit(() -> {
                runClientSimulation(clientId, testType, script);
                latch.countDown();
            });
        }

        latch.await();
        threadPool.shutdown();
        System.out.println("\n=== Todos os clientes concorrentes finalizaram a execucao! ===");
    }

    private static void runClientSimulation(int clientId, String testType, String pythonCode) {
        try {
            // 1. Obter RunServer do ManagerServer (Round-Robin)
            ManagedChannel mgrChannel = ManagedChannelBuilder.forAddress(MANAGER_HOST, MANAGER_PORT)
                    .usePlaintext()
                    .build();
            ManagerLookupGrpc.ManagerLookupBlockingStub lookupStub = ManagerLookupGrpc.newBlockingStub(mgrChannel);
            ServerLocation loc = lookupStub.getServer(LookupRequest.newBuilder().build());
            mgrChannel.shutdown();

            System.out.printf("[Cliente %d | %s] Alocado ao RunServer em %s:%d%n", clientId, testType, loc.getIp(), loc.getPort());

            // 2. Conectar ao RunServer atribuído e submeter via Client Streaming
            ManagedChannel runChannel = ManagedChannelBuilder.forAddress(loc.getIp(), loc.getPort())
                    .usePlaintext()
                    .build();
            RunServiceGrpc.RunServiceStub asyncStub = RunServiceGrpc.newStub(runChannel);
            RunServiceGrpc.RunServiceBlockingStub blockingStub = RunServiceGrpc.newBlockingStub(runChannel);

            CountDownLatch subLatch = new CountDownLatch(1);
            final String[] reqId = new String[1];

            StreamObserver<FileChunk> reqStream = asyncStub.submit(new StreamObserver<>() {
                @Override
                public void onNext(SubmitReply reply) {
                    reqId[0] = reply.getRequestId();
                }

                @Override
                public void onError(Throwable t) {
                    subLatch.countDown();
                }

                @Override
                public void onCompleted() {
                    subLatch.countDown();
                }
            });

            // Envia o ficheiro prog.py
            reqStream.onNext(FileChunk.newBuilder().setFilename("prog.py").build());
            reqStream.onNext(FileChunk.newBuilder().setData(ByteString.copyFrom(pythonCode, StandardCharsets.UTF_8)).build());
            reqStream.onCompleted();
            subLatch.await(10, TimeUnit.SECONDS);

            String id = reqId[0];
            System.out.printf("[Cliente %d] Pedido submetido com UUID: %s%n", clientId, id);

            // 3. Polling periódico até o estado deixar de ser PENDENTE
            String status = "PENDENTE";
            String stdout = "";
            String stderr = "";

            while ("PENDENTE".equalsIgnoreCase(status)) {
                Thread.sleep(2000);
                Iterator<ResultChunk> stream = blockingStub.getResult(ResultRequest.newBuilder().setRequestId(id).build());
                if (stream.hasNext()) {
                    ResultChunk chunk = stream.next();
                    status = chunk.getStatus();
                    stdout = chunk.getStdoutData().toStringUtf8().trim();
                    stderr = chunk.getStderrData().toStringUtf8().trim();
                }
            }

            System.out.printf("[Cliente %d | %s] Resposta: %s | Saida: [%s] | Erro: [%s]%n",
                    clientId, testType, status, stdout, stderr);

            runChannel.shutdown();
        } catch (Exception e) {
            System.err.printf("[Cliente %d] Falha: %s%n", clientId, e.getMessage());
        }
    }
}