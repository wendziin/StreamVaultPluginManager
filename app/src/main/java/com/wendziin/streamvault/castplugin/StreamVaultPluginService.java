package com.wendziin.streamvault.castplugin;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

public class StreamVaultPluginService extends Service {
    private static final String TAG = "StreamVaultPlugin";

    // Message ID constants from StreamVault Plugin API
    private static final int MSG_GET_MANIFEST = 1;
    private static final int MSG_SET_ENABLED = 2;
    private static final int MSG_GET_STATUS = 3;
    private static final int MSG_REWRITE_CAST_URL = 6;

    private static final String MANIFEST_JSON = "{"
            + "\"schemaVersion\": 1,"
            + "\"id\": \"com.wendziin.streamvault.castplugin\","
            + "\"name\": \"StreamVault Cast Proxy\","
            + "\"versionName\": \"1.0.0\","
            + "\"versionCode\": 1,"
            + "\"description\": \"Reescreve e faz proxy de links de filmes/séries para reproduzir via Google Cast.\","
            + "\"providerName\": \"Cast Proxy\","
            + "\"configurationMode\": \"activity\","
            + "\"configurationActivityAction\": \"com.wendziin.streamvault.castplugin.CONFIGURE\","
            + "\"capabilities\": ["
            + "  \"cast.rewriteUrl\","
            + "  \"configuration.activity\""
            + "]"
            + "}";

    private boolean isEnabled = false;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "StreamVault Plugin Service created");
        // Start proxy server automatically when service starts
        LocalProxyServer.getInstance().start();
        isEnabled = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "StreamVault Plugin Service destroyed");
        LocalProxyServer.getInstance().stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (messenger == null) {
            messenger = new Messenger(new IncomingHandler());
        }
        return messenger.getBinder();
    }

    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle data = msg.getData();
            if (data == null) return;

            int apiVersion = data.getInt("api_version", 1);
            String requestId = data.getString("request_id", "");

            Log.d(TAG, "Received message: " + msg.what + ", requestId: " + requestId);

            Message reply = Message.obtain(null, msg.what);
            Bundle replyData = new Bundle();
            replyData.putInt("api_version", apiVersion);
            replyData.putString("request_id", requestId);
            replyData.putBoolean("success", true);

            switch (msg.what) {
                case MSG_GET_MANIFEST:
                    replyData.putString("manifest_json", MANIFEST_JSON);
                    break;

                case MSG_SET_ENABLED:
                    isEnabled = data.getBoolean("enabled", false);
                    if (isEnabled) {
                        LocalProxyServer.getInstance().start();
                    } else {
                        LocalProxyServer.getInstance().stop();
                    }
                    Log.i(TAG, "Plugin enabled state set to: " + isEnabled);
                    break;

                case MSG_GET_STATUS:
                    String status = isEnabled ? "Ativo" : "Inativo";
                    String message = isEnabled 
                            ? "Proxy ativo no IP: " + LocalProxyServer.getInstance().getLocalIpAddress() + ":" + LocalProxyServer.getInstance().getPort()
                            : "Plugin desabilitado.";
                    replyData.putString("status_label", status);
                    replyData.putString("message", message);
                    break;

                case MSG_REWRITE_CAST_URL:
                    String inputUrl = data.getString("input_url", "");
                    Log.i(TAG, "Cast URL rewrite request for: " + inputUrl);
                    if (inputUrl != null && !inputUrl.isEmpty() && !inputUrl.contains("127.0.0.1") && !inputUrl.contains("localhost")) {
                        String outputUrl = LocalProxyServer.getInstance().getProxyUrl(inputUrl);
                        replyData.putBoolean("handled", true);
                        replyData.putString("output_url", outputUrl);
                        Log.i(TAG, "Rewrote Cast URL to proxy: " + outputUrl);
                    } else {
                        replyData.putBoolean("handled", false);
                    }
                    break;

                default:
                    super.handleMessage(msg);
                    return;
            }

            reply.setData(replyData);
            try {
                if (msg.replyTo != null) {
                    msg.replyTo.send(reply);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send IPC reply", e);
            }
        }
    }
}
