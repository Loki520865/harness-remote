package com.harness.assistant;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 最小 WebSocket 客户端（RFC 6455，零第三方依赖，M1 用）。
 * 支持 ws:// 文本帧、ping/pong、close；不支持 wss(TLS，M2 加)。
 * 线程模型：单后台线程负责读帧；send 可任意线程调用。
 */
public class WsClient {

    public interface Listener {
        void onOpen();
        void onMessage(String text);
        void onClose(int code, String reason);
        void onError(String message);
    }

    private static final String TAG = "WsClient";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 回调串行执行器（修复：此前每回调 new Thread → 多线程并发回调 → 上层 HashMap 并发损坏 → 死循环/卡死/白屏） */
    private final ExecutorService callback = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile boolean running;
    private final Object sendLock = new Object();
    /** continuation 帧（opcode 0x0）累积缓冲 */
    private String pendingText = null;

    public WsClient(Listener listener) {
        this.listener = listener;
    }

    /** 异步连接：成功回调 onOpen，失败回调 onError */
    public void connect(String url) {
        connect(url, null);
    }

    /** 异步连接（可带自定义握手头，如 x-dsh-token）。 */
    public void connect(String url, Map<String, String> extraHeaders) {
        io.execute(() -> {
            try {
                UriParts u = parse(url);
                Socket s;
                if (u.secure) {
                    // wss: 信任系统 CA（Let's Encrypt IP 证书公共信任根，Android 自动信任）
                    javax.net.ssl.SSLSocketFactory sf =
                            (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                    javax.net.ssl.SSLSocket ssl =
                            (javax.net.ssl.SSLSocket) sf.createSocket();
                    // P1 修复：开启主机名校验（HTTPS endpoint identification，RFC 6125）——
                    // 此前 SSLSocket 只验证书链不验主机名，可被中间人用任意受信证书冒充
                    javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(params);
                    ssl.connect(new InetSocketAddress(u.host, u.port), 10_000);
                    s = ssl;
                } else {
                    s = new Socket();
                    s.connect(new InetSocketAddress(u.host, u.port), 10_000);
                }
                s.setSoTimeout(15_000); // 握手读超时（修复：服务端不回响应时永久阻塞）
                socket = s;
                handshake(s, u, extraHeaders);
                s.setSoTimeout(45_000); // 读超时 45s：服务器每 30s ping 保活，正常不会触发；
                // 半开连接（对端死/网络切换）时 45s 内无任何数据 → 抛 SocketTimeoutException → 断开重连（防假死）
                running = true;
                fire(() -> listener.onOpen());
                readLoop(s);
            } catch (IOException e) {
                Log.w(TAG, "连接失败: " + e.getMessage());
                fire(() -> listener.onError(e.getMessage()));
            } catch (Exception e) {
                Log.w(TAG, "异常: " + e.getMessage());
                fire(() -> listener.onError(e.getMessage()));
            }
        });
    }

    /** 发送文本帧（任意线程可调，线程安全） */
    public void send(String text) {
        Socket s = socket;
        if (s == null || !running) {
            fire(() -> listener.onError("连接未建立"));
            return;
        }
        try {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            byte[] frame = encodeFrame((byte) 0x1, payload, true);
            synchronized (sendLock) {
                OutputStream out = s.getOutputStream();
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            // 发送失败 = 连接不可用（缓冲满/对端死）：主动断开，让上层 onClose → 自动重连。
            // 修复：此前只 Log 会导致"假死"（UI 显示已连接但实际发不出去）
            Log.w(TAG, "发送失败，主动断开: " + e.getMessage());
            running = false;
            try { s.close(); } catch (IOException ignored) {}
            fire(() -> listener.onClose(1006, "发送失败: " + e.getMessage()));
        }
    }

    /** 关闭连接 */
    public void close() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }
        io.shutdown();
        callback.shutdown();
    }

    // ---------------- 握手 ----------------

    private static final class UriParts {
        String host;
        int port;
        String path;
        boolean secure;
    }

    private static UriParts parse(String url) throws IOException {
        boolean secure;
        String rest;
        if (url.startsWith("wss://")) {
            secure = true;
            rest = url.substring(6);
        } else if (url.startsWith("ws://")) {
            secure = false;
            rest = url.substring(5);
        } else {
            throw new IOException("仅支持 ws:// 或 wss:// 协议");
        }
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        int colon = authority.indexOf(':');
        String host;
        int port;
        if (colon >= 0) {
            host = authority.substring(0, colon);
            port = Integer.parseInt(authority.substring(colon + 1));
        } else {
            host = authority;
            port = secure ? 443 : 80;
        }
        UriParts u = new UriParts();
        u.host = host;
        u.port = port;
        u.path = path;
        u.secure = secure;
        return u;
    }

    private static void handshake(Socket s, UriParts u, Map<String, String> extraHeaders) throws Exception {
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(u.path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(u.host).append(":").append(u.port).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        // P1 修复（token 泄露面）：鉴权头走自定义头（x-dsh-token），不再拼 URL query
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 读响应头（到空行），不做预读缓冲，后续直接读二进制帧
        InputStream in = s.getInputStream();
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IOException("服务器未返回握手响应（连接被关闭，检查地址/防火墙）");
        }
        if (!statusLine.contains(" 101 ")) {
            throw new IOException("握手失败: " + statusLine);
        }
        String accept = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("sec-websocket-accept:")) {
                accept = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        // 校验 Sec-WebSocket-Accept
        String expect = Base64.getEncoder().encodeToString(
                sha1((key + GUID).getBytes(StandardCharsets.UTF_8)));
        if (accept == null || !accept.equals(expect)) {
            throw new IOException("Sec-WebSocket-Accept 校验失败");
        }
    }

    private static byte[] sha1(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return md.digest(data);
    }

    /** 逐字节读一行（读到 \n），返回不含换行的字符串；流结束返回 null */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (buf.size() == 0 && b == -1) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------- 帧编解码 ----------------

    /** 构造客户端帧（必须掩码）。opcode: 0x1=text, 0x8=close, 0x9=ping */
    private static byte[] encodeFrame(byte opcode, byte[] payload, boolean mask) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | opcode); // FIN=1
        int len = payload.length;
        if (len < 126) {
            out.write(mask ? 0x80 | len : len);
        } else if (len < 65536) {
            out.write(mask ? 0x80 | 126 : 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(mask ? 0x80 | 127 : 127);
            for (int i = 7; i >= 0; i--) out.write((int) ((long) len >>> (8 * i)) & 0xFF);
        }
        if (mask) {
            byte[] key = new byte[4];
            new SecureRandom().nextBytes(key);
            out.writeBytes(key);
            for (int i = 0; i < len; i++) {
                out.write(payload[i] ^ key[i % 4]);
            }
        } else {
            out.writeBytes(payload);
        }
        return out.toByteArray();
    }

    /** 读帧循环：收到文本 → onMessage；收到 ping → 回 pong；收到 close → 关闭。
     * 单帧解析异常不退出循环（避免一次坏帧导致静默断连），记录后继续。 */
    private void readLoop(Socket s) {
        try {
            InputStream in = s.getInputStream();
            while (running) {
                int b0 = in.read();
                if (b0 == -1) break;
                try {
                    int opcode = b0 & 0x0F;
                    int b1 = in.read();
                    if (b1 == -1) break;
                    boolean masked = (b1 & 0x80) != 0;
                    long len = b1 & 0x7F;
                    if (len == 126) {
                        int h = in.read();
                        int l = in.read();
                        if (h == -1 || l == -1) break;
                        len = (h << 8) | l;
                    } else if (len == 127) {
                        len = 0;
                        for (int i = 0; i < 8; i++) {
                            int b = in.read();
                            if (b == -1) break;
                            len = (len << 8) | b;
                        }
                    }
                    if (len > 0x7FFFFFFF) { // 防御：64 位长度截断为负数会导致 readNBytes 抛异常失步
                        Log.w(TAG, "帧长度超限，断开: " + len);
                        break;
                    }
                    byte[] maskKey = null;
                    if (masked) {
                        maskKey = in.readNBytes(4);
                        if (maskKey.length < 4) break;
                    }
                    byte[] payload = in.readNBytes((int) len);
                    if (payload.length < len) break; // EOF，连接已死
                    if (maskKey != null) {
                        for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];
                    }
                    boolean fin = (b0 & 0x80) != 0;

                    switch (opcode) {
                        case 0x1: { // text（支持分片：非 FIN 时开始累积）
                            String text = new String(payload, StandardCharsets.UTF_8);
                            if (fin) {
                                fire(() -> listener.onMessage(text));
                            } else {
                                pendingText = text;
                            }
                            break;
                        }
                        case 0x0: { // continuation
                            if (pendingText == null) break; // 无起始帧，忽略
                            String part = new String(payload, StandardCharsets.UTF_8);
                            pendingText += part;
                            if (fin) {
                                final String full = pendingText;
                                pendingText = null;
                                fire(() -> listener.onMessage(full));
                            }
                            break;
                        }
                        case 0x9: { // ping → pong
                            byte[] pong = encodeFrame((byte) 0xA, payload, true);
                            synchronized (sendLock) {
                                s.getOutputStream().write(pong);
                                s.getOutputStream().flush();
                            }
                            break;
                        }
                        case 0x8: { // close
                            running = false;
                            // 修复：解析 close 帧内的真实 close code/reason——
                            // 此前硬编码 1000，导致服务器 1008(未授权)等拒绝码丢失，
                            // 上层"登录失效停止重连"分支永不触发 → 登出/换端后无限重连
                            int closeCode = 1000;
                            String closeReason = "peer closed";
                            if (payload.length >= 2) {
                                closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                                if (payload.length > 2) {
                                    closeReason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                                }
                            }
                            try {
                                byte[] close = encodeFrame((byte) 0x8, new byte[0], true);
                                synchronized (sendLock) {
                                    s.getOutputStream().write(close);
                                    s.getOutputStream().flush();
                                }
                            } catch (IOException ignored) {}
                            final int c = closeCode;
                            final String r = closeReason;
                            fire(() -> listener.onClose(c, r));
                            return;
                        }
                        default:
                            // 其他帧（binary 等）忽略
                    }
                } catch (Exception e) {
                    // 读超时（假死检测）必须中断循环交给外层处理，否则会被吞掉死循环
                    if (e instanceof java.net.SocketTimeoutException) {
                        throw (java.net.SocketTimeoutException) e;
                    }
                    Log.w(TAG, "读帧异常（跳过继续）: " + e.getMessage());
                }
            }
            fire(() -> listener.onClose(1006, "连接断开"));
        } catch (IOException e) {
            Log.w(TAG, "读帧中断: " + e.getMessage());
            fire(() -> listener.onClose(1006, e.getMessage()));
        }
    }

    private void fire(Runnable r) {
        try {
            callback.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 已关闭（close 后到达的回调丢弃）
        }
    }
}
