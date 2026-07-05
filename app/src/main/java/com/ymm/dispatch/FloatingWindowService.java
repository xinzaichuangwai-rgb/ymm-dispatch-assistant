package com.ymm.dispatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 悬浮窗服务：显示可拖动的悬浮窗，提供截屏OCR和语音转文字功能
 */
public class FloatingWindowService extends Service {

    public static boolean isRunning = false;
    private static Intent mediaProjectionData;

    private WindowManager windowManager;
    private View floatingView;
    private TextView tvVoiceText;
    private TextView tvResultText;
    private Button btnOCR, btnVoice, btnYMM, btnCopy;

    private MediaProjection mediaProjection;
    private TextRecognizer textRecognizer;

    // 语音识别
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;

    private float startX, startY;
    private int initialX, initialY;

    // 运满满包名列表
    private static final String[] YMM_PACKAGES = {
        "com.manbuyuwanba",
        "com.mmtrix.yunmanman",
        "com.ymm.android",
        "cn.manbuyuwanba"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        createNotificationChannel();
        showFloatingWindow();
        isRunning = true;
    }

    public static void setMediaProjectionData(Intent data) {
        mediaProjectionData = data;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "dispatch_channel", "抢单助手", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, "dispatch_channel")
                .setContentTitle("运满满抢单助手运行中")
                .setContentText("悬浮窗已开启")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());

        // 初始化MediaProjection
        if (mediaProjectionData != null) {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            try {
                mediaProjection = mpm.getMediaProjection(
                        android.app.Activity.RESULT_OK, mediaProjectionData);
            } catch (Exception e) {
                // 权限可能已失效
            }
        }

        return START_STICKY;
    }

    // ==================== 悬浮窗 ====================
    private void showFloatingWindow() {
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 30;
        params.y = 200;

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

        tvVoiceText = floatingView.findViewById(R.id.tvVoiceText);
        tvResultText = floatingView.findViewById(R.id.tvResultText);
        btnOCR = floatingView.findViewById(R.id.btnOCR);
        btnVoice = floatingView.findViewById(R.id.btnVoice);
        btnYMM = floatingView.findViewById(R.id.btnYMM);
        Button btnFold = floatingView.findViewById(R.id.btnFold);
        Button btnClose = floatingView.findViewById(R.id.btnClose);
        btnCopy = floatingView.findViewById(R.id.btnCopy);
        final View bodyLayout = floatingView.findViewById(R.id.bodyLayout);
        final boolean[] folded = {false};

        // 标题栏拖动
        View header = floatingView.findViewById(R.id.headerLayout);
        header.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    initialX = params.x;
                    initialY = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - startX);
                    int dy = (int) (event.getRawY() - startY);
                    params.x = initialX + dx;
                    params.y = initialY + dy;
                    windowManager.updateViewLayout(floatingView, params);
                    return true;
            }
            return false;
        });

        // 折叠/展开
        btnFold.setOnClickListener(v -> {
            if (folded[0]) {
                bodyLayout.setVisibility(View.VISIBLE);
                btnFold.setText("─");
                folded[0] = false;
            } else {
                bodyLayout.setVisibility(View.GONE);
                btnFold.setText("＋");
                folded[0] = true;
            }
        });

        // 关闭
        btnClose.setOnClickListener(v -> {
            stopListening();
            stopSelf();
        });

        // OCR抓取
        btnOCR.setOnClickListener(v -> captureAndExtract());

        // 语音听音
        btnVoice.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            } else {
                startListening();
            }
        });

        // 切回运满满
        btnYMM.setOnClickListener(v -> launchYMM());

        // 复制结果
        btnCopy.setOnClickListener(v -> {
            String text = tvResultText.getText().toString();
            if (text != null && !text.isEmpty() && !text.contains("点「抓取」")) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("dispatch", text));
                Toast.makeText(this, "✅ 已复制到剪贴板", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "暂无内容，先点「抓取」", Toast.LENGTH_SHORT).show();
            }
        });

        windowManager.addView(floatingView, params);
    }

    // ==================== 截屏OCR ====================
    private void captureAndExtract() {
        if (mediaProjection == null) {
            tvResultText.setText("❌ 截屏权限未授权，请重新启动助手");
            return;
        }

        tvResultText.setText("⏳ 正在截屏识别...");

        new Thread(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getMetrics(metrics);
                int width = metrics.widthPixels;
                int height = metrics.heightPixels;

                android.hardware.display.VirtualDisplay virtualDisplay =
                        mediaProjection.createVirtualDisplay("screen_capture",
                                width, height, metrics.densityDpi,
                                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                null, null, null);

                // 等待一帧渲染
                Thread.sleep(200);

                // 使用ImageReader截取
                android.media.ImageReader imageReader = android.media.ImageReader.newInstance(
                        width, height, PixelFormat.RGBA_8888, 2);

                android.hardware.display.VirtualDisplay vd =
                        mediaProjection.createVirtualDisplay("capture2",
                                width, height, metrics.densityDpi,
                                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                imageReader.getSurface(), null, null);

                android.media.Image image = imageReader.acquireLatestImage();
                int attempts = 0;
                while (image == null && attempts < 10) {
                    Thread.sleep(100);
                    image = imageReader.acquireLatestImage();
                    attempts++;
                }

                if (image != null) {
                    android.media.Image.Plane[] planes = image.getPlanes();
                    java.nio.ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;

                    bitmap = android.graphics.Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    // 裁掉padding
                    bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height);

                    image.close();
                }
                imageReader.close();
                if (vd != null) vd.release();
                if (virtualDisplay != null) virtualDisplay.release();

                if (bitmap == null) {
                    runOnUI(() -> tvResultText.setText("❌ 截屏失败，请重试"));
                    return;
                }

                // OCR识别
                InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
                textRecognizer.process(inputImage)
                        .addOnSuccessListener(visionText -> {
                            String fullText = visionText.getText();
                            String result = extractAndFormat(fullText);
                            tvResultText.setText(result);

                            // 复制到剪贴板
                            ClipboardManager cm = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("dispatch", result));
                            Toast.makeText(this, "✅ 抓取成功，已复制", Toast.LENGTH_SHORT).show();
                        })
                        .addOnFailureListener(e -> runOnUI(() ->
                                tvResultText.setText("❌ 识别失败: " + e.getMessage())))
                        .addOnCompleteListener(task -> {
                            if (bitmap != null) bitmap.recycle();
                        });

            } catch (Exception e) {
                String msg = e.getMessage();
                runOnUI(() -> tvResultText.setText("❌ 出错: " + msg));
                if (bitmap != null) bitmap.recycle();
            }
        }).start();
    }

    // ==================== 正则提取 ====================
    private String extractAndFormat(String fullText) {
        if (fullText == null || fullText.trim().isEmpty()) {
            return "❌ 未识别到文字";
        }

        String t = fullText.replaceAll("[ \\t]+", " ").replaceAll("\\s+", "\n");

        String from = "", to = "", time = "", goods = "", weight = "", price = "";

        // 发货地
        Matcher fromM = Pattern.compile(
                "(?:发货地|起运地|装货地|始发地?|从|起点)\\s*[:：]?\\s*([^\\n，,。;；]{2,30})").matcher(t);
        if (fromM.find()) from = cleanPlace(fromM.group(1));

        // 卸货地
        Matcher toM = Pattern.compile(
                "(?:卸货地|收货地|到货地|目的地|终点|到|送达)\\s*[:：]?\\s*([^\\n，,。;；]{2,30})").matcher(t);
        if (toM.find()) to = cleanPlace(toM.group(1));

        // 装货时间
        Matcher timeM = Pattern.compile(
                "(?:装货时间|装货|时间|发车)\\s*[:：]?\\s*([^\\n]{2,20}?(?:号|日|点|上午|下午|明天|后天|今天)[^\\n]{0,10})").matcher(t);
        if (timeM.find()) time = timeM.group(1).trim();

        // 货物
        Matcher goodsM = Pattern.compile(
                "(?:货物|货品|物品|货名|货)\\s*[:：]?\\s*([^\\n，,。;；]{2,15})").matcher(t);
        if (goodsM.find()) goods = goodsM.group(1).trim();

        // 吨数/方数
        Matcher weightM = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(吨|方|吨位|立方)").matcher(t);
        if (weightM.find()) weight = weightM.group(1) + weightM.group(2);

        // 运费
        Matcher priceM = Pattern.compile(
                "(?:运费|价格|运价|给)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:元|块|圆)?").matcher(t);
        if (priceM.find()) price = priceM.group(1) + "元";

        // 兜底：XX→XX格式
        if (from.isEmpty() || to.isEmpty()) {
            Matcher arrowM = Pattern.compile(
                    "([\\u4e00-\\u9fa5]{2,8}(?:市|区|县|镇))\\s*[→\\-—>到]+\\s*([\\u4e00-\\u9fa5]{2,8}(?:市|区|县|镇))")
                    .matcher(t);
            if (arrowM.find()) {
                if (from.isEmpty()) from = arrowM.group(1);
                if (to.isEmpty()) to = arrowM.group(2);
            }
        }

        StringBuilder sb = new StringBuilder("【抢单速报】\n");
        if (!from.isEmpty()) sb.append("📍 发货地: ").append(from).append("\n");
        if (!to.isEmpty()) sb.append("🏁 卸货地: ").append(to).append("\n");
        if (!time.isEmpty()) sb.append("🕐 装货时间: ").append(time).append("\n");
        if (!goods.isEmpty()) sb.append("📦 货物: ").append(goods).append("\n");
        if (!weight.isEmpty()) sb.append("⚖ 货量: ").append(weight).append("\n");
        if (!price.isEmpty()) sb.append("💰 运费: ").append(price);

        if (sb.length() < 10) {
            sb.append("\n⚠️ 未识别到关键字段\n原始文字:\n").append(t.substring(0, Math.min(t.length(), 200)));
        }

        return sb.toString();
    }

    private String cleanPlace(String s) {
        return s.replaceAll("^[:：\\s]+|[:：\\s]+$", "")
                .replaceAll("(发货地|起运地|装货地|卸货地|收货地|目的地)", "")
                .trim();
    }

    // ==================== 语音转文字 ====================
    private void initSpeechRecognizer() {
        if (speechRecognizer != null) return;

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            runOnUI(() -> tvVoiceText.setText("⚠️ 手机不支持语音识别，请检查语音引擎"));
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        speechIntent.putExtra("android.speech.extra.PARTIAL_RESULTS", true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                runOnUI(() -> tvVoiceText.setText("🎤 正在聆听..."));
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                if (error == 6) { // no-speech
                    // 静默重启
                } else if (error == 2 || error == 1) { // network
                    runOnUI(() -> tvVoiceText.setText("⚠️ 语音需联网，错误码:" + error));
                } else if (error != 7) { // 7=aborted, 正常停止
                    runOnUI(() -> tvVoiceText.setText("⚠️ 语音错误码:" + error + "，重试中..."));
                }
                // 自动重启保持连续监听
                if (isListening && speechRecognizer != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if (isListening && speechRecognizer != null)
                                speechRecognizer.startListening(speechIntent);
                        } catch (Exception e) {}
                    }, 800);
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    String text = list.get(0);
                    final String result = extractAndFormat(text);
                    runOnUI(() -> {
                        String display = text.length() > 60 ? text.substring(0, 60) + "..." : text;
                        tvVoiceText.setText("🎯 " + display);
                        // 如果提取到了信息，也更新结果区
                        if (result.length() > 10) {
                            tvResultText.setText(result);
                        }
                    });
                }
                // 连续识别
                if (isListening && speechRecognizer != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if (isListening && speechRecognizer != null)
                                speechRecognizer.startListening(speechIntent);
                        } catch (Exception e) {}
                    }, 300);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    String text = list.get(0);
                    String display = text.length() > 60 ? text.substring(0, 60) + "..." : text;
                    runOnUI(() -> tvVoiceText.setText("🔊 " + display));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListening() {
        isListening = true;
        btnVoice.setText("⏸停止");
        btnVoice.setBackgroundColor(Color.parseColor("#F44336"));

        if (speechRecognizer == null) {
            initSpeechRecognizer();
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.startListening(speechIntent);
                Toast.makeText(this, "🎤 已开始监听，请打开运满满语音播报",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                tvVoiceText.setText("❌ 启动失败: " + e.getMessage());
            }
        }
    }

    private void stopListening() {
        isListening = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception e) {}
            try { speechRecognizer.destroy(); } catch (Exception e) {}
            speechRecognizer = null;
        }
        runOnUI(() -> {
            if (btnVoice != null) {
                btnVoice.setText("🎤听音");
                btnVoice.setBackgroundColor(Color.parseColor("#4CAF50"));
            }
            if (tvVoiceText != null) {
                tvVoiceText.setText("🛑 已停止监听");
            }
        });
    }

    // ==================== 启动运满满 ====================
    private void launchYMM() {
        // 先尝试通过包名启动
        for (String pkg : YMM_PACKAGES) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                }
            } catch (Exception e) {}
        }
        // 兜底：按名称搜索
        Toast.makeText(this, "⚠️ 未找到运满满，请确认已安装", Toast.LENGTH_SHORT).show();
    }

    // ==================== 工具方法 ====================
    private void runOnUI(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        stopListening();
        if (textRecognizer != null) textRecognizer.close();
        if (mediaProjection != null) mediaProjection.stop();
        if (floatingView != null) {
            try { windowManager.removeView(floatingView); } catch (Exception e) {}
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
