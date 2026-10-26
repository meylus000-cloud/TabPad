// TabPad для Windows: приймає команди від планшета і емулює мишу/клавіатуру.
// Один файл, без залежностей. Компілюється вбудованим у Windows csc.exe (C# 5).
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace TabPad
{
    static class Native
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Explicit)]
        public struct INPUTUNION
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct INPUT
        {
            public uint type;
            public INPUTUNION u;
        }

        [DllImport("user32.dll", SetLastError = true)]
        public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        public static extern uint MapVirtualKey(uint uCode, uint uMapType);
    }

    static class Inject
    {
        const uint KEYEVENTF_EXTENDEDKEY = 0x1;
        const uint KEYEVENTF_KEYUP = 0x2;
        const uint KEYEVENTF_UNICODE = 0x4;
        static readonly object sync = new object();

        static void Send(Native.INPUT i)
        {
            Native.INPUT[] a = new Native.INPUT[] { i };
            lock (sync)
            {
                Native.SendInput(1, a, Marshal.SizeOf(typeof(Native.INPUT)));
            }
        }

        static void Mouse(uint flags, int dx, int dy, uint data)
        {
            Native.INPUT i = new Native.INPUT();
            i.type = 0;
            i.u.mi.dx = dx;
            i.u.mi.dy = dy;
            i.u.mi.mouseData = data;
            i.u.mi.dwFlags = flags;
            Send(i);
        }

        public static void Move(int dx, int dy)
        {
            Mouse(0x0001, dx, dy, 0);
        }

        public static void Button(int b, bool down)
        {
            uint f;
            if (b == 1) f = down ? 0x0002u : 0x0004u;
            else if (b == 2) f = down ? 0x0008u : 0x0010u;
            else f = down ? 0x0020u : 0x0040u;
            Mouse(f, 0, 0, 0);
        }

        public static void Wheel(int delta)
        {
            Mouse(0x0800, 0, 0, unchecked((uint)delta));
        }

        static bool IsExtended(int vk)
        {
            return (vk >= 0x21 && vk <= 0x28) || vk == 0x2D || vk == 0x2E || vk == 0x5B || vk == 0x5C;
        }

        public static void Key(int vk, bool down)
        {
            Native.INPUT i = new Native.INPUT();
            i.type = 1;
            i.u.ki.wVk = (ushort)vk;
            i.u.ki.wScan = (ushort)Native.MapVirtualKey((uint)vk, 0);
            uint f = 0;
            if (IsExtended(vk)) f |= KEYEVENTF_EXTENDEDKEY;
            if (!down) f |= KEYEVENTF_KEYUP;
            i.u.ki.dwFlags = f;
            Send(i);
        }

        // Символ: звичайно як Unicode (працює з українською), а при затиснутому
        // Ctrl/Alt/Win — як справжня клавіша, щоб працювали гарячі клавіші.
        public static void Char(int c, bool modifierHeld)
        {
            if (modifierHeld)
            {
                int vk = -1;
                if (c >= 'a' && c <= 'z') vk = c - 32;
                else if (c >= 'A' && c <= 'Z') vk = c;
                else if (c >= '0' && c <= '9') vk = c;
                if (vk > 0)
                {
                    Key(vk, true);
                    Key(vk, false);
                    return;
                }
            }
            for (int n = 0; n < 2; n++)
            {
                Native.INPUT i = new Native.INPUT();
                i.type = 1;
                i.u.ki.wVk = 0;
                i.u.ki.wScan = (ushort)c;
                i.u.ki.dwFlags = KEYEVENTF_UNICODE | (n == 1 ? KEYEVENTF_KEYUP : 0u);
                Send(i);
            }
        }
    }

    class Server
    {
        public const int Port = 5555;
        public const int DiscPort = 5556;
        public string Pin;
        public event Action<string> StatusChanged;

        readonly object sync = new object();
        TcpClient current;

        public void Start()
        {
            Thread t = new Thread(TcpLoop);
            t.IsBackground = true;
            t.Start();
            Thread u = new Thread(UdpLoop);
            u.IsBackground = true;
            u.Start();
        }

        void SetStatus(string s)
        {
            Action<string> h = StatusChanged;
            if (h != null) h(s);
        }

        void UdpLoop()
        {
            try
            {
                UdpClient udp = new UdpClient(new IPEndPoint(IPAddress.Any, DiscPort));
                while (true)
                {
                    IPEndPoint from = new IPEndPoint(IPAddress.Any, 0);
                    byte[] data = udp.Receive(ref from);
                    string s = Encoding.UTF8.GetString(data);
                    if (s == "TABPAD?")
                    {
                        byte[] reply = Encoding.UTF8.GetBytes("TABPAD " + Environment.MachineName);
                        udp.Send(reply, reply.Length, from);
                    }
                }
            }
            catch (Exception ex)
            {
                SetStatus("Помилка пошуку (UDP " + DiscPort + "): " + ex.Message);
            }
        }

        void TcpLoop()
        {
            TcpListener l;
            try
            {
                l = new TcpListener(IPAddress.Any, Port);
                l.Start();
            }
            catch (Exception ex)
            {
                SetStatus("Не вдалося відкрити порт " + Port + ": " + ex.Message);
                return;
            }
            SetStatus("Очікую підключення планшета…");
            while (true)
            {
                try
                {
                    TcpClient c = l.AcceptTcpClient();
                    lock (sync)
                    {
                        if (current != null)
                        {
                            try { current.Close(); } catch (Exception) { }
                        }
                        current = c;
                    }
                    Thread t = new Thread(delegate() { Handle(c); });
                    t.IsBackground = true;
                    t.Start();
                }
                catch (Exception)
                {
                    Thread.Sleep(200);
                }
            }
        }

        void Handle(TcpClient c)
        {
            HashSet<int> keys = new HashSet<int>();
            bool[] btn = new bool[4];
            bool authed = false;
            string who = "?";
            try
            {
                who = ((IPEndPoint)c.Client.RemoteEndPoint).Address.ToString();
                c.NoDelay = true;
                c.ReceiveTimeout = 6000;
                NetworkStream ns = c.GetStream();
                StreamReader rd = new StreamReader(ns, new UTF8Encoding(false));
                StreamWriter wr = new StreamWriter(ns, new UTF8Encoding(false));
                wr.NewLine = "\n";
                wr.AutoFlush = true;

                string hello = rd.ReadLine();
                if (hello == null || hello != "HELLO " + Pin)
                {
                    Thread.Sleep(1500);
                    wr.WriteLine("NO");
                    return;
                }
                wr.WriteLine("OK");
                authed = true;
                SetStatus("● Підключено: " + who);

                string line;
                while ((line = rd.ReadLine()) != null)
                {
                    try
                    {
                        Process(line, keys, btn);
                    }
                    catch (FormatException) { }
                    catch (IndexOutOfRangeException) { }
                }
            }
            catch (Exception)
            {
            }
            finally
            {
                // відпускаємо все, що могло залишитися затиснутим
                foreach (int vk in keys) Inject.Key(vk, false);
                for (int b = 1; b <= 3; b++) if (btn[b]) Inject.Button(b, false);
                try { c.Close(); } catch (Exception) { }
                bool last;
                lock (sync)
                {
                    last = (current == c);
                    if (last) current = null;
                }
                if (authed && last) SetStatus("Очікую підключення планшета…");
            }
        }

        static void Process(string line, HashSet<int> keys, bool[] btn)
        {
            string[] p = line.Split(' ');
            switch (p[0])
            {
                case "M":
                    Inject.Move(int.Parse(p[1]), int.Parse(p[2]));
                    break;
                case "S":
                    Inject.Wheel(int.Parse(p[1]));
                    break;
                case "B":
                    {
                        int b = int.Parse(p[1]);
                        bool d = p[2] == "1";
                        if (b < 1 || b > 3) break;
                        Inject.Button(b, d);
                        btn[b] = d;
                        break;
                    }
                case "L":
                    {
                        int b = int.Parse(p[1]);
                        if (b < 1 || b > 3) break;
                        Inject.Button(b, true);
                        Inject.Button(b, false);
                        break;
                    }
                case "K":
                    {
                        int vk = int.Parse(p[1]);
                        bool d = p[2] == "1";
                        Inject.Key(vk, d);
                        if (d) keys.Add(vk); else keys.Remove(vk);
                        break;
                    }
                case "X":
                    {
                        string[] parts = p[1].Split(',');
                        List<int> vks = new List<int>();
                        foreach (string s in parts) vks.Add(int.Parse(s));
                        foreach (int vk in vks) Inject.Key(vk, true);
                        for (int i = vks.Count - 1; i >= 0; i--) Inject.Key(vks[i], false);
                        break;
                    }
                case "U":
                    {
                        bool mod = keys.Contains(0x11) || keys.Contains(0x12) || keys.Contains(0x5B);
                        Inject.Char(int.Parse(p[1]), mod);
                        break;
                    }
                default: // "P" — heartbeat, та все невідоме ігноруємо
                    break;
            }
        }
    }

    class MainForm : Form
    {
        readonly Server server;
        readonly Label lblIps;
        readonly Label lblStatus;
        readonly NotifyIcon tray;
        bool reallyExit;
        bool startHidden;
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public MainForm(Server s, bool hidden)
        {
            server = s;
            startHidden = hidden;
            Text = "TabPad";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(400, 250);
            Font = SystemFonts.MessageBoxFont;
            Icon = SystemIcons.Application;

            Label l1 = new Label();
            l1.Text = "IP-адреси цього ноутбука (порт " + Server.Port + "):";
            l1.SetBounds(12, 10, 376, 18);
            Controls.Add(l1);

            lblIps = new Label();
            lblIps.SetBounds(12, 30, 376, 62);
            Controls.Add(lblIps);

            Label l2 = new Label();
            l2.Text = "PIN для планшета:";
            l2.SetBounds(12, 98, 150, 20);
            Controls.Add(l2);

            Label lblPin = new Label();
            lblPin.Text = server.Pin;
            lblPin.Font = new Font(Font.FontFamily, 18f, FontStyle.Bold);
            lblPin.SetBounds(160, 90, 200, 34);
            Controls.Add(lblPin);

            lblStatus = new Label();
            lblStatus.Text = "Запуск…";
            lblStatus.SetBounds(12, 134, 376, 36);
            Controls.Add(lblStatus);

            CheckBox auto = new CheckBox();
            auto.Text = "Запускати разом з Windows (у треї)";
            auto.SetBounds(12, 176, 376, 22);
            auto.Checked = IsAutostart();
            auto.CheckedChanged += delegate(object o, EventArgs e) { SetAutostart(auto.Checked); };
            Controls.Add(auto);

            Button hide = new Button();
            hide.Text = "Сховати в трей";
            hide.SetBounds(12, 208, 150, 30);
            hide.Click += delegate(object o, EventArgs e) { Hide(); };
            Controls.Add(hide);

            Button quit = new Button();
            quit.Text = "Вийти";
            quit.SetBounds(238, 208, 150, 30);
            quit.Click += delegate(object o, EventArgs e) { ExitApp(); };
            Controls.Add(quit);

            tray = new NotifyIcon();
            tray.Icon = SystemIcons.Application;
            tray.Text = "TabPad";
            tray.Visible = true;
            tray.DoubleClick += delegate(object o, EventArgs e) { ShowMe(); };
            ContextMenu menu = new ContextMenu();
            menu.MenuItems.Add("Відкрити", delegate(object o, EventArgs e) { ShowMe(); });
            menu.MenuItems.Add("Вийти", delegate(object o, EventArgs e) { ExitApp(); });
            tray.ContextMenu = menu;

            IntPtr h = Handle; // примусово створюємо handle, щоб працював BeginInvoke
            server.StatusChanged += delegate(string st)
            {
                try
                {
                    BeginInvoke(new Action(delegate() { lblStatus.Text = st; }));
                }
                catch (Exception) { }
            };
            NetworkChange.NetworkAddressChanged += delegate(object o, EventArgs e)
            {
                try
                {
                    BeginInvoke(new Action(RefreshIps));
                }
                catch (Exception) { }
            };
            RefreshIps();
        }

        void ShowMe()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        void ExitApp()
        {
            reallyExit = true;
            tray.Visible = false;
            Application.Exit();
        }

        protected override void SetVisibleCore(bool value)
        {
            if (startHidden)
            {
                startHidden = false;
                base.SetVisibleCore(false);
                return;
            }
            base.SetVisibleCore(value);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!reallyExit && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
            }
            base.OnFormClosing(e);
        }

        void RefreshIps()
        {
            List<string> lines = new List<string>();
            foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (UnicastIPAddressInformation ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily == AddressFamily.InterNetwork)
                        lines.Add(ua.Address.ToString() + "   (" + ni.Name + ")");
                }
            }
            lblIps.Text = lines.Count == 0 ? "Немає активних мереж" : string.Join("\n", lines.ToArray());
        }

        static bool IsAutostart()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey))
                {
                    return k != null && k.GetValue("TabPad") != null;
                }
            }
            catch (Exception) { return false; }
        }

        static void SetAutostart(bool on)
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
                {
                    if (k == null) return;
                    if (on) k.SetValue("TabPad", "\"" + Application.ExecutablePath + "\" --tray");
                    else k.DeleteValue("TabPad", false);
                }
            }
            catch (Exception) { }
        }
    }

    static class Program
    {
        static string LoadPin()
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TabPad");
            string file = Path.Combine(dir, "pin.txt");
            try
            {
                if (File.Exists(file))
                {
                    string p = File.ReadAllText(file).Trim();
                    if (p.Length >= 4) return p;
                }
            }
            catch (Exception) { }
            byte[] b = new byte[4];
            using (RNGCryptoServiceProvider rng = new RNGCryptoServiceProvider())
            {
                rng.GetBytes(b);
            }
            uint n = BitConverter.ToUInt32(b, 0) % 1000000u;
            string pin = n.ToString("D6");
            try
            {
                Directory.CreateDirectory(dir);
                File.WriteAllText(file, pin);
            }
            catch (Exception) { }
            return pin;
        }

        [STAThread]
        static void Main(string[] args)
        {
            bool createdNew;
            using (Mutex m = new Mutex(true, "TabPad_SingleInstance", out createdNew))
            {
                if (!createdNew)
                {
                    MessageBox.Show("TabPad вже запущено (шукайте значок у треї).", "TabPad");
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Server server = new Server();
                server.Pin = LoadPin();
                bool hidden = Array.IndexOf(args, "--tray") >= 0;
                MainForm form = new MainForm(server, hidden);
                server.Start();
                Application.Run(form);
            }
        }
    }
}
