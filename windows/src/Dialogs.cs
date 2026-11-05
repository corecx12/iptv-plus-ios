namespace IPTVPlus;

public class DetailDialog : Form
{
    readonly Label _plot;
    public DetailDialog(string title, string plot, Action onPlay)
    {
        Text = title;
        BackColor = ColorTranslator.FromHtml("#16191E");
        ForeColor = ColorTranslator.FromHtml("#E8EAEE");
        Width = 560; Height = 420; StartPosition = FormStartPosition.CenterParent;
        var play = new Button { Text = "▶ Abspielen", Dock = DockStyle.Top, Height = 40, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = ColorTranslator.FromHtml("#3D8BFD") };
        play.FlatAppearance.BorderSize = 0;
        play.Click += (_, _) => { onPlay(); Close(); };
        _plot = new Label { Text = plot, Dock = DockStyle.Fill, Padding = new Padding(14), AutoEllipsis = true };
        Controls.Add(_plot);
        Controls.Add(play);
    }
    public void SetText(string t) => _plot.Text = t;
}

public class AccountDialog : Form
{
    readonly TextBox _label, _server, _user, _pass;
    public string AccLabel => _label.Text;
    public string AccServer => _server.Text;
    public string AccUser => _user.Text;
    public string AccPass => _pass.Text;

    public AccountDialog(string label, string server, string user)
    {
        Text = "Konto bearbeiten";
        BackColor = ColorTranslator.FromHtml("#16191E");
        ForeColor = ColorTranslator.FromHtml("#E8EAEE");
        Width = 460; Height = 320; StartPosition = FormStartPosition.CenterParent;
        var flow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(16) };
        Controls.Add(flow);
        _label = Make("Name", label);
        _server = Make("Server-URL", server);
        _user = Make("Benutzername", user);
        _pass = Make("Passwort (leer = unverändert)", "", true);
        flow.Controls.Add(new Label { Text = "Name", AutoSize = true, ForeColor = ColorTranslator.FromHtml("#8B93A1") });
        flow.Controls.Add(_label);
        flow.Controls.Add(new Label { Text = "Server-URL", AutoSize = true, ForeColor = ColorTranslator.FromHtml("#8B93A1") });
        flow.Controls.Add(_server);
        flow.Controls.Add(new Label { Text = "Benutzername", AutoSize = true, ForeColor = ColorTranslator.FromHtml("#8B93A1") });
        flow.Controls.Add(_user);
        flow.Controls.Add(new Label { Text = "Passwort (leer = unverändert)", AutoSize = true, ForeColor = ColorTranslator.FromHtml("#8B93A1") });
        flow.Controls.Add(_pass);
        var ok = new Button { Text = "Speichern & verbinden", Width = 260, Height = 34, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = ColorTranslator.FromHtml("#3D8BFD"), Margin = new Padding(0, 10, 0, 0) };
        ok.FlatAppearance.BorderSize = 0;
        ok.DialogResult = DialogResult.OK;
        flow.Controls.Add(ok);
        AcceptButton = ok;
    }

    TextBox Make(string ph, string val, bool pass = false)
    {
        var t = new TextBox { Width = 400, Text = val, BackColor = ColorTranslator.FromHtml("#1D2128"), ForeColor = ColorTranslator.FromHtml("#E8EAEE"), BorderStyle = BorderStyle.FixedSingle, Margin = new Padding(0, 2, 0, 8) };
        if (pass) t.UseSystemPasswordChar = true;
        return t;
    }
}
