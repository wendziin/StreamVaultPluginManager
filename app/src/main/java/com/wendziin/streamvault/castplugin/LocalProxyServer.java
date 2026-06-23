package com.wendziin.streamvault.castplugin;

import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalProxyServer {
    private static final String TAG = "StreamVaultProxy";
    private ServerSocket serverSocket;
    private int port = 0; // 0 will bind to a random free port
    private boolean isRunning = false;
    private ExecutorService threadPool;
    private static LocalProxyServer instance;

    public static synchronized LocalProxyServer getInstance() {
        if (instance == null) {
            instance = new LocalProxyServer();
        }
        return instance;
    }

    private LocalProxyServer() {
        threadPool = Executors.newCachedThreadPool();
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool.execute(() -> {
            try {
                serverSocket = new ServerSocket(0); // binds to a random free port
                port = serverSocket.getLocalPort();
                Log.i(TAG, "Proxy server started on port: " + port);
                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error running proxy server", e);
            }
        });
    }

    public synchronized void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                // ignore
            }
        }
        Log.i(TAG, "Proxy server stopped");
    }

    public int getPort() {
        return port;
    }

    public String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get local IP", e);
        }
        return "127.0.0.1";
    }

    public String getProxyUrl(String targetUrl) {
        if (targetUrl == null) return null;
        String base64Url = Base64.encodeToString(targetUrl.getBytes(), Base64.URL_SAFE | Base64.NO_WRAP);
        return "http://" + getLocalIpAddress() + ":" + getPort() + "/stream?data=" + base64Url;
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }

            Log.d(TAG, "Request: " + requestLine);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) {
                writeErrorResponse(socket.getOutputStream(), 405, "Method Not Allowed");
                socket.close();
                return;
            }

            String path = parts[1];
            Uri uri = Uri.parse(path);
            if (!uri.getPath().equals("/stream")) {
                writeErrorResponse(socket.getOutputStream(), 404, "Not Found");
                socket.close();
                return;
            }

            // Extract target URL
            String dataParam = uri.getQueryParameter("data");
            String targetUrl = null;
            if (dataParam != null) {
                targetUrl = new String(Base64.decode(dataParam, Base64.URL_SAFE | Base64.NO_WRAP));
            } else {
                String urlParam = uri.getQueryParameter("url");
                if (urlParam != null) {
                    targetUrl = URLDecoder.decode(urlParam, "UTF-8");
                }
            }

            if (targetUrl == null || targetUrl.isEmpty()) {
                writeErrorResponse(socket.getOutputStream(), 400, "Bad Request: Missing Target URL");
                socket.close();
                return;
            }

            // Extract request headers from client (especially Range)
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colonIndex = line.indexOf(":");
                if (colonIndex != -1) {
                    String key = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    headers.put(key, value);
                }
            }

            proxyStream(socket, targetUrl, headers);

        } catch (Exception e) {
            Log.e(TAG, "Error handling client request", e);
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void proxyStream(Socket clientSocket, String targetUrl, Map<String, String> clientHeaders) {
        HttpURLConnection conn = null;
        try {
            // Parse headers embedded via pipe syntax if present
            Map<String, String> customHeaders = new HashMap<>();
            if (targetUrl.contains("|")) {
                int pipeIndex = targetUrl.indexOf("|");
                String rawHeaders = targetUrl.substring(pipeIndex + 1);
                targetUrl = targetUrl.substring(0, pipeIndex);
                String[] pairs = rawHeaders.split("&");
                for (String pair : pairs) {
                    String[] kv = pair.split("=");
                    if (kv.length == 2) {
                        customHeaders.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                    }
                }
            }

            Log.i(TAG, "Proxying to: " + targetUrl);
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            // Forward Range header if requested by client (Chromecast uses Range extensively)
            if (clientHeaders.containsKey("Range")) {
                conn.setRequestProperty("Range", clientHeaders.get("Range"));
                Log.d(TAG, "Forwarded Range: " + clientHeaders.get("Range"));
            }

            // Apply custom headers from URL or standard User-Agent to bypass provider restrictions
            if (customHeaders.containsKey("User-Agent")) {
                conn.setRequestProperty("User-Agent", customHeaders.get("User-Agent"));
            } else {
                conn.setRequestProperty("User-Agent", "AppleCoreMedia/1.0.0.16G77 (Apple TV; U; CPU OS 12_4 like Mac OS X; en_us)");
            }

            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("User-Agent")) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            conn.connect();

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Target Response Code: " + responseCode);

            OutputStream clientOut = clientSocket.getOutputStream();
            BufferedOutputStream clientBuffer = new BufferedOutputStream(clientOut);

            // Write HTTP response status line
            clientBuffer.write(("HTTP/1.1 " + responseCode + " " + conn.getResponseMessage() + "\r\n").getBytes());

            // Write HTTP headers back to the client
            Map<String, List<String>> targetHeaders = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : targetHeaders.entrySet()) {
                String key = entry.getKey();
                if (key != null) {
                    // Skip Transfer-Encoding to avoid chucked encoding conflicts if we're sending raw content
                    if (key.equalsIgnoreCase("Transfer-Encoding")) continue;
                    
                    for (String val : entry.getValue()) {
                        clientBuffer.write((key + ": " + val + "\r\n").getBytes());
                    }
                }
            }
            clientBuffer.write("\r\n".getBytes());
            clientBuffer.flush();

            // Pipe stream data
            InputStream targetIn = conn.getInputStream();
            BufferedInputStream targetBuffer = new BufferedInputStream(targetIn);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = targetBuffer.read(buffer)) != -1) {
                clientBuffer.write(buffer, 0, bytesRead);
            }
            clientBuffer.flush();

        } catch (Exception e) {
            Log.e(TAG, "Error proxying stream", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void writeErrorResponse(OutputStream out, int statusCode, String message) {
        try {
            String response = "HTTP/1.1 " + statusCode + " " + message + "\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: " + message.length() + "\r\n" +
                    "\r\n" +
                    message;
            out.write(response.getBytes());
            out.flush();
        } catch (Exception e) {
            // ignore
        }
    }
}
