package coderunner.manager;

import coderunner.contract.lookup.*;
import coderunner.contract.registry.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class ManagerServer {
    private static final int PORT = 8080;

    // Estruturas thread-safe para suportar acessos concorrentes
    private static final List<ServerLocation> runServers = new CopyOnWriteArrayList<>();
    private static final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = ServerBuilder.forPort(PORT)
                .addService(new RegistryImpl()) // Contrato (a): Registo do RunServer
                .addService(new LookupImpl())   // Contrato (b): Pedido do Cliente por RunServer
                .build();

        server.start();
        System.out.println("ManagerServer iniciado no porto " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("A encerrar o ManagerServer...");
            server.shutdown();
        }));

        server.awaitTermination();
    }

    // Contrato (a) - ManagerRegistry: RunServer -> Manager
    static class RegistryImpl extends ManagerRegistryGrpc.ManagerRegistryImplBase {
        @Override
        public void registerRunServer(RegisterRequest request, StreamObserver<RegisterReply> responseObserver) {
            ServerLocation location = ServerLocation.newBuilder()
                    .setIp(request.getIp())
                    .setPort(request.getPort())
                    .build();

            runServers.add(location);
            System.out.println("Novo RunServer registado: " + request.getIp() + ":" + request.getPort());

            // Devolve endereço do Redis (que corre na VM/máquina do Manager)
            RegisterReply reply = RegisterReply.newBuilder()
                    .setRedisIp("localhost")
                    .setRedisPort(6379)
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }

    // Contrato (b) - ManagerLookup: Client -> Manager
    static class LookupImpl extends ManagerLookupGrpc.ManagerLookupImplBase {
        @Override
        public void getServer(LookupRequest request, StreamObserver<ServerLocation> responseObserver) {
            if (runServers.isEmpty()) {
                responseObserver.onError(io.grpc.Status.UNAVAILABLE
                        .withDescription("Nenhum RunServer registado no momento.")
                        .asRuntimeException());
                return;
            }

            // Algoritmo Round-Robin para balancear os clientes
            int index = Math.abs(roundRobinIndex.getAndIncrement() % runServers.size());
            ServerLocation chosen = runServers.get(index);
            System.out.println("Cliente reencaminhado para RunServer: " + chosen.getIp() + ":" + chosen.getPort());

            responseObserver.onNext(chosen);
            responseObserver.onCompleted();
        }
    }
}
