package com.tabpad;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Мережевий клієнт: текстовий протокол по TCP (NoDelay), рухи миші склеюються,
 * щоб не засмічувати канал, а порядок команд зберігається.
 */
public class Net {
    public static final int PORT = 5555;
    public static final int DISC_PORT = 5556;

    public interface Listener {
        void onState(boolean connected, String msg);
    }

    public interface DiscoverCb {
        void onResult(List<String> found);
    }

    private final Object lock = new Object();
    private final ArrayList<String> queue = new ArrayList<String>();
    private int pdx, pdy;
    private volatile boolean connected;
    private volatile Socket sock;
    private volatile int generation;
    private Listener listener;

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isConnected() {
        return connected;
    }

    // ---------- з'єднання ----------

    public void connect(final String host, final int port, final String pin) {
        disconnect();
        final int gen = ++generation;
        Thread t = new Thread(new Runnable() {
            public void run() {
                session(host, port, pin, gen);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void disconnect() {
        generation++;
        connected = false;
        Socket s = sock;
        sock = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                // ignore
            }
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void fire(boolean ok, String msg) {
        Listener l = listener;
        if (l != null) l.onState(ok, msg);
    }

    private void session(String host, int port, String pin, int gen) {
        Socket s = new Socket();
        String err = null;
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(4000);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            out.write(("HELLO " + pin + "\n").getBytes("UTF-8"));
            out.flush();
            String r = in.readLine();
            if (r == null) throw new IOException("сервер закрив з'єднання");
            if (!r.equals("OK")) {
                err = "Невірний PIN";
            } else if (gen == generation) {
                synchronized (lock) {
                    queue.clear();
                    pdx = 0;
                    pdy = 0;
                }
                sock = s;
                connected = true;
                fire(true, "");
                loop(out, gen);
                err = "З'єднання втрачено";
            }
        } catch (Exception e) {
            String m = e.getMessage();
            err = "Не вдалося з'єднатися: " + (m == null ? e.getClass().getSimpleName() : m);
        } finally {
            if (gen == generation) connected = false;
            try {
                s.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (gen == generation) fire(false, err == null ? "Відключено" : err);
    }

    private void loop(OutputStream out, int gen) throws IOException {
        while (gen == generation) {
            String batch;
            synchronized (lock) {
                if (queue.isEmpty() && pdx == 0 && pdy == 0) {
                    try {
                        lock.wait(1500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < queue.size(); i++) sb.append(queue.get(i)).append('\n');
                queue.clear();
                if (pdx != 0 || pdy != 0) {
                    sb.append("M ").append(pdx).append(' ').append(pdy).append('\n');
                    pdx = 0;
                    pdy = 0;
                }
                if (sb.length() == 0) sb.append("P\n"); // heartbeat
                batch = sb.toString();
            }
            if (gen != generation) return;
            out.write(batch.getBytes("UTF-8"));
            out.flush();
        }
    }

    // ---------- команди ----------

    private void enqueue(String cmd) {
        if (!connected) return;
        synchronized (lock) {
            if (pdx != 0 || pdy != 0) {
                queue.add("M " + pdx + " " + pdy);
                pdx = 0;
                pdy = 0;
            }
            queue.add(cmd);
            lock.notifyAll();
        }
    }

    public void move(int dx, int dy) {
        if (!connected) return;
        synchronized (lock) {
            pdx += dx;
            pdy += dy;
            lock.notifyAll();
        }
    }

    public void scroll(int delta) {
        enqueue("S " + delta);
    }

    /** b: 1 = ліва, 2 = права, 3 = середня */
    public void button(int b, boolean down) {
        enqueue("B " + b + " " + (down ? 1 : 0));
    }

    public void click(int b) {
        enqueue("L " + b);
    }

    public void key(int vk, boolean down) {
        enqueue("K " + vk + " " + (down ? 1 : 0));
    }

    /** Комбінація: усі натискаються по черзі, відпускаються у зворотному порядку. */
    public void combo(int... vks) {
        StringBuilder sb = new StringBuilder("X ");
        for (int i = 0; i < vks.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vks[i]);
        }
        enqueue(sb.toString());
    }

    public void ch(char c) {
        enqueue("U " + (int) c);
    }

    // ---------- пошук ноутбука ----------

    public static void discover(final DiscoverCb cb) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                List<String> found = new ArrayList<String>();
                DatagramSocket s = null;
                try {
                    s = new DatagramSocket();
                    s.setBroadcast(true);
                    s.setSoTimeout(300);
                    byte[] msg = "TABPAD?".getBytes("UTF-8");
                    Set<InetAddress> targets = new LinkedHashSet<InetAddress>();
                    targets.add(InetAddress.getByName("255.255.255.255"));
                    Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                    while (en != null && en.hasMoreElements()) {
                        NetworkInterface ni = en.nextElement();
                        try {
                            if (ni.isLoopback()) continue;
                        } catch (Exception e) {
                            // ignore
                        }
                        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                            InetAddress b = ia.getBroadcast();
                            if (b != null) targets.add(b);
                        }
                    }
                    for (int round = 0; round < 4 && found.isEmpty(); round++) {
                        for (InetAddress target : targets) {
                            try {
                                s.send(new DatagramPacket(msg, msg.length, target, DISC_PORT));
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                        long end = System.currentTimeMillis() + 700;
                        while (System.currentTimeMillis() < end) {
                            try {
                                DatagramPacket r = new DatagramPacket(new byte[256], 256);
                                s.receive(r);
                                String txt = new String(r.getData(), 0, r.getLength(), "UTF-8");
                                if (txt.startsWith("TABPAD ")) {
                                    String entry = r.getAddress().getHostAddress() + " " + txt.substring(7).trim();
                                    if (!found.contains(entry)) found.add(entry);
                                }
                            } catch (SocketTimeoutException e) {
                                // чекаємо далі
                            }
                        }
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    if (s != null) s.close();
                }
                cb.onResult(found);
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
