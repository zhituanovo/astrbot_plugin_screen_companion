package com.astrbot.screencompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "screen_companion";
    private static final int JPEG_QUALITY = 75;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private EditText serverUrl;
    private EditText authToken;
    private TextView status;
    private MediaProjection mediaProjection;
    private ScreenSocket socket;
    private boolean capabilitiesSent;

    private final ActivityResultLauncher<Intent> projectionPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    showStatus("未获得录屏权限，无法响应截图请求。");
                    return;
                }
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = manager.getMediaProjection(result.getResultCode(), result.getData());
                connect();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        serverUrl = findViewById(R.id.serverUrl);
        authToken = findViewById(R.id.authToken);
        status = findViewById(R.id.status);
        Button connectButton = findViewById(R.id.connectButton);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl.setText(prefs.getString("server_url", ""));
        authToken.setText(prefs.getString("auth_token", ""));
        connectButton.setOnClickListener(view -> requestProjectionAndConnect());
    }

    private void requestProjectionAndConnect() {
        String url = serverUrl.getText().toString().trim();
        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
            showStatus("服务器地址需要以 ws:// 或 wss:// 开头。");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("server_url", url)
                .putString("auth_token", authToken.getText().toString())
                .apply();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projectionPermission.launch(manager.createScreenCaptureIntent());
    }

    private void connect() {
        try {
            if (socket != null) socket.close();
            capabilitiesSent = false;
            socket = new ScreenSocket(new URI(serverUrl.getText().toString().trim()));
            socket.connect();
            showStatus("正在连接远程识屏服务...");
        } catch (Exception e) {
            showStatus("服务器地址无效：" + e.getMessage());
        }
    }

    private void sendCapabilities() {
        if (capabilitiesSent || socket == null || !socket.isOpen()) return;
        capabilitiesSent = true;
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "client_capabilities");
            payload.put("client_id", "android-" + UUID.randomUUID());
            payload.put("capabilities", new JSONArray().put("request_screenshot"));
            socket.send(payload.toString());
        } catch (Exception e) {
            showStatus("能力协商失败：" + e.getMessage());
        }
    }

    private void captureAndSend(String requestId) {
        if (mediaProjection == null || socket == null || !socket.isOpen()) {
            sendCaptureError(requestId, "capture_failed", "录屏权限或连接不可用");
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        VirtualDisplay display = mediaProjection.createVirtualDisplay(
                "ScreenCompanionCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, mainHandler);

        reader.setOnImageAvailableListener(imageReader -> {
            Image image = imageReader.acquireLatestImage();
            if (image == null) return;
            try {
                byte[] jpeg = toJpeg(image, width, height);
                JSONObject meta = new JSONObject();
                meta.put("type", "screenshot_meta");
                meta.put("request_id", requestId);
                meta.put("window_title", "Android 屏幕");
                meta.put("timestamp", System.currentTimeMillis() / 1000.0);
                meta.put("client_id", "android");
                meta.put("capture_scope", "fullscreen");
                socket.send(meta.toString());
                socket.send(jpeg);
                showStatus("已响应插件的截图请求。");
            } catch (Exception e) {
                sendCaptureError(requestId, "capture_failed", e.getMessage());
            } finally {
                image.close();
                display.release();
                reader.close();
            }
        }, mainHandler);
    }

    private byte[] toJpeg(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        Bitmap padded = Bitmap.createBitmap(rowStride / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(plane.getBuffer());
        Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, width, height);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output);
        bitmap.recycle();
        padded.recycle();
        return output.toByteArray();
    }

    private void sendCaptureError(String requestId, String code, String error) {
        if (socket == null || !socket.isOpen()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", "capture_error");
            payload.put("request_id", requestId);
            payload.put("code", code);
            payload.put("error", error == null ? "截图失败" : error);
            socket.send(payload.toString());
            showStatus("截图失败：" + payload.getString("error"));
        } catch (Exception ignored) {
            showStatus("截图失败，错误上报也未发送。");
        }
    }

    private void showStatus(String text) {
        mainHandler.post(() -> status.setText(text));
    }

    @Override
    protected void onDestroy() {
        if (socket != null) socket.close();
        if (mediaProjection != null) mediaProjection.stop();
        super.onDestroy();
    }

    private final class ScreenSocket extends WebSocketClient {
        ScreenSocket(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            String token = authToken.getText().toString();
            if (!token.isEmpty()) {
                try {
                    JSONObject auth = new JSONObject();
                    auth.put("token", token);
                    send(auth.toString());
                } catch (Exception e) {
                    showStatus("认证消息创建失败：" + e.getMessage());
                    return;
                }
            }
            showStatus("已连接，正在协商截图能力...");
        }

        @Override
        public void onMessage(String message) {
            try {
                JSONObject payload = new JSONObject(message);
                String type = payload.optString("type");
                String state = payload.optString("status");
                if ("authenticated".equals(state) || "ready".equals(state)) {
                    sendCapabilities();
                } else if ("capabilities_received".equals(state)) {
                    showStatus("已连接。插件需要时会请求一次新的屏幕截图。");
                } else if ("request_screenshot".equals(type)) {
                    captureAndSend(payload.optString("request_id"));
                } else if ("ping".equals(type)) {
                    JSONObject pong = new JSONObject();
                    pong.put("type", "pong");
                    pong.put("ts", System.currentTimeMillis() / 1000.0);
                    send(pong.toString());
                }
            } catch (Exception e) {
                showStatus("协议消息处理失败：" + e.getMessage());
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            showStatus("连接已关闭：" + reason);
        }

        @Override
        public void onError(Exception error) {
            showStatus("连接错误：" + error.getMessage());
        }
    }
}
