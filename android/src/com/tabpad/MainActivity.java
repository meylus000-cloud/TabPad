package com.tabpad;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.util.List;

public class MainActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // Windows virtual-key codes
    private static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_ENTER = 0x0D, VK_SHIFT = 0x10, VK_CTRL = 0x11,
            VK_ALT = 0x12, VK_ESC = 0x1B, VK_SPACE = 0x20, VK_PGUP = 0x21, VK_PGDN = 0x22, VK_END = 0x23,
            VK_HOME = 0x24, VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_PRTSC = 0x2C,
            VK_DEL = 0x2E, VK_WIN = 0x5B, VK_F4 = 0x73, VK_F5 = 0x74, VK_F11 = 0x7A, VK_MUTE = 0xAD,
            VK_VOLDN = 0xAE, VK_VOLUP = 0xAF, VK_PLAY = 0xB3;
    private static final int[] MODS = {VK_CTRL, VK_ALT, VK_SHIFT, VK_WIN};
    private static final String[] MOD_NAMES = {"Ctrl", "Alt", "Shift", "Win"};

    private final Net net = new Net();
    private SharedPreferences prefs;
    private PadView pad;
    private TextView status;
    private EditText typer;
    private ScrollView overlay;
    private EditText ipField, pinField;
    private SeekBar sensBar;
    private CheckBox invertBox;
    private TextView overlayMsg;
    private final ToggleButton[] modBtns = new ToggleButton[4];
    private boolean resetting = false;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("tabpad", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF121212);
        root.addView(buildMain(), new FrameLayout.LayoutParams(MATCH, MATCH));
        buildTyper();
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(2), dp(2));
        tlp.gravity = Gravity.TOP | Gravity.LEFT;
        root.addView(typer, tlp);
        buildOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);

        net.setListener(new Net.Listener() {
            public void onState(final boolean ok, final String msg) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        onNetState(ok, msg);
                    }
                });
            }
        });
        pad.setNet(net);

        applySettings();
        if (prefs.getString("ip", "").length() > 0 && prefs.getString("pin", "").length() > 0) {
            connect();
        }
    }

    @Override
    protected void onDestroy() {
        net.disconnect();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE && net.isConnected()) {
            overlay.setVisibility(View.GONE);
        } else if (overlay.getVisibility() != View.VISIBLE) {
            overlay.setVisibility(View.VISIBLE);
        } else {
            finish();
        }
    }

    // ---------- UI ----------

    private Button btn(String text, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(text);
        x.setOnClickListener(l);
        x.setPadding(dp(4), 0, dp(4), 0);
        return x;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, MATCH, w);
    }

    private View buildMain() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), dp(6), dp(8), dp(8));

        // верхня панель
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFFB0BEC5);
        status.setText("○ Не підключено");
        top.addView(status, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(btn("⚙ Підключення", new View.OnClickListener() {
            public void onClick(View v) {
                overlay.setVisibility(View.VISIBLE);
            }
        }), new LinearLayout.LayoutParams(WRAP, dp(44)));
        top.addView(btn("⌨ Клавіатура", new View.OnClickListener() {
            public void onClick(View v) {
                toggleKeyboard();
            }
        }), new LinearLayout.LayoutParams(WRAP, dp(44)));
        col.addView(top, new LinearLayout.LayoutParams(MATCH, dp(48)));

        // середина: тачпад + клавіші
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        pad = new PadView(this);
        left.addView(pad, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(holdButton("Ліва", 1), weight(2f));
        buttons.addView(holdButton("Середня", 3), weight(1f));
        buttons.addView(holdButton("Права", 2), weight(2f));
        left.addView(buttons, new LinearLayout.LayoutParams(MATCH, dp(64)));
        mid.addView(left, new LinearLayout.LayoutParams(0, MATCH, 1f));

        ScrollView sv = new ScrollView(this);
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        keys.addView(row(k("Esc", VK_ESC), k("Tab", VK_TAB), k("⌫", VK_BACK), k("Del", VK_DEL)));
        keys.addView(row(mod(0), mod(1), mod(2), mod(3)));
        keys.addView(row(k("←", VK_LEFT), k("↑", VK_UP), k("↓", VK_DOWN), k("→", VK_RIGHT)));
        keys.addView(row(k("Home", VK_HOME), k("End", VK_END), k("PgUp", VK_PGUP), k("PgDn", VK_PGDN)));
        keys.addView(row(k("Alt+Tab", VK_ALT, VK_TAB), k("Win+D", VK_WIN, 0x44), k("Alt+F4", VK_ALT, VK_F4),
                k("Мова", VK_WIN, VK_SPACE)));
        keys.addView(row(k("Копіюв.", VK_CTRL, 0x43), k("Вставити", VK_CTRL, 0x56), k("Вирізати", VK_CTRL, 0x58),
                k("Скасув.", VK_CTRL, 0x5A)));
        keys.addView(row(k("Ctrl+A", VK_CTRL, 0x41), k("Ctrl+S", VK_CTRL, 0x53), k("F5", VK_F5),
                k("Enter", VK_ENTER)));
        keys.addView(row(k("Гуч.−", VK_VOLDN), k("Гуч.+", VK_VOLUP), k("Mute", VK_MUTE), k("▶❚❚", VK_PLAY)));
        keys.addView(row(k("Win", VK_WIN), k("Диспетч.", VK_CTRL, VK_SHIFT, VK_ESC), k("PrtSc", VK_PRTSC),
                k("F11", VK_F11)));
        sv.addView(keys, new ScrollView.LayoutParams(MATCH, WRAP));
        mid.addView(sv, new LinearLayout.LayoutParams(dp(330), MATCH));

        col.addView(mid, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return col;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            r.addView(views[i], new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        return r;
    }

    private Button k(String label, final int... vks) {
        return btn(label, new View.OnClickListener() {
            public void onClick(View v) {
                net.combo(vks);
                releaseSticky();
            }
        });
    }

    private ToggleButton mod(final int idx) {
        final ToggleButton t = new ToggleButton(this);
        t.setTextOn(MOD_NAMES[idx]);
        t.setTextOff(MOD_NAMES[idx]);
        t.setText(MOD_NAMES[idx]);
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean on) {
                net.key(MODS[idx], on);
            }
        });
        modBtns[idx] = t;
        return t;
    }

    private void releaseSticky() {
        for (int i = 0; i < modBtns.length; i++) {
            if (modBtns[i] != null && modBtns[i].isChecked()) modBtns[i].setChecked(false);
        }
    }

    private Button holdButton(String label, final int which) {
        Button x = new Button(this);
        x.setText(label);
        x.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) net.button(which, true);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) net.button(which, false);
                return false;
            }
        });
        return x;
    }

    // ---------- введення тексту ----------

    private void buildTyper() {
        typer = new EditText(this);
        typer.setBackgroundColor(0x00000000);
        typer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        typer.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE);
        typer.setText(" ");
        typer.setSelection(1);
        typer.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable ed) {
                if (resetting) return;
                String t = ed.toString();
                if (t.length() == 0) {
                    typeKey(VK_BACK);
                } else if (t.length() > 1) {
                    String added = t.startsWith(" ") ? t.substring(1) : t.substring(0, t.length() - 1);
                    for (int i = 0; i < added.length(); i++) typeChar(added.charAt(i));
                } else if (!t.equals(" ")) {
                    typeChar(t.charAt(0));
                }
                resetting = true;
                ed.replace(0, ed.length(), " ");
                Selection.setSelection(ed, ed.length());
                resetting = false;
            }
        });
    }

    private void typeKey(int vk) {
        net.combo(vk);
        releaseSticky();
    }

    private void typeChar(char c) {
        if (c == '\n') net.combo(VK_ENTER);
        else if (c == '\t') net.combo(VK_TAB);
        else net.ch(c);
        releaseSticky();
    }

    private void toggleKeyboard() {
        typer.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    // ---------- екран підключення ----------

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFCFD8DC);
        v.setTextSize(15);
        v.setPadding(0, dp(10), 0, dp(2));
        return v;
    }

    private void buildOverlay() {
        overlay = new ScrollView(this);
        overlay.setBackgroundColor(0xF0121212);
        overlay.setFillViewport(true);
        overlay.setClickable(true); // щоб дотики не «протікали» на тачпад під ним

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(40), dp(16), dp(40), dp(16));

        TextView title = new TextView(this);
        title.setText("TabPad — планшет як мишка і клавіатура");
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        box.addView(title);

        TextView help = new TextView(this);
        help.setTextColor(0xFF90A4AE);
        help.setTextSize(13);
        help.setText("Кабель: з'єднайте планшет із ноутбуком USB-кабелем і ввімкніть на планшеті «USB-модем» "
                + "(Налаштування → Бездротові мережі → Ще → Модем). Потім натисніть «Знайти ноутбук». "
                + "Можна і через Wi-Fi в одній мережі. На ноутбуці має бути запущений TabPad.exe.");
        help.setPadding(0, dp(6), 0, dp(6));
        box.addView(help);

        box.addView(label("IP-адреса ноутбука"));
        ipField = new EditText(this);
        ipField.setSingleLine(true);
        ipField.setText(prefs.getString("ip", ""));
        ipField.setHint("наприклад 192.168.42.100");
        box.addView(ipField, new LinearLayout.LayoutParams(dp(420), WRAP));

        box.addView(label("PIN (показаний у програмі на ноутбуці)"));
        pinField = new EditText(this);
        pinField.setSingleLine(true);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinField.setText(prefs.getString("pin", ""));
        box.addView(pinField, new LinearLayout.LayoutParams(dp(420), WRAP));

        box.addView(label("Швидкість курсора"));
        sensBar = new SeekBar(this);
        sensBar.setMax(100);
        sensBar.setProgress(prefs.getInt("sens", 40));
        box.addView(sensBar, new LinearLayout.LayoutParams(dp(420), WRAP));

        invertBox = new CheckBox(this);
        invertBox.setText("Інвертувати прокрутку");
        invertBox.setChecked(prefs.getBoolean("inv", false));
        box.addView(invertBox);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(10), 0, dp(6));
        btns.addView(btn("Знайти ноутбук", new View.OnClickListener() {
            public void onClick(View v) {
                discover();
            }
        }), new LinearLayout.LayoutParams(dp(205), dp(52)));
        btns.addView(btn("З'єднатися", new View.OnClickListener() {
            public void onClick(View v) {
                connect();
            }
        }), new LinearLayout.LayoutParams(dp(205), dp(52)));
        box.addView(btns);

        overlayMsg = new TextView(this);
        overlayMsg.setTextSize(15);
        overlayMsg.setTextColor(0xFFFFCA28);
        overlayMsg.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(overlayMsg);

        overlay.addView(box, new ScrollView.LayoutParams(MATCH, WRAP));
    }

    private void applySettings() {
        pad.sensitivity = 0.5f + sensBar.getProgress() / 100f * 2.5f;
        pad.invertScroll = invertBox.isChecked();
    }

    private void connect() {
        String ip = ipField.getText().toString().trim();
        String pin = pinField.getText().toString().trim();
        if (ip.length() == 0 || pin.length() == 0) {
            overlay.setVisibility(View.VISIBLE);
            overlayMsg.setText("Введіть IP та PIN (або натисніть «Знайти ноутбук»).");
            return;
        }
        prefs.edit().putString("ip", ip).putString("pin", pin).putInt("sens", sensBar.getProgress())
                .putBoolean("inv", invertBox.isChecked()).commit();
        applySettings();
        overlayMsg.setText("З'єднання…");
        net.connect(ip, Net.PORT, pin);
    }

    private void discover() {
        overlayMsg.setText("Пошук ноутбука…");
        Net.discover(new Net.DiscoverCb() {
            public void onResult(final List<String> found) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (found.isEmpty()) {
                            overlayMsg.setText("Не знайдено. Перевірте кабель/USB-модем і брандмауер "
                                    + "Windows, або введіть IP вручну.");
                        } else {
                            String first = found.get(0);
                            int sp = first.indexOf(' ');
                            ipField.setText(sp > 0 ? first.substring(0, sp) : first);
                            overlayMsg.setText("Знайдено: " + first + (found.size() > 1 ? " (є ще варіанти)" : ""));
                        }
                    }
                });
            }
        });
    }

    private void onNetState(boolean ok, String msg) {
        if (ok) {
            status.setText("● Підключено: " + ipField.getText().toString().trim());
            status.setTextColor(0xFF81C784);
            overlayMsg.setText("");
            overlay.setVisibility(View.GONE);
        } else {
            status.setText("○ Не підключено");
            status.setTextColor(0xFFB0BEC5);
            for (int i = 0; i < modBtns.length; i++) {
                if (modBtns[i] != null) modBtns[i].setChecked(false);
            }
            overlayMsg.setText(msg);
            overlay.setVisibility(View.VISIBLE);
        }
    }
}
