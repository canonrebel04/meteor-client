/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class SwarmHost extends Thread {
    private ServerSocket socket;
    private final SwarmConnection[] clientConnections = new SwarmConnection[50];

    public SwarmHost(int port) {
        try {
            socket = new ServerSocket(port);
        } catch (IOException e) {
            socket = null;
            ChatUtils.errorPrefix("Swarm", "Couldn't start a server on port %s.", port);
            e.printStackTrace();
        }

        if (socket != null) start();
    }

    @Override
    public void run() {
        ChatUtils.infoPrefix("Swarm", "Listening for incoming connections on port %s.", socket.getLocalPort());

        while (!isInterrupted()) {
            try {
                Socket connection = socket.accept();
                assignConnectionToSubServer(connection);
            } catch (IOException e) {
                ChatUtils.errorPrefix("Swarm", "Error making a connection to worker.");
                e.printStackTrace();
            }
        }
    }

    public void assignConnectionToSubServer(Socket connection) {
        for (int i = 0; i < clientConnections.length; i++) {
            if (this.clientConnections[i] == null) {
                this.clientConnections[i] = new SwarmConnection(connection);
                break;
            }
        }
    }

    public void disconnect() {
        for (SwarmConnection connection : clientConnections) {
            if (connection != null) connection.disconnect();
        }

        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        ChatUtils.infoPrefix("Swarm", "Server closed on port %s.", socket.getLocalPort());

        interrupt();
    }

    public void sendMessage(String s) {
        MeteorExecutor.execute(() -> {
            String wrapped = wrapMessage(s);
            for (SwarmConnection connection : clientConnections) {
                if (connection != null) {
                    connection.sendMessage(wrapped);
                }
            }
        });
    }

    public void sendMessageTo(int index, String s) {
        if (index < 0 || index >= clientConnections.length) return;

        MeteorExecutor.execute(() -> {
            SwarmConnection connection = clientConnections[index];
            if (connection != null) connection.sendMessage(wrapMessage(s));
        });
    }

    private String wrapMessage(String message) {
        if (message == null) return null;

        Swarm swarm = Modules.get().get(Swarm.class);
        if (swarm == null) return message;
        if (!swarm.requireToken.get()) return message;

        String token = swarm.token.get();
        if (token == null || token.isBlank()) return message;

        // Worker will unwrap and validate before dispatching.
        return "swarm|" + token + "|" + message;
    }

    public SwarmConnection[] getConnections() {
        return clientConnections;
    }

    public int getConnectionCount() {
        int count = 0;

        for (SwarmConnection clientConnection : clientConnections) {
            if (clientConnection != null) count++;
        }

        return count;
    }
}
