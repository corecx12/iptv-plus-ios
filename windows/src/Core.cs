using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace IPTVPlus;

public static class Http
{
    public const string UserAgent = "VLC/3.0.20 LibVLC/3.0.20";
    public static readonly HttpClient Client = CreateClient();

    static HttpClient CreateClient()
    {
        var h = new HttpClientHandler { AllowAutoRedirect = true, AutomaticDecompression = System.Net.DecompressionMethods.All };
        var c = new HttpClient(h) { Timeout = TimeSpan.FromSeconds(90) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        return c;
    }
}

public class ApiException : Exception { public ApiException(string m) : base(m) { } }

public record Account(string Server, string Username, string Password)
{
    public static string Normalize(string input)
    {
        var x = (input ?? "").Trim().TrimEnd('/');
        if (x.Length > 0 && !x.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !x.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            x = "http://" + x;
        foreach (var suf in new[] { "/player_api.php", "/get.php" })
            if (x.EndsWith(suf)) x = x[..^suf.Length];
        return x;
    }
}

public enum Kind { Live, Movie, Series }

public static class KindExt
{
    public static string Path(this Kind k) => k switch { Kind.Live => "live", Kind.Movie => "movie", _ => "series" };
    public static string CatAction(this Kind k) => k switch { Kind.Live => "get_live_categories", Kind.Movie => "get_vod_categories", _ => "get_series_categories" };
    public static string ListAction(this Kind k) => k switch { Kind.Live => "get_live_streams", Kind.Movie => "get_vod_streams", _ => "get_series" };
}

public record Category(string Id, string Name);

public class Item
{
    public string Id = "", Name = "", Icon = "", CategoryId = "", Ext = "", EpgId = "", Year = "", Rating = "";
    public int Num;

    public static string S(JsonElement o, string key)
    {
        if (!o.TryGetProperty(key, out var v)) return "";
        return v.ValueKind switch
        {
            JsonValueKind.String => v.GetString() ?? "",
            JsonValueKind.Number => v.ToString(),
            JsonValueKind.Null => "",
            _ => v.ToString()
        };
    }

    public static Item From(JsonElement o, Kind k)
    {
        var y = S(o, "year");
        var rd = S(o, "releaseDate");
        return new Item
        {
            Id = k == Kind.Series ? S(o, "series_id") : S(o, "stream_id"),
            Name = S(o, "name"),
            Icon = k == Kind.Series ? S(o, "cover") : S(o, "stream_icon"),
            CategoryId = S(o, "category_id"),
            Ext = S(o, "container_extension"),
            Num = int.TryParse(S(o, "num"), out var n) ? n : 0,
            EpgId = S(o, "epg_channel_id").ToLowerInvariant().Trim(),
            Year = y.Length > 0 ? y : (rd.Length >= 4 ? rd[..4] : ""),
            Rating = S(o, "rating")
        };
    }
}

public class XtreamApi
{
    public readonly Account Acc;
    public XtreamApi(Account a) { Acc = a; }

    static string Enc(string s) => Uri.EscapeDataString(s);

    public string ApiUrl(IDictionary<string, string> p)
    {
        var sb = new StringBuilder($"{Acc.Server}/player_api.php?username={Enc(Acc.Username)}&password={Enc(Acc.Password)}");
        foreach (var kv in p) sb.Append('&').Append(Enc(kv.Key)).Append('=').Append(Enc(kv.Value));
        return sb.ToString();
    }

    public async Task<Stream> OpenAsync(string url, int timeoutSec = 90, CancellationToken ct = default)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(timeoutSec));
        var resp = await Http.Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cts.Token);
        if (resp.StatusCode != System.Net.HttpStatusCode.OK)
        {
            int code = (int)resp.StatusCode;
            throw new ApiException(code is 513 or 511 or 401
                ? $"Login abgelehnt (HTTP {code}) – Benutzername/Passwort prüfen."
                : $"Server antwortet mit HTTP {code}");
        }
        return await resp.Content.ReadAsStreamAsync(ct);
    }

    async Task<string> TextAsync(IDictionary<string, string> p, CancellationToken ct = default)
    {
        using var s = await OpenAsync(ApiUrl(p), 90, ct);
        using var sr = new StreamReader(s, Encoding.UTF8);
        return await sr.ReadToEndAsync(ct);
    }

    public async Task<Dictionary<string, string>> UserInfoAsync(CancellationToken ct = default)
    {
        var t = await TextAsync(new Dictionary<string, string>(), ct);
        JsonDocument doc;
        try { doc = JsonDocument.Parse(t); } catch { throw new ApiException("Unerwartete Antwort vom Server"); }
        using (doc)
        {
            if (!doc.RootElement.TryGetProperty("user_info", out var u) || u.ValueKind != JsonValueKind.Object)
                throw new ApiException("Login abgelehnt – Benutzername/Passwort prüfen.");
            if (Item.S(u, "auth") != "1") throw new ApiException("Login abgelehnt – Benutzername/Passwort prüfen.");
            var d = new Dictionary<string, string>();
            foreach (var pr in u.EnumerateObject()) d[pr.Name] = Item.S(u, pr.Name);
            return d;
        }
    }

    async Task<List<JsonElement>> ArrayAsync(IDictionary<string, string> p, CancellationToken ct = default)
    {
        var t = (await TextAsync(p, ct)).TrimStart();
        var list = new List<JsonElement>();
        if (!t.StartsWith("[")) return list;
        var doc = JsonDocument.Parse(t);
        foreach (var e in doc.RootElement.EnumerateArray()) list.Add(e.Clone());
        doc.Dispose();
        return list;
    }

    public async Task<List<Category>> CategoriesAsync(Kind k, CancellationToken ct = default)
    {
        var a = await ArrayAsync(new Dictionary<string, string> { ["action"] = k.CatAction() }, ct);
        return a.Select(o => new Category(Item.S(o, "category_id"), Item.S(o, "category_name"))).ToList();
    }

    public async Task<List<Item>> ItemsAsync(Kind k, string category = null, CancellationToken ct = default)
    {
        var p = new Dictionary<string, string> { ["action"] = k.ListAction() };
        if (!string.IsNullOrEmpty(category)) p["category_id"] = category;
        var a = await ArrayAsync(p, ct);
        return a.Select(o => Item.From(o, k)).ToList();
    }

    public async Task<JsonElement> InfoAsync(string action, string key, string id, CancellationToken ct = default)
    {
        var t = (await TextAsync(new Dictionary<string, string> { ["action"] = action, [key] = id }, ct)).TrimStart();
        if (!t.StartsWith("{")) return default;
        var doc = JsonDocument.Parse(t);
        return doc.RootElement.Clone();
    }

    public string StreamUrl(Kind k, string id, string ext)
    {
        var e = string.IsNullOrEmpty(ext) ? (k == Kind.Live ? "ts" : "mp4") : ext;
        return $"{Acc.Server}/{k.Path()}/{Enc(Acc.Username)}/{Enc(Acc.Password)}/{id}.{e}";
    }

    public string XmltvUrl => $"{Acc.Server}/xmltv.php?username={Enc(Acc.Username)}&password={Enc(Acc.Password)}";
}

public static class Lang
{
    public static readonly Regex German = new(@"(^|[^a-z])(de|ger|deu)([^a-z]|$)|deutsch|germany|österreich|schweiz", RegexOptions.IgnoreCase);
    public static readonly Regex TitleDE = new(@"^\s*[\[|(]?\s*(de|ger|deu)\s*[\]|):\-]", RegexOptions.IgnoreCase);
    public static readonly Regex TitleForeign = new(@"^\s*[\[|(]?\s*(en|eng|uk|us|fr|it|es|tr|pl|nl|ar|multi)\s*[\]|):\-]", RegexOptions.IgnoreCase);
    public static readonly Regex ForeignCat = new(@"(^|[^a-z])(uk|gb|us|usa|fr|it|es|nl|be|pl|tr|pt|ar|yu|exyu|ex-yu|al|ro|gr|ru|ua|in|pk|af|se|dk|no|fi|hu|cz|sk|hr|rs|ba|bg|ca|au|br|mx|latino|arab|arabic|turkey|turk|france|italy|italia|spain|espana|poland|polska|portugal|netherlands|nederland|kurd|persian|iran|india|africa|asia)([^a-z]|$)", RegexOptions.IgnoreCase);
    static readonly Regex QPrefix = new(@"^\s*[\[|(]?\s*[a-z]{2,3}\s*[\]|):\-]\s*", RegexOptions.IgnoreCase);
    static readonly Regex NonAlnum = new(@"[^a-z0-9]+");

    public static string NormQ(string s)
    {
        var x = (s ?? "").ToLowerInvariant().Replace("ä", "ae").Replace("ö", "oe").Replace("ü", "ue").Replace("ß", "ss");
        x = QPrefix.Replace(x, "");
        return NonAlnum.Replace(x, "");
    }

    static int Lev(string a, string b)
    {
        int m = a.Length, n = b.Length;
        if (m == 0) return n;
        if (n == 0) return m;
        var prev = new int[n + 1];
        var cur = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++)
        {
            cur[0] = i;
            for (int j = 1; j <= n; j++)
                cur[j] = Math.Min(Math.Min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1));
            (prev, cur) = (cur, prev);
        }
        return prev[n];
    }

    public static bool Fuzzy(string q, string t)
    {
        int tol = q.Length >= 8 ? 2 : 1;
        for (int len = Math.Max(3, q.Length - tol); len <= q.Length + tol; len++)
        {
            if (len > t.Length) continue;
            for (int i = 0; i + len <= t.Length; i++)
                if (Lev(q, t.Substring(i, len)) <= tol) return true;
        }
        return false;
    }
}

public static class Canon
{
    static readonly Regex Prefix = new(@"^\s*(\[[^\]]*\]|\|[^|]*\||[a-z0-9]{2,8}\s*[:|]\s*)", RegexOptions.IgnoreCase);
    static readonly Regex Paren = new(@"\([^)]*\)|\[[^\]]*\]");
    static readonly Regex Tokens = new(@"\b(hd|fhd|uhd|sd|4k|8k|hevc|h\.?265|h\.?264|raw|backup|ger|deu|de|germany|deutschland|50fps|60fps|vip|plus\+?|multi|audio|fernsehen|television|\d{3,4}p)\b", RegexOptions.IgnoreCase);
    static readonly Regex NonAlnum = new(@"[^a-z0-9]+");

    static readonly Dictionary<string, string> Aliases = new()
    {
        ["ard"] = "daserste", ["erstes"] = "daserste", ["daserstes"] = "daserste",
        ["rtlii"] = "rtlzwei", ["rtl2"] = "rtlzwei",
        ["pro7"] = "prosieben", ["pro7maxx"] = "prosiebenmaxx", ["pro7fun"] = "prosiebenfun",
        ["kabel1"] = "kabeleins", ["kabel1doku"] = "kabeleinsdoku", ["kabel1classics"] = "kabeleinsclassics",
        ["ntvnachrichten"] = "ntv", ["rtlnitro"] = "nitro", ["weltn24"] = "welt", ["n24"] = "welt",
        ["eurosport"] = "eurosport1", ["disney"] = "disneychannel", ["nickelodeon"] = "nick",
        ["bayerisches"] = "br", ["bayerischesfernsehen"] = "br", ["bralpha"] = "ardalpha", ["alpha"] = "ardalpha",
        ["hse24"] = "hse", ["hse24extra"] = "hseextra", ["servustvdeutschland"] = "servustv", ["servustvdetschland"] = "servustv",
        ["servustvtschland"] = "servustv", ["radiobrementv"] = "radiobremen", ["sixxtv"] = "sixx", ["voxtv"] = "vox",
        ["tele5tv"] = "tele5", ["toggo"] = "toggoplus"
    };
    static readonly HashSet<string> Regional = new() { "", "nord", "sued", "ost", "west", "aachen", "bielefeld", "dortmund", "duesseldorf",
        "essen", "koeln", "muenster", "siegen", "wuppertal", "bonn", "duisburg", "sachsen", "sachsenanhalt", "thueringen",
        "berlin", "brandenburg", "hamburg", "niedersachsen", "schleswigholstein", "sh", "mv", "mecklenburgvorpommern", "bw",
        "rp", "badenwuerttemberg", "rheinlandpfalz", "hannover", "bayern", "franken", "schwaben", "muenchen", "oberbayern",
        "niederbayern", "unterfranken", "saarland", "hessen" };
    static readonly string[] RegionalPrefixes = { "wdr", "ndr", "mdr", "rbb", "swr", "br", "hr", "sr" };

    public static string StripPrefix(string s)
    {
        var x = s;
        for (int i = 0; i < 3; i++) { var t = Prefix.Replace(x, ""); if (t == x) return x; x = t; }
        return x;
    }

    public static string Key(string name)
    {
        var s = Paren.Replace(StripPrefix(name), " ").ToLowerInvariant()
            .Replace("ä", "ae").Replace("ö", "oe").Replace("ü", "ue").Replace("ß", "ss");
        s = Tokens.Replace(s, " ");
        var n = NonAlnum.Replace(s, "");
        if (Aliases.TryGetValue(n, out var al)) n = al;
        foreach (var p in RegionalPrefixes)
            if (n.StartsWith(p) && Regional.Contains(n[p.Length..])) return p;
        return n;
    }

    public static string Clean(string name)
    {
        var s = StripPrefix(name).Trim();
        return s.Length == 0 ? name : s;
    }
}
