using System.Diagnostics;
using System.Reflection;
using System.Text.Json;

namespace IPTVPlus;

public static class Updater
{
    const string ReleasesApi = "https://api.github.com/repos/corecx12/iptv-plus-ios/releases?per_page=30";

    public record Release(int Number, string Name, string Url);

    public static int CurrentBuild()
    {
        // Version wird im Build als 1.0.<RUN> gesetzt
        var v = Assembly.GetExecutingAssembly().GetName().Version;
        return v?.Build ?? 0;
    }

    public static string VersionName()
    {
        var v = Assembly.GetExecutingAssembly().GetName().Version;
        return v == null ? "1.0" : $"{v.Major}.{v.Minor}.{v.Build}";
    }

    public static async Task<Release> LatestAsync()
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, ReleasesApi);
        req.Headers.Add("Accept", "application/vnd.github+json");
        req.Headers.UserAgent.ParseAdd("IPTVPlus-Windows");
        var resp = await Http.Client.SendAsync(req);
        if (!resp.IsSuccessStatusCode) throw new ApiException($"Update-Server antwortet mit HTTP {(int)resp.StatusCode}");
        using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        Release best = null;
        foreach (var r in doc.RootElement.EnumerateArray())
        {
            var tag = r.TryGetProperty("tag_name", out var t) ? t.GetString() ?? "" : "";
            if (!tag.StartsWith("win-")) continue;
            if (r.TryGetProperty("draft", out var dr) && dr.GetBoolean()) continue;
            if (!int.TryParse(tag["win-".Length..], out var n)) continue;
            if (!r.TryGetProperty("assets", out var assets)) continue;
            foreach (var a in assets.EnumerateArray())
            {
                var name = a.TryGetProperty("name", out var nm) ? nm.GetString() ?? "" : "";
                if (name.EndsWith(".exe") && (best == null || n > best.Number))
                    best = new Release(n, r.TryGetProperty("name", out var rn) ? rn.GetString() ?? tag : tag,
                        a.GetProperty("browser_download_url").GetString() ?? "");
            }
        }
        return best;
    }

    public static async Task<string> DownloadAsync(Release r, IProgress<int> progress, CancellationToken ct = default)
    {
        var dir = Path.Combine(Path.GetTempPath(), "iptvplus-update");
        Directory.CreateDirectory(dir);
        var outPath = Path.Combine(dir, $"IPTV+_{r.Number}.exe");
        using var resp = await Http.Client.GetAsync(r.Url, HttpCompletionOption.ResponseHeadersRead, ct);
        if (!resp.IsSuccessStatusCode) throw new ApiException($"Download fehlgeschlagen (HTTP {(int)resp.StatusCode})");
        var total = resp.Content.Headers.ContentLength ?? 0;
        await using var src = await resp.Content.ReadAsStreamAsync(ct);
        await using var dst = File.Create(outPath);
        var buf = new byte[256 * 1024];
        long done = 0; int last = -1; int n;
        while ((n = await src.ReadAsync(buf, ct)) > 0)
        {
            await dst.WriteAsync(buf.AsMemory(0, n), ct);
            done += n;
            if (total > 0) { int pct = (int)(done * 100 / total); if (pct != last) { last = pct; progress?.Report(pct); } }
        }
        return outPath;
    }

    /// neue EXE an die Stelle der laufenden kopieren und neu starten
    public static void ApplyAndRestart(string newExe)
    {
        var current = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule!.FileName;
        var bat = Path.Combine(Path.GetTempPath(), "iptvplus-update", "apply.cmd");
        var script = $@"@echo off
setlocal
set ""target={current}""
set ""source={newExe}""
:wait
timeout /t 1 /nobreak >nul
copy /y ""%source%"" ""%target%"" >nul 2>&1
if errorlevel 1 goto wait
start """" ""%target%""
del ""%source%"" >nul 2>&1
";
        File.WriteAllText(bat, script);
        Process.Start(new ProcessStartInfo { FileName = "cmd.exe", Arguments = $"/c \"{bat}\"", CreateNoWindow = true, UseShellExecute = false });
        Application.Exit();
    }
}
