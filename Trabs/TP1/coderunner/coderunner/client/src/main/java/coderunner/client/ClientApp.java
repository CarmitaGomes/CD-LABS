package coderunner.client;

import coderunner.contract.lookup.*;
import coderunner.contract.runner.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ClientApp {
    private static final String MANAGER_HOST = "localhost";
    private static final int MANAGER_PORT = 8080;

    public static void main(String[] args) {
        System.out.println("=== Bem-vindo ao CodeRunner Client ===");

        // 1. Obter RunServer junto do ManagerServer (Contrato b - Load Balancing)
        ManagedChannel managerChannel = ManagedChannelBuilder.forAddress(MANAGER_HOST, MANAGER_PORT)
                .usePlaintext()
                .build();
        ManagerLookupGrpc.ManagerLookupBlockingStub lookupStub = ManagerLookupGrpc.newBlockingStub(managerChannel);

        ServerLocation runLocation;
        try {
            runLocation = lookupStub.getServer(LookupRequest.newBuilder().build());
            System.out.println("Servidor atribuído pelo Manager: " + runLocation.getIp() + ":" + runLocation.getPort());
        } catch (Exception e) {
            System.err.println("Erro ao contactar o Manager: " + e.getMessage());
            managerChannel.shutdown();
            return;
        }
        managerChannel.shutdown();

        // 2. Preparar canal de comunicação com o RunServer atribuído
        ManagedChannel runChannel = ManagedChannelBuilder.forAddress(runLocation.getIp(), runLocation.getPort())
                .usePlaintext()
                .build();
        RunServiceGrpc.RunServiceStub asyncStub = RunServiceGrpc.newStub(runChannel);
        RunServiceGrpc.RunServiceBlockingStub blockingStub = RunServiceGrpc.newBlockingStub(runChannel);

        Scanner scanner = new Scanner(System.in);
        int op;
        do {
            op = menu(scanner);
            switch (op) {
                case 1 -> submitPythonScript(scanner, asyncStub);
                case 2 -> checkResult(scanner, blockingStub);
                case 0 -> System.out.println("A terminar sessão...");
            }
        } while (op != 0);

        runChannel.shutdown();
    }

    private static int menu(Scanner scan) {
        int op;
        do {
            System.out.println("\n---------------- MENU ----------------");
            System.out.println(" 1 - Submeter programa Python (.py)");
            System.out.println(" 2 - Consultar resultado (por UUID)");
            System.out.println(" 0 - Sair");
            System.out.print("Opção: ");
            while (!scan.hasNextInt()) {
                scan.next();
                System.out.print("Opção inválida. Digite novamente: ");
            }
            op = scan.nextInt();
            scan.nextLine();
        } while (op != 1 && op != 2 && op != 0);
        return op;
    }

    // Operação c1: Client Streaming para upload de código e input
    private static void submitPythonScript(Scanner scan, RunServiceGrpc.RunServiceStub asyncStub) {
        System.out.print("Caminho para o ficheiro .py: ");
        String pyPathStr = scan.nextLine().trim();
        Path pyPath = Paths.get(pyPathStr);

        if (!Files.exists(pyPath)) {
            System.out.println("Ficheiro não encontrado: " + pyPathStr);
            return;
        }

        System.out.print("Caminho para input.txt (ou Enter se não tiver): ");
        String inPathStr = scan.nextLine().trim();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> requestId = new AtomicReference<>();

        StreamObserver<FileChunk> requestObserver = asyncStub.submit(new StreamObserver<>() {
            @Override
            public void onNext(SubmitReply reply) {
                requestId.set(reply.getRequestId());
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Erro na submissão: " + t.getMessage());
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        try {
            // 1. Enviar prog.py
            requestObserver.onNext(FileChunk.newBuilder().setFilename("prog.py").build());
            byte[] pyBytes = Files.readAllBytes(pyPath);
            requestObserver.onNext(FileChunk.newBuilder().setData(ByteString.copyFrom(pyBytes)).build());

            // 2. Enviar input.txt (se existir)
            if (!inPathStr.isEmpty() && Files.exists(Paths.get(inPathStr))) {
                requestObserver.onNext(FileChunk.newBuilder().setFilename("input.txt").build());
                byte[] inBytes = Files.readAllBytes(Paths.get(inPathStr));
                requestObserver.onNext(FileChunk.newBuilder().setData(ByteString.copyFrom(inBytes)).build());
            }

            requestObserver.onCompleted();
            if (latch.await(10, TimeUnit.SECONDS) && requestId.get() != null) {
                System.out.println(">> Submissão aceite com sucesso!");
                System.out.println(">> Identificador único do pedido (UUID): " + requestId.get());
            }
        } catch (Exception e) {
            System.err.println("Falha ao transmitir ficheiros: " + e.getMessage());
        }
    }

    // Operação c2: Server Streaming para leitura do estado e ficheiros
    private static void checkResult(Scanner scan, RunServiceGrpc.RunServiceBlockingStub blockingStub) {
        System.out.print("Indique o UUID do pedido: ");
        String id = scan.nextLine().trim();

        try {
            Iterator<ResultChunk> stream = blockingStub.getResult(ResultRequest.newBuilder().setRequestId(id).build());

            while (stream.hasNext()) {
                ResultChunk chunk = stream.next();
                System.out.println("Estado do Pedido: " + chunk.getStatus());

                // Tratamento do requisito de reencaminhamento de servidor
                if (!chunk.getRedirectIp().isEmpty() && chunk.getRedirectPort() != 0) {
                    System.out.println("Este pedido foi executado noutro servidor: " + chunk.getRedirectIp() + ":" + chunk.getRedirectPort());
                    System.out.println("A ligar ao servidor responsável...");

                    ManagedChannel otherChannel = ManagedChannelBuilder.forAddress(chunk.getRedirectIp(), chunk.getRedirectPort())
                            .usePlaintext().build();
                    RunServiceGrpc.RunServiceBlockingStub otherStub = RunServiceGrpc.newBlockingStub(otherChannel);
                    checkResultDirect(otherStub, id);
                    otherChannel.shutdown();
                    return;
                }

                if ("PENDENTE".equalsIgnoreCase(chunk.getStatus())) {
                    System.out.println("O pedido ainda está em execução no Docker. Tente novamente daqui a instantes.");
                    return;
                }

                // Exibir conteúdo de stdout e stderr
                String stdout = chunk.getStdoutData().toStringUtf8();
                String stderr = chunk.getStderrData().toStringUtf8();

                System.out.println("\n--- [ STDOUT ] ---");
                System.out.println(stdout.isEmpty() ? "(vazio)" : stdout);
                System.out.println("--- [ STDERR ] ---");
                System.out.println(stderr.isEmpty() ? "(vazio)" : stderr);
            }
        } catch (Exception e) {
            System.err.println("Erro ao obter resultado: " + e.getMessage());
        }
    }

    private static void checkResultDirect(RunServiceGrpc.RunServiceBlockingStub stub, String id) {
        Iterator<ResultChunk> stream = stub.getResult(ResultRequest.newBuilder().setRequestId(id).build());
        while (stream.hasNext()) {
            ResultChunk chunk = stream.next();
            System.out.println("Estado: " + chunk.getStatus());
            System.out.println("--- [ STDOUT ] ---\n" + chunk.getStdoutData().toStringUtf8());
            System.out.println("--- [ STDERR ] ---\n" + chunk.getStderrData().toStringUtf8());
        }
    }
}
