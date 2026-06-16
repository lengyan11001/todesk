package top.bhzn.todesk;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private LinearLayout content;
    private Button homeTab;
    private Button permissionTab;
    private Button fileTab;
    private Button logTab;
    private TextView statusLine;
    private int activeTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.info(this, "MainActivity", "onCreate");
        buildShell();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DiagnosticLog.info(this, "MainActivity", "onResume");
        if (RemoteService.isRunning()) {
            startService(new Intent(this, RemoteService.class).setAction(RemoteService.ACTION_STATUS));
        }
        render();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            DiagnosticLog.info(this, "MainActivity", "media projection result=" + resultCode + " data=" + (data != null));
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, RemoteService.class)
                        .setAction(RemoteService.ACTION_START)
                        .putExtra(RemoteService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(RemoteService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(service);
                } else {
                    startService(service);
                }
                DiagnosticLog.info(this, "MainActivity", "start remote service requested");
                Toast.makeText(this, "远控服务已启动", Toast.LENGTH_SHORT).show();
            } else {
                DiagnosticLog.info(this, "MainActivity", "media projection denied");
                Toast.makeText(this, "未获得录屏权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            render();
        }
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 247, 251));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(12));
        header.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this);
        title.setText("BHZN ToDesk");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(23, 32, 42));
        title.setGravity(Gravity.START);
        title.setTypeface(null, 1);
        statusLine = new TextView(this);
        statusLine.setTextSize(13);
        statusLine.setTextColor(Color.rgb(92, 106, 122));
        statusLine.setPadding(0, dp(4), 0, 0);
        header.addView(title);
        header.addView(statusLine);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(12), dp(10), dp(12), dp(10));
        tabs.setBackgroundColor(Color.WHITE);
        homeTab = tabButton("设备");
        permissionTab = tabButton("权限");
        fileTab = tabButton("文件");
        logTab = tabButton("日志");
        tabs.addView(homeTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(permissionTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(fileTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(logTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        homeTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeTab = 0;
                render();
            }
        });
        permissionTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeTab = 1;
                render();
            }
        });
        fileTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeTab = 2;
                render();
            }
        });
        logTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeTab = 3;
                render();
            }
        });
        setContentView(root);
    }

    private Button tabButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private void render() {
        if (content == null) return;
        boolean running = RemoteService.isRunning();
        boolean media = RemoteService.isMediaReady();
        boolean accessibility = PermissionState.isAccessibilityEnabled(this);
        boolean input = PermissionState.isInputControlReady(this);
        boolean server = RemoteService.isServerConnected();
        statusLine.setText(running ? (server ? "服务运行中 · 已连接服务器" : "服务运行中 · 正在连接服务器") : "服务未启动");
        homeTab.setEnabled(activeTab != 0);
        permissionTab.setEnabled(activeTab != 1);
        fileTab.setEnabled(activeTab != 2);
        logTab.setEnabled(activeTab != 3);
        content.removeAllViews();
        if (activeTab == 0) {
            renderHome(media, accessibility, input, running, server);
        } else if (activeTab == 1) {
            renderPermissions(media, accessibility, input, running);
        } else if (activeTab == 2) {
            renderFiles();
        } else {
            renderLogs();
        }
    }

    private void renderHome(boolean media, boolean accessibility, boolean input, boolean running, boolean server) {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button start = primaryButton(running ? "重新开启" : "开启服务");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestMediaProjection();
            }
        });
        Button stop = dangerButton("暂停服务");
        stop.setEnabled(running);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, RemoteService.class).setAction(RemoteService.ACTION_STOP));
                render();
            }
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        startParams.setMargins(0, 0, dp(8), 0);
        controls.addView(start, startParams);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        stopParams.setMargins(dp(8), 0, 0, 0);
        controls.addView(stop, stopParams);
        content.addView(controls, blockParams());

        copyCard("设备 ID", AppPrefs.deviceId(this), "复制设备 ID");
        copyCard("验证码", AppPrefs.deviceCode(this), "复制验证码");

        String state = "录屏：" + yesNo(media)
                + "    无障碍：" + yesNo(accessibility)
                + "    输入服务：" + yesNo(input)
                + "    服务：" + yesNo(running)
                + "    服务器：" + yesNo(server);
        String error = RemoteService.lastError();
        if (error != null && error.length() > 0) {
            state += "\n启动错误：" + error;
        }
        card("当前状态", state, 16, false);

        Button resetCode = dangerButton("刷新验证码");
        resetCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.resetDeviceCode(MainActivity.this);
                if (RemoteService.isRunning()) {
                    startService(new Intent(MainActivity.this, RemoteService.class).setAction(RemoteService.ACTION_STATUS));
                }
                Toast.makeText(MainActivity.this, "设备验证码已刷新", Toast.LENGTH_SHORT).show();
                render();
            }
        });
        content.addView(resetCode, blockParams());
    }

    private void renderPermissions(boolean media, boolean accessibility, boolean input, boolean running) {
        permissionRow(
                "录屏权限",
                media ? "已授权，屏幕画面可传到网页端" : "未授权，点击后系统会弹出屏幕录制确认",
                "申请录屏",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestMediaProjection();
                    }
                }
        );
        permissionRow(
                "无障碍输入",
                input ? "输入服务已就绪，可执行点击、滑动、返回、主页"
                        : (accessibility ? "系统权限已开启，输入服务未连接；请返回本应用或关闭后重新开启该无障碍服务"
                        : "未开启，需要在系统设置中开启 BHZN ToDesk 输入控制"),
                "打开设置",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }
        );
        permissionRow(
                "悬浮窗权限",
                PermissionState.canDrawOverlays(this) ? "已开启" : "未开启，后续可用于显示远控状态浮窗",
                "打开设置",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        }
                    }
                }
        );
        permissionRow(
                "通知权限",
                PermissionState.canPostNotifications(this) ? "已开启" : "未开启，Android 13+ 前台服务需要通知权限",
                "申请通知",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestNotificationPermission();
                    }
                }
        );
        permissionRow(
                "后台保活",
                isIgnoringBatteryOptimizations() ? "已允许后台持续运行" : "建议设为不受限制，避免切换应用后连接被系统回收",
                "打开设置",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestIgnoreBatteryOptimizations();
                    }
                }
        );
        String ready = media && input
                ? "设备已准备好，在 H5 输入设备 ID 即可发起控制。"
                : "请先补齐录屏和无障碍输入服务。";
        card("接管准备状态", ready, 16, false);
    }

    private void renderFiles() {
        final List<FileTransferStore.Record> records = FileTransferStore.list(this);
        TextView title = smallLabel("接收记录");
        content.addView(title);

        Button openDownloads = primaryButton("打开下载目录");
        openDownloads.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDownloadsFolder();
            }
        });
        content.addView(openDownloads, blockParams());

        if (records.isEmpty()) {
            card("暂无文件", "H5 下发文件后，会在这里显示文件名、大小、保存位置和接收时间。", 16, false);
            return;
        }

        for (final FileTransferStore.Record record : records) {
            LinearLayout box = cardBox();
            TextView name = new TextView(this);
            name.setText(record.fileName);
            name.setTextColor(Color.rgb(23, 32, 42));
            name.setTextSize(17);
            name.setTypeface(null, 1);

            TextView detail = new TextView(this);
            detail.setText(formatBytes(record.bytes) + " · " + formatTime(record.receivedAt) + "\n" + record.path);
            detail.setTextColor(Color.rgb(92, 106, 122));
            detail.setTextSize(13);
            detail.setPadding(0, dp(6), 0, dp(12));

            Button open = primaryButton("打开文件");
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openReceivedFile(record);
                }
            });

            box.addView(name);
            box.addView(detail);
            box.addView(open, new LinearLayout.LayoutParams(-1, dp(42)));
            content.addView(box, blockParams());
        }

        Button clear = dangerButton("清空记录");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FileTransferStore.clear(MainActivity.this);
                render();
            }
        });
        content.addView(clear, blockParams());
    }

    private void renderLogs() {
        card("诊断日志", DiagnosticLog.recent(this, 80), 12, true);

        Button clearLog = dangerButton("清空诊断日志");
        clearLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DiagnosticLog.clear(MainActivity.this);
                Toast.makeText(MainActivity.this, "诊断日志已清空", Toast.LENGTH_SHORT).show();
                render();
            }
        });
        content.addView(clearLog, blockParams());
    }

    private void openReceivedFile(FileTransferStore.Record record) {
        if (record.uri == null || record.uri.length() == 0) {
            Toast.makeText(this, "文件地址为空", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(record.uri), "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "打开文件"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开该文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDownloadsFolder() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("content://com.android.externalstorage.documents/root/primary"));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "请在文件管理器中打开 Downloads/BHZN-ToDesk", Toast.LENGTH_LONG).show();
        }
    }

    private void permissionRow(String title, String detail, String action, View.OnClickListener listener) {
        LinearLayout box = cardBox();
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(23, 32, 42));
        titleView.setTextSize(17);
        titleView.setTypeface(null, 1);
        TextView detailView = new TextView(this);
        detailView.setText(detail);
        detailView.setTextColor(Color.rgb(92, 106, 122));
        detailView.setTextSize(14);
        detailView.setPadding(0, dp(6), 0, dp(12));
        Button button = primaryButton(action);
        button.setOnClickListener(listener);
        box.addView(titleView);
        box.addView(detailView);
        box.addView(button, new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(box, blockParams());
    }

    private void card(String title, String value, int valueSize, boolean mono) {
        LinearLayout box = cardBox();
        TextView titleView = smallLabel(title);
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(valueSize);
        valueView.setTextColor(Color.rgb(23, 32, 42));
        valueView.setPadding(0, dp(8), 0, 0);
        valueView.setGravity(Gravity.START);
        if (mono) {
            valueView.setLetterSpacing(.08f);
            valueView.setTypeface(android.graphics.Typeface.MONOSPACE, 1);
        }
        box.addView(titleView);
        box.addView(valueView);
        content.addView(box, blockParams());
    }

    private void copyCard(final String title, final String value, String buttonText) {
        LinearLayout box = cardBox();
        TextView titleView = smallLabel(title);
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(30);
        valueView.setTextColor(Color.rgb(23, 32, 42));
        valueView.setPadding(0, dp(8), 0, dp(10));
        valueView.setGravity(Gravity.START);
        valueView.setLetterSpacing(.08f);
        valueView.setTypeface(android.graphics.Typeface.MONOSPACE, 1);

        Button copy = primaryButton(buttonText);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(title, value);
            }
        });

        box.addView(titleView);
        box.addView(valueView);
        box.addView(copy, new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(box, blockParams());
    }

    private void copyText(String label, String value) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + "已复制", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackgroundColor(Color.WHITE);
        return box;
    }

    private TextView smallLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(Color.rgb(92, 106, 122));
        return label;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(22, 119, 255));
        return button;
    }

    private Button dangerButton(String text) {
        Button button = primaryButton(text);
        button.setBackgroundColor(Color.rgb(214, 69, 69));
        return button;
    }

    private LinearLayout.LayoutParams blockParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(12));
        return params;
    }

    private String yesNo(boolean value) {
        return value ? "已开" : "未开";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
    }

    private String formatTime(long time) {
        if (time <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(time));
    }

    private void requestMediaProjection() {
        DiagnosticLog.info(this, "MainActivity", "request media projection");
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            DiagnosticLog.info(this, "MainActivity", "MediaProjectionManager unavailable");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
}
