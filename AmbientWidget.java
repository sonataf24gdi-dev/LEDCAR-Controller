
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

    private static final int[] DEFAULT_COLORS = {
            0xFFFF3434,
            0xFFFF9B28,
            0xFFFFE144,
            0xFF36D66A,
            0xFF308BFF,
            0xFFAE51E9
    };

    @Override
    public void onUpdate(
            Context context,
            AppWidgetManager manager,
            int[] ids
    ) {
        for (int id : ids) {
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

        int[] ids =
                manager.getAppWidgetIds(component);

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

        int opacity =
                settings.getInt("opacity", 75);

        boolean white =
                settings.getBoolean(
                        "white_background",
                        false
                );

        views.setInt(
                R.id.widget_title,
                "setTextColor",
                white ? Color.BLACK : Color.WHITE
        );

        // 현재 레이아웃의 루트 배경 투명도 조절
        // 실제 배경 변경은 다음 단계에서
        // widget_background 뷰를 추가하여 연결

        String status =
                settings.getString(
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
                        : 0xFFA7ABB5
        );

        int selected =
                settings.getInt(
                        "selected_preset",
                        -1
                );

        for (int i = 0; i < 6; i++) {

            int color = app.getInt(
                    "preset_" + i,
                    DEFAULT_COLORS[i]
            );

            views.setImageViewBitmap(
                    PRESET_IDS[i],
                    createColorButton(
                            color,
                            selected == i
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

        views.setOnClickPendingIntent(
                R.id.widget_sequential,
                commandIntent(
                        context,
                        "sequential",
                        -1,
                        201
                )
        );

        views.setOnClickPendingIntent(
                R.id.widget_rainbow,
                commandIntent(
                        context,
                        "rainbow",
                        -1,
                        202
                )
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

        intent.putExtra(
                "command",
                command
        );

        intent.putExtra(
                "index",
                index
        );

        return PendingIntent.getForegroundService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                        PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static Bitmap createColorButton(
            int color,
            boolean selected
    ) {

        Bitmap bitmap =
                Bitmap.createBitmap(
                        112,
                        112,
                        Bitmap.Config.ARGB_8888
                );

        Canvas canvas = new Canvas(bitmap);

        Paint paint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        paint.setColor(0xFF292D35);

        canvas.drawRoundRect(
                new RectF(2, 2, 110, 110),
                24,
                24,
                paint
        );

        if (selected) {
            paint.setColor(Color.WHITE);

            canvas.drawCircle(
                    56,
                    56,
                    41,
                    paint
            );
        }

        paint.setColor(color);

        canvas.drawCircle(
                56,
                56,
                selected ? 34 : 38,
                paint
        );

        return bitmap;
    }
}

