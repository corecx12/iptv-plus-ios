using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Xml;

namespace IPTVPlus;

public class Prog
{
    [JsonPropertyName("s")] public long S { get; set; }
    [JsonPropertyName("e")] public long E { get; set; }
    [JsonPropertyName("t")] public string T { get; set; } = "";
    [JsonPropertyName("d")] public string D { get; set; } = "";
}

public class EpgChannel
{
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("icon")] public string Icon { get; set; } = "";
    [JsonPropertyName("sid")] public string StreamId { get; set; } = "";
    [JsonPropertyName("num")] public int Num { get; set; }
    [JsonPropertyName("progs")] public List<Prog> Progs { get; set; } = new();

    public Prog Current(long now) => Progs.FirstOrDefault(p => p.S <= now && p.E > now);
    public IEnumerable<Prog> Upcoming(long now) => Progs.Where(p => p.E > now);
}

public class EpgData
{
    [JsonPropertyName("v")] public int V { get; set; } = 2;
    [JsonPropertyName("updated")] public long Updated { get; set; }
    [JsonPropertyName("source")] public string Source { get; set; } = "";
    [JsonPropertyName("server")] public string Server { get; set; } = "";
    [JsonPropertyName("channels")] public List<EpgChannel> Channels { get; set; } = new();
    [JsonPropertyName("map")] public Dictionary<string, int> Map { get; set; } = new();

    public EpgChannel ForStream(string id) => Map.TryGetValue(id, out var i) && i >= 0 && i < Channels.Count ? Channels[i] : null;

    public string ToJson() => JsonSerializer.Serialize(this);
    public static EpgData FromJson(string s)
    {
        try { var e = JsonSerializer.Deserialize<EpgData>(s); return e != null && e.V == 2 ? e : null; }
        catch { return null; }
    }
    public static EpgData Load(string path) => File.Exists(path) ? FromJson(File.ReadAllText(path)) : null;
    public void Save(string path) { try { File.WriteAllText(path, ToJson()); } catch { } }
}

public static class XmlTv
{
    static long Time(string s)
    {
        s = (s ?? "").Trim();
        if (s.Length < 14) return 0;
        var styles = DateTimeStyles.None;
        if (DateTimeOffset.TryParseExact(s, "yyyyMMddHHmmss zzz", CultureInfo.InvariantCulture, styles, out var o1)) return o1.ToUnixTimeSeconds();
        if (DateTimeOffset.TryParseExact(s, "yyyyMMddHHmmsszzz", CultureInfo.InvariantCulture, styles, out var o2)) return o2.ToUnixTimeSeconds();
        if (DateTime.TryParseExact(s.Substring(0, 14), "yyyyMMddHHmmss", CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var d))
            return ((DateTimeOffset)d.ToUniversalTime()).ToUnixTimeSeconds();
        return 0;
    }

    public static void Parse(Stream input, long from, long to,
        Action<string, string> onChannel, Func<string, bool> wants, Action<string, Prog> onProg)
    {
        var settings = new XmlReaderSettings { DtdProcessing = DtdProcessing.Ignore, IgnoreComments = true, IgnoreWhitespace = true, CheckCharacters = false };
        try
        {
            using var r = XmlReader.Create(input, settings);
            string chId = "", progCh = "", start = "", stop = "", title = "", titleDE = "", desc = "", descDE = "";
            bool inProg = false;
            while (r.Read())
            {
                if (r.NodeType == XmlNodeType.Element)
                {
                    switch (r.Name)
                    {
                        case "channel":
                            chId = (r.GetAttribute("id") ?? "").ToLowerInvariant().Trim();
                            break;
                        case "display-name":
                            if (onChannel != null && chId.Length > 0)
                            {
                                var nm = r.ReadElementContentAsString();
                                onChannel(chId, nm.Trim());
                            }
                            break;
                        case "programme":
                            progCh = (r.GetAttribute("channel") ?? "").ToLowerInvariant().Trim();
                            inProg = wants(progCh);
                            if (inProg)
                            {
                                start = r.GetAttribute("start") ?? ""; stop = r.GetAttribute("stop") ?? "";
                                title = titleDE = desc = descDE = "";
                            }
                            break;
                        case "title":
                            if (inProg)
                            {
                                var de = (r.GetAttribute("lang") ?? "").ToLowerInvariant().StartsWith("de");
                                var v = r.ReadElementContentAsString().Trim();
                                if (de) titleDE = v; else if (title.Length == 0) title = v;
                            }
                            break;
                        case "desc":
                            if (inProg)
                            {
                                var de = (r.GetAttribute("lang") ?? "").ToLowerInvariant().StartsWith("de");
                                var v = r.ReadElementContentAsString().Trim();
                                if (de) descDE = v; else if (desc.Length == 0) desc = v;
                            }
                            break;
                    }
                }
                else if (r.NodeType == XmlNodeType.EndElement && r.Name == "programme")
                {
                    if (inProg)
                    {
                        long s = Time(start), e = Time(stop);
                        if (e == 0) e = s + 1800;
                        if (s > 0 && e >= from && s <= to)
                        {
                            var d = descDE.Length > 0 ? descDE : desc;
                            if (d.Length > 400) d = d[..400] + "…";
                            onProg(progCh, new Prog { S = s, E = e, T = titleDE.Length > 0 ? titleDE : title, D = d });
                        }
                    }
                    inProg = false;
                }
            }
        }
        catch { /* abgeschnittene Datei: behalten, was gelesen wurde */ }
    }
}

public static class EpgBuilder
{
    const string Fallback = "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz";

    class Cand
    {
        public string Name, Icon, Sid, EpgId, Key;
        public int Num;
        public bool German;
        public readonly List<Prog> Progs = new();
    }

    public static async Task<EpgData> BuildAsync(XtreamApi api, List<Category> cats, List<Item> streams, CancellationToken ct = default)
    {
        var de = new HashSet<string>(); var foreign = new HashSet<string>();
        foreach (var c in cats)
        {
            if (Lang.German.IsMatch(c.Name)) de.Add(c.Id);
            else if (Lang.ForeignCat.IsMatch(c.Name)) foreign.Add(c.Id);
        }
        var list = new List<Cand>();
        var byEpg = new Dictionary<string, Cand>(); var byKey = new Dictionary<string, Cand>(); var streamMap = new Dictionary<string, Cand>();
        var sorted = streams.Select((v, i) => (v, i)).OrderBy(x => de.Contains(x.v.CategoryId) ? 0 : 1).ThenBy(x => x.i).Select(x => x.v);
        foreach (var s in sorted)
        {
            if (foreign.Contains(s.CategoryId)) continue;
            var k = Canon.Key(s.Name);
            var german = de.Contains(s.CategoryId) || s.EpgId.EndsWith(".de");
            Cand cd = null;
            if (s.EpgId.Length > 0) byEpg.TryGetValue(s.EpgId, out cd);
            if (cd == null && k.Length > 0) byKey.TryGetValue(k, out cd);
            if (cd == null)
            {
                if (k.Length == 0) continue;
                cd = new Cand { Name = Canon.Clean(s.Name), Icon = s.Icon, Sid = s.Id, Num = s.Num, EpgId = s.EpgId, Key = k, German = german };
                list.Add(cd);
            }
            if (german) cd.German = true;
            if (cd.EpgId.Length == 0 && s.EpgId.Length > 0) cd.EpgId = s.EpgId;
            if (cd.Icon.Length == 0) cd.Icon = s.Icon;
            if (s.EpgId.Length > 0 && !byEpg.ContainsKey(s.EpgId)) byEpg[s.EpgId] = cd;
            if (k.Length > 0 && !byKey.ContainsKey(k)) byKey[k] = cd;
            streamMap[s.Id] = cd;
        }
        long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        long from = now - 3 * 3600, to = now + 44 * 3600;
        int nProv = 0, nPub = 0;

        var idMap = new Dictionary<string, Cand>();
        foreach (var c in list) if (c.EpgId.Length > 0) idMap[c.EpgId] = c;
        if (idMap.Count > 0)
        {
            try
            {
                using var s = await DownloadAsync(api.XmltvUrl, ct);
                XmlTv.Parse(s, from, to, null, id => idMap.ContainsKey(id), (id, p) => { if (idMap.TryGetValue(id, out var c)) c.Progs.Add(p); });
            }
            catch { }
            nProv = list.Count(c => c.Progs.Count > 0);
        }

        var missing = new Dictionary<string, Cand>();
        foreach (var c in list) if (c.Progs.Count == 0) missing[c.Key] = c;
        var filled = new HashSet<Cand>();
        if (missing.Count > 0)
        {
            var pubMap = new Dictionary<string, Cand>();
            try
            {
                using var s = await DownloadAsync(Fallback, ct);
                XmlTv.Parse(s, from, to,
                    (id, name) => { if (!pubMap.ContainsKey(id)) { if (missing.TryGetValue(Canon.Key(name), out var c) && !filled.Contains(c)) { pubMap[id] = c; filled.Add(c); } } },
                    id => pubMap.ContainsKey(id),
                    (id, p) => { if (pubMap.TryGetValue(id, out var c)) c.Progs.Add(p); });
            }
            catch { }
            nPub = filled.Count(c => c.Progs.Count > 0);
        }

        var chosen = list.Where(c => c.Progs.Count > 0 && (c.German || filled.Contains(c)))
            .OrderBy(c => c.Num == 0 ? int.MaxValue : c.Num).ThenBy(c => c.Name.ToLowerInvariant()).ToList();
        var outCh = new List<EpgChannel>();
        var idx = new Dictionary<Cand, int>();
        foreach (var c in chosen)
        {
            var ps = c.Progs.OrderBy(p => p.S).GroupBy(p => p.S).Select(g => g.First()).ToList();
            idx[c] = outCh.Count;
            outCh.Add(new EpgChannel { Name = c.Name, Icon = c.Icon, StreamId = c.Sid, Num = c.Num, Progs = ps });
        }
        var map = new Dictionary<string, int>();
        foreach (var kv in streamMap) if (idx.TryGetValue(kv.Value, out var i)) map[kv.Key] = i;
        if (outCh.Count == 0) throw new ApiException("Keine Programmdaten für deutsche Sender gefunden");
        string src = nPub > 0 ? $"Anbieter ({nProv}) + epgshare01 ({nPub})" : (nProv > 0 ? "Anbieter" : "epgshare01");
        return new EpgData { V = 2, Updated = now, Source = src, Server = api.Acc.Server, Channels = outCh, Map = map };
    }

    static async Task<Stream> DownloadAsync(string url, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromMinutes(5));
        var resp = await Http.Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cts.Token);
        if (resp.StatusCode != System.Net.HttpStatusCode.OK) throw new ApiException($"HTTP {(int)resp.StatusCode}");
        var raw = await resp.Content.ReadAsStreamAsync(ct);
        // .gz anhand der Endung/Signatur entpacken (AutomaticDecompression greift bei .gz-Dateien ohne passenden Header nicht)
        var buffered = new BufferedStream(raw, 1 << 16);
        int b1 = buffered.ReadByte();
        int b2 = buffered.ReadByte();
        var head = new MemoryStream();
        if (b1 >= 0) head.WriteByte((byte)b1);
        if (b2 >= 0) head.WriteByte((byte)b2);
        head.Position = 0;
        Stream combined = new ConcatStream(head, buffered);
        if (b1 == 0x1f && b2 == 0x8b)
            return new System.IO.Compression.GZipStream(combined, System.IO.Compression.CompressionMode.Decompress);
        return combined;
    }
}

/// zwei Streams nacheinander lesen (um die 2 geprüften Bytes voranzustellen)
public class ConcatStream : Stream
{
    readonly Stream _a, _b;
    bool _aDone;
    public ConcatStream(Stream a, Stream b) { _a = a; _b = b; }
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override void Flush() { }
    public override int Read(byte[] buffer, int offset, int count)
    {
        if (!_aDone)
        {
            int n = _a.Read(buffer, offset, count);
            if (n > 0) return n;
            _aDone = true;
        }
        return _b.Read(buffer, offset, count);
    }
    public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
    public override void SetLength(long v) => throw new NotSupportedException();
    public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();
    protected override void Dispose(bool disposing) { if (disposing) { _a.Dispose(); _b.Dispose(); } base.Dispose(disposing); }
}
