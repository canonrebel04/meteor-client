/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SwarmConnection extends Thread {
    public final Socket socket;
    private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<>();

    public SwarmConnection(Socket socket) {
        this.socket = socket;
        start();
    }

    public void sendMessage(String message) {
        if (message == null) return;
        outgoing.offer(message);
    }

    @Override
    public void run() {
        ChatUtils.infoPrefix("Swarm", "New worker connected on %s.", getIp(socket.getInetAddress().getHostAddress()));

        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            while (!isInterrupted()) {
                try {
                    String message = outgoing.take();
                    out.writeUTF(message);
                    out.flush();
                } catch (InterruptedException e) {
                    interrupt();
                } catch (Exception e) {
                    ChatUtils.errorPrefix("Swarm", "Encountered error when sending command.");
                    e.printStackTrace();
                }
            }

            out.close();
        } catch (IOException e) {
            ChatUtils.infoPrefix("Swarm", "Error creating a connection with %s on port %s.", getIp(socket.getInetAddress().getHostAddress()), socket.getPort());
            e.printStackTrace();
        }
    }

    public void disconnect() {
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        ChatUtils.infoPrefix("Swarm", "Worker disconnected on ip: %s.", socket.getInetAddress().getHostAddress());

        interrupt();
    }

    public String getConnection() {
        return getIp(socket.getInetAddress().getHostAddress()) + ":" + socket.getPort();
    }

    private String getIp(String ip) {
        return ip.equals("127.0.0.1") ? "localhost" : ip;
    }
}
