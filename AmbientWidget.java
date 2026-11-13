
package com.ledcar.ambient;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.RemoteViews;

public class AmbientWidget extends AppWidgetProvider {

    public static final String PREFS = "MainActivity";

    public static final String WIDGET_PREFS =
        "ambient_widget_prefs";

    private static final int[] PRESET_IDS = {
        R.id.preset_0,
        R.id.preset_1,
        R.id.preset_2,
        R.id.preset_3,
        R.id.preset_4,
        R.id.preset_5
    };

    @Override
    public void onUpdate(
        Context context,
        AppWidgetManager manager,
        int[] widgetIds
    ) {
        for (int id : widgetIds) {
            updateOne(context, manager, id);
        }
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager =
            AppWidgetManager.getInstance(context);

        ComponentName component =
            new ComponentName(
                context,
                AmbientWidget.class
            );

        int[] ids = manager.getAppWidgetIds(component);

        for (int id : ids) {
            updateOne(context, manager, id);
        }
    }

    private static void updateOne(
        Context context,
        AppWidgetManager manager,
        int widgetId
    ) {
        RemoteViews views = new RemoteViews(
            context.getPackageName(),
            R.layout.widget_ambient
        );

        SharedPreferences app =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            );

        SharedPreferences settings =
            context.getSharedPreferences(
                WIDGET_PREFS,
                Context.MODE_PRIVATE
            );

        int opacity = settings.getInt("opacity", 75);

        opacity = Math.max(
            0,
            Math.min(100, opacity)
        );

        boolean white = settings.getBoolean(
            "white_background",
            false
        );

        // 배경 불투명도와 색상 적용
        views.setImageViewBitmap(
            R.id.widget_background,
            createBackground(opacity, white)
        );

        views.setTextColor(
            R.id.widget_title,
            white ? Color.BLACK : Color.WHITE
        );

        // 연결 상태
        String status = settings.getString(
            "connection_status",
            "대기"
        );

        views.setTextViewText(
            R.id.widget_status,
            "● " + status
        );

        views.setTextColor(
            R.id.widget_status,
            "연결됨".equals(status)
                ? 0xFF14C7AD
                : (white ? 0xFF555B66 : 0xFFA7ABB5)
        );

        // 프리셋
        int selected = settings.getInt(
            "selected_preset",
            -1
        );

        for (int i = 0; i < 6; i++) {

            boolean saved = app.contains(
                "preset_" + i
            );

            int color = app.getInt(
                "preset_" + i,
                Color.WHITE
            );

            views.setImageViewBitmap(
                PRESET_IDS[i],
                createColorButton(
                    color,
                    selected == i,
                    saved,
                    white
                )
            );

            views.setOnClickPendingIntent(
                PRESET_IDS[i],
                commandIntent(
                    context,
                    "preset",
                    i,
                    100 + i
                )
            );
        }

        // 순차 점등
        views.setOnClickPendingIntent(
            R.id.widget_sequential,
            commandIntent(
                context,
                "sequential",
                -1,
                201
            )
        );

        // 무지개 순환
        views.setOnClickPendingIntent(
            R.id.widget_rainbow,
            commandIntent(
                context,
                "rainbow",
                -1,
                202
            )
        );

        // 전원
        boolean powerOn = settings.getBoolean(
            "power_on",
            true
        );

        views.setTextViewText(
            R.id.widget_power,
            powerOn ? "⏻ 전원" : "⏻ 꺼짐"
        );

        views.setOnClickPendingIntent(
            R.id.widget_power,
            commandIntent(
                context,
                "power",
                -1,
                203
            )
        );

        // 제목 터치 시 위젯 설정 열기
        Intent settingsIntent = new Intent(
            context,
            WidgetSettingsActivity.class
        );

        PendingIntent settingsPending =
            PendingIntent.getActivity(
                context,
                300,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        views.setOnClickPendingIntent(
            R.id.widget_title,
            settingsPending
        );

        manager.updateAppWidget(
            widgetId,
            views
        );
    }

    private static PendingIntent commandIntent(
        Context context,
        String command,
        int index,
        int requestCode
    ) {
        Intent intent = new Intent(
            context,
            WidgetBleService.class
        );

        intent.setAction(
            "com.ledcar.ambient.WIDGET_COMMAND"
        );

        intent.putExtra("command", command);
        intent.putExtra("index", index);

        return PendingIntent.getForegroundService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT |
            PendingIntent.FLAG_IMMUTABLE
        );
    }

    // 둥근 모서리 배경
    private static Bitmap createBackground(
        int opacity,
        boolean white
    ) {
        int width = 600;
        int height = 280;

        Bitmap bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(
            Paint.ANTI_ALIAS_FLAG
        );

        int alpha = Math.round(
            opacity * 255f / 100f
        );

        int backgroundColor = white
            ? Color.argb(
                alpha,
                255,
                255,
                255
            )
            : Color.argb(
                alpha,
                23,
                25,
                31
            );

        paint.setColor(backgroundColor);

        canvas.drawRoundRect(
            new RectF(
                0,
                0,
                width,
                height
            ),
            32,
            32,
            paint
        );

        return bitmap;
    }

    // 프리셋 원형 색상 버튼
    private static Bitmap createColorButton(
        int color,
        boolean selected,
        boolean saved,
        boolean white
    ) {
        Bitmap bitmap = Bitmap.createBitmap(
            112,
            112,
            Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(
            Paint.ANTI_ALIAS_FLAG
        );

        // 버튼 배경
        paint.setColor(
            white ? 0xFFDCE0E6 : 0xFF292D35
        );

        canvas.drawRoundRect(
            new RectF(
                2,
                2,
                110,
                110
            ),
            24,
            24,
            paint
        );

        if (!saved) {
            // 저장되지 않은 프리셋
            paint.setColor(
                white ? 0xFF8A909A : 0xFF777D88
            );

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);

            canvas.drawCircle(
                56,
                56,
                35,
                paint
            );

            paint.setStyle(Paint.Style.FILL);

            return bitmap;
        }

        // 선택된 프리셋 테두리
        if (selected) {
            paint.setColor(
                white ? Color.BLACK : Color.WHITE
            );

            canvas.drawCircle(
                56,
                56,
                42,
                paint
            );
        }

        paint.setColor(color);

        canvas.drawCircle(
            56,
            56,
            selected ? 35 : 38,
            paint
        );

        return bitmap;
    }
}
