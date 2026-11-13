
package com.ledcar.ambient;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class WidgetSettingsActivity extends Activity {

    private SharedPreferences prefs;
    private TextView opacityValue;
    private int opacity;
    private boolean whiteBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(
                AmbientWidget.WIDGET_PREFS,
                MODE_PRIVATE
        );

        opacity = prefs.getInt("opacity", 75);

        whiteBackground = prefs.getBoolean(
                "white_background",
                false
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                dp(24),
                dp(35),
                dp(24),
                dp(24)
        );
        root.setBackgroundColor(0xFF17191F);

        TextView title = new TextView(this);
        title.setText("위젯 설정");
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);

        root.addView(title);

        TextView description = new TextView(this);
        description.setText(
                "홈 화면 위젯의 배경 투명도와 색상을 설정합니다."
        );
        description.setTextSize(14);
        description.setTextColor(0xFFA7ABB5);
        description.setPadding(
                0, dp(12), 0, dp(32)
        );

        root.addView(description);

        TextView opacityTitle = new TextView(this);
        opacityTitle.setText("배경 불투명도");
        opacityTitle.setTextSize(18);
        opacityTitle.setTextColor(Color.WHITE);

        root.addView(opacityTitle);

        opacityValue = new TextView(this);
        opacityValue.setText(opacity + "%");
        opacityValue.setTextSize(18);
        opacityValue.setTextColor(0xFF14C7AD);
        opacityValue.setGravity(Gravity.END);
        opacityValue.setPadding(
                0, dp(12), 0, dp(8)
        );

        root.addView(opacityValue);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(20);
        seekBar.setProgress(opacity / 5);

        seekBar.setProgressTintList(
                ColorStateList.valueOf(0xFF14C7AD)
        );

        seekBar.setThumbTintList(
                ColorStateList.valueOf(0xFF14C7AD)
        );

        root.addView(seekBar);

        TextView hint = new TextView(this);
        hint.setText(
                "0% 완전 투명                 100% 불투명"
        );
        hint.setTextSize(12);
        hint.setTextColor(0xFFA7ABB5);
        hint.setPadding(
                0, dp(8), 0, dp(30)
        );

        root.addView(hint);

        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        if (!fromUser) return;

                        opacity = progress * 5;
                        opacityValue.setText(opacity + "%");
                        saveSettings();
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }
                }
        );

        TextView colorTitle = new TextView(this);
        colorTitle.setText("배경 색상");
        colorTitle.setTextSize(18);
        colorTitle.setTextColor(Color.WHITE);
        colorTitle.setPadding(
                0, 0, 0, dp(12)
        );

        root.addView(colorTitle);

        RadioGroup colorGroup = new RadioGroup(this);
        colorGroup.setOrientation(RadioGroup.VERTICAL);

        RadioButton blackButton = new RadioButton(this);
        blackButton.setId(401);
        blackButton.setText("블랙");
        blackButton.setTextColor(Color.WHITE);
        blackButton.setButtonTintList(
                ColorStateList.valueOf(0xFF14C7AD)
        );

        RadioButton whiteButton = new RadioButton(this);
        whiteButton.setId(402);
        whiteButton.setText("화이트");
        whiteButton.setTextColor(Color.WHITE);
        whiteButton.setButtonTintList(
                ColorStateList.valueOf(0xFF14C7AD)
        );

        colorGroup.addView(blackButton);
        colorGroup.addView(whiteButton);

        colorGroup.check(
                whiteBackground ? 402 : 401
        );

        colorGroup.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    whiteBackground = checkedId == 402;
                    saveSettings();
                }
        );

        root.addView(colorGroup);

        Button saveButton = new Button(this);
        saveButton.setText("설정 완료");
        saveButton.setAllCaps(false);
        saveButton.setTextColor(Color.WHITE);
        saveButton.setBackgroundTintList(
                ColorStateList.valueOf(0xFF0C9F8B)
        );

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                );

        buttonParams.topMargin = dp(30);

        root.addView(saveButton, buttonParams);

        saveButton.setOnClickListener(v -> {
            saveSettings();
            finish();
        });

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(root);

        setContentView(scrollView);
    }

    private void saveSettings() {

        prefs.edit()
                .putInt("opacity", opacity)
                .putBoolean(
                        "white_background",
                        whiteBackground
                )
                .apply();

        AmbientWidget.updateAll(this);
    }

    private int dp(int value) {
        return Math.round(
                value * getResources()
                        .getDisplayMetrics().density
        );
    }
}

