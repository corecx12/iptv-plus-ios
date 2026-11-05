using System.Text.Json;
using System.Text.Json.Serialization;

namespace IPTVPlus;

public class SavedAccount
{
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    [JsonPropertyName("server")] public string Server { get; set; } = "";
    [JsonPropertyName("user")] public string User { get; set; } = "";
    [JsonPropertyName("pass")] public string Pass { get; set; } = "";
    [JsonIgnore] public Account Account => new(Server, User, Pass);
}

class Persisted
{
    [JsonPropertyName("accounts")] public List<SavedAccount> Accounts { get; set; } = new();
    [JsonPropertyName("activeIndex")] public int ActiveIndex { get; set; }
    [JsonPropertyName("deOnly")] public bool DeOnly { get; set; } = true;
}

public class Repo
{
    public static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "IPTVPlusWin");
    static string ConfigPath => Path.Combine(DataDir, "config.json");
    static string EpgPath => Path.Combine(DataDir, "epg_cache.json");

    public List<SavedAccount> Accounts = new();
    public int ActiveIndex;
    public bool DeOnly = true;
    public Dictionary<string, string> UserInfo = new();
    public EpgData Epg;
    public bool EpgLoading;

    readonly Dictionary<Kind, List<Category>> _catCache = new();
    readonly Dictionary<string, List<Item>> _listCache = new();

    public Account Account => (ActiveIndex >= 0 && ActiveIndex < Accounts.Count) ? Accounts[ActiveIndex].Account : null;
    public XtreamApi Api => Account != null ? new XtreamApi(Account) : null;

    public Repo()
    {
        Directory.CreateDirectory(DataDir);
        Load();
        Epg = EpgData.Load(EpgPath);
    }

    void Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                var p = JsonSerializer.Deserialize<Persisted>(File.ReadAllText(ConfigPath));
                if (p != null) { Accounts = p.Accounts ?? new(); ActiveIndex = p.ActiveIndex; DeOnly = p.DeOnly; }
            }
        }
        catch { }
        if (ActiveIndex < 0 || ActiveIndex >= Accounts.Count) ActiveIndex = Accounts.Count > 0 ? 0 : 0;
    }

    void Persist()
    {
        try
        {
            var p = new Persisted { Accounts = Accounts, ActiveIndex = ActiveIndex, DeOnly = DeOnly };
            File.WriteAllText(ConfigPath, JsonSerializer.Serialize(p));
        }
        catch { }
    }

    public void SetDeOnly(bool v) { DeOnly = v; _listCache.Clear(); Persist(); }

    void ResetCaches() { _catCache.Clear(); _listCache.Clear(); Epg = null; try { File.Delete(EpgPath); } catch { } }

    public async Task<int> SaveAccountAsync(string label, string server, string user, string pass, int index)
    {
        var existing = (index >= 0 && index < Accounts.Count) ? Accounts[index].Account : null;
        var pw = string.IsNullOrWhiteSpace(pass) ? (existing?.Password ?? "") : pass.Trim();
        var acc = new Account(Account.Normalize(server), user.Trim(), pw);
        var info = await new XtreamApi(acc).UserInfoAsync();
        var name = string.IsNullOrWhiteSpace(label) ? $"Konto {Accounts.Count + 1}" : label.Trim();
        var sa = new SavedAccount { Label = name, Server = acc.Server, User = acc.Username, Pass = acc.Password };
        bool wasActive = index == ActiveIndex;
        int newIndex;
        if (index >= 0 && index < Accounts.Count) { Accounts[index] = sa; newIndex = index; }
        else { Accounts.Add(sa); newIndex = Accounts.Count - 1; }
        if (Accounts.Count == 1 || wasActive || index < 0)
        {
            ActiveIndex = newIndex; UserInfo = info; ResetCaches();
        }
        Persist();
        return newIndex;
    }

    public void SwitchTo(int index)
    {
        if (index < 0 || index >= Accounts.Count || index == ActiveIndex) return;
        ActiveIndex = index; UserInfo = new(); ResetCaches(); Persist();
    }

    public void RemoveAccount(int index)
    {
        if (index < 0 || index >= Accounts.Count) return;
        Accounts.RemoveAt(index);
        if (ActiveIndex >= Accounts.Count) ActiveIndex = Math.Max(0, Accounts.Count - 1);
        UserInfo = new(); ResetCaches(); Persist();
    }

    public async Task RefreshUserInfoAsync()
    {
        try { if (Api != null) UserInfo = await Api.UserInfoAsync(); } catch { }
    }

    public void Logout() { Accounts.Clear(); ActiveIndex = 0; UserInfo = new(); ResetCaches(); Persist(); }

    public bool FilterDE(Kind k) => DeOnly && (k == Kind.Movie || k == Kind.Series);

    HashSet<string> GermanCatIds(Kind k) =>
        (_catCache.TryGetValue(k, out var c) ? c : new()).Where(x => Lang.German.IsMatch(x.Name)).Select(x => x.Id).ToHashSet();

    public bool IsGerman(Item it, Kind k)
    {
        if (Lang.TitleForeign.IsMatch(it.Name)) return false;
        if (Lang.TitleDE.IsMatch(it.Name)) return true;
        var ids = GermanCatIds(k);
        return ids.Count == 0 || ids.Contains(it.CategoryId);
    }

    public async Task<List<Category>> RawCategoriesAsync(Kind k)
    {
        if (!_catCache.TryGetValue(k, out var c)) { c = await Api.CategoriesAsync(k); _catCache[k] = c; }
        return c;
    }

    public async Task<List<Category>> CategoriesAsync(Kind k)
    {
        var all = await RawCategoriesAsync(k);
        if (!FilterDE(k)) return all;
        var de = all.Where(x => Lang.German.IsMatch(x.Name)).ToList();
        return de.Count > 0 ? de : all;
    }

    public async Task<List<Item>> AllItemsAsync(Kind k)
    {
        if (!_catCache.ContainsKey(k)) { try { _catCache[k] = await Api.CategoriesAsync(k); } catch { } }
        var key = k.Path() + ":";
        if (!_listCache.TryGetValue(key, out var l)) { l = await Api.ItemsAsync(k); _listCache[key] = l; }
        return l;
    }

    public async Task<List<Item>> ItemsAsync(Kind k, string cat)
    {
        if (string.IsNullOrEmpty(cat))
        {
            var all = await AllItemsAsync(k);
            return FilterDE(k) ? all.Where(i => IsGerman(i, k)).ToList() : all;
        }
        var key = k.Path() + ":" + cat;
        if (!_listCache.TryGetValue(key, out var l)) { l = await Api.ItemsAsync(k, cat); _listCache[key] = l; }
        return l;
    }

    public async Task<List<Item>> SearchAsync(Kind k, string query)
    {
        var q = Lang.NormQ(query);
        if (q.Length == 0) return new();
        var all = await AllItemsAsync(k);
        if (FilterDE(k)) all = all.Where(i => IsGerman(i, k)).ToList();
        var hits = all.Where(i => Lang.NormQ(i.Name).Contains(q)).ToList();
        if (hits.Count > 0 || q.Length < 4) return hits;
        return all.Where(i => Lang.Fuzzy(q, Lang.NormQ(i.Name))).Take(300).ToList();
    }

    public string StreamUrl(Kind k, Item it) => Api?.StreamUrl(k, it.Id, k == Kind.Live ? "ts" : it.Ext);
    public EpgChannel EpgFor(Item it) => Epg?.ForStream(it.Id);

    public async Task<string> LoadEpgAsync(bool force)
    {
        var a = Api;
        if (a == null) return null;
        if (!force && Epg != null && Epg.Server == a.Acc.Server && DateTimeOffset.UtcNow.ToUnixTimeSeconds() - Epg.Updated < 6 * 3600) return null;
        if (EpgLoading) return null;
        EpgLoading = true;
        try
        {
            var cats = await RawCategoriesAsync(Kind.Live);
            var streams = await AllItemsAsync(Kind.Live);
            var data = await EpgBuilder.BuildAsync(a, cats, streams);
            Epg = data;
            data.Save(EpgPath);
            return null;
        }
        catch (Exception e) { return e.Message; }
        finally { EpgLoading = false; }
    }
}
