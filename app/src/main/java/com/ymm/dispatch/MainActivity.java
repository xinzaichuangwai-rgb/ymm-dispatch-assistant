package com.ymm.dispatch;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * 主界面：权限检查 + 启动悬浮窗服务
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private Button btnStart;
    private Button btnStop;

    // 媒体投影（截屏）请求
    private final ActivityResultLauncher<Void> mediaProjectionLauncher =
            registerForActivityResult(new MediaProjectionResultContract(), result -> {
                if (result != null) {
                    FloatingWindowService.setMediaProjectionData(result);
                    checkPermissionsAndStart();
                } else {
                    tvStatus.setText("❌ 截屏授权失败，无法使用抓取功能");
                }
            });

    // 录音权限请求
    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startService();
                } else {
                    Toast.makeText(this, "需要麦克风权限才能语音转文字", Toast.LENGTH_SHORT).show();
                    startService(); // 仍然启动，语音功能不可用但OCR可用
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        updateStatus();

        btnStart.setOnClickListener(v -> {
            // 第一步：检查悬浮窗权限
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.setText("⚠️ 请先开启悬浮窗权限");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
            // 第二步：请求截屏权限
            tvStatus.setText("请在接下来弹窗中允许截屏...");
            mediaProjectionLauncher.launch(null);
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingWindowService.class));
            tvStatus.setText("已停止");
        });
    }

    private void checkPermissionsAndStart() {
        // 第三步：检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            startService();
        }
    }

    private void startService() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        tvStatus.setText("✅ 悬浮窗已启动\n\n现在打开运满满，悬浮窗会显示在最上层。\n\n" +
                "📷 点「抓取」截屏识别订单信息\n" +
                "🎤 点「听音」监听语音播报\n" +
                "📲 点「运满满」秒切回运满满");
        updateStatus();
    }

    private void updateStatus() {
        boolean overlayOK = Settings.canDrawOverlays(this);
        boolean audioOK = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean running = FloatingWindowService.isRunning;

        StringBuilder sb = new StringBuilder();
        sb.append("🚛 运满满抢单助手 v1.0\n\n");
        sb.append("权限状态：\n");
        sb.append(overlayOK ? "  ✅ 悬浮窗权限\n" : "  ❌ 悬浮窗权限（未开启）\n");
        sb.append(audioOK ? "  ✅ 麦克风权限\n" : "  ❌ 麦克风权限（未开启）\n");
        sb.append("  ✅ 截屏权限（每次启动需授权）\n\n");
        sb.append(running ? "🟢 悬浮窗运行中\n" : "⚫ 悬浮窗未启动\n");
        tvStatus.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    /**
     * 自定义ActivityResultContract，用于请求MediaProjection
     */
    private static class MediaProjectionResultContract
            extends ActivityResultContracts.ActivityResultContract<Void, Intent> {

        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, Void input) {
            return ((android.media.projection.MediaProjectionManager)
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE))
                    .createScreenCaptureIntent();
        }

        @Override
        public Intent parseResult(int resultCode, @NonNull android.content.Intent intent) {
            if (resultCode != android.app.Activity.RESULT_OK) return null;
            return intent;
        }
    }
}
