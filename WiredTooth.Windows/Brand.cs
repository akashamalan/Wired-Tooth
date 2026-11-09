using System.IO;

namespace WiredTooth.Windows;

/// <summary>
/// The product identity, in one place.
///
/// The app is called Luma. The repository, the projects, the namespaces and the
/// executable stay WiredTooth -- renaming those would rewrite every file for a
/// string nobody sees, and the git history is worth more than the consistency.
/// This class is the seam: everything a user reads comes from here.
/// </summary>
public static class Brand
{
    public const string Name = "Luma";
    public const string Tagline = "Your computer's sound, on your phone.";
    public const string Version = "0.2.0";

    /// <summary>
    /// The pairing URL scheme.
    ///
    /// NOT a brand string. This is a wire contract: the iOS and Android apps
    /// already shipped registering wiredtooth://, so renaming it to luma://
    /// would break pairing for every phone already carrying the app, with no
    /// error message that would help anyone work out why. It stays, and it is
    /// hidden from the interface rather than changed.
    /// </summary>
    public const string Scheme = "wiredtooth";
}

/// <summary>
/// The handful of things that must survive a restart.
///
/// A flat key=value file under %APPDATA%\Luma, deliberately not the registry
/// and deliberately not a settings framework. There are two keys. Anything
/// heavier would be more code than the thing it stores, and a file the user can
/// delete to reset the app is a feature.
/// </summary>
public static class Prefs
{
    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), Brand.Name);

    private static readonly string File_ = Path.Combine(Dir, "prefs.ini");

    private static Dictionary<string, string> Load()
    {
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            if (!File.Exists(File_)) return map;
            foreach (string line in File.ReadAllLines(File_))
            {
                int eq = line.IndexOf('=');
                if (eq <= 0) continue;
                map[line[..eq].Trim()] = line[(eq + 1)..].Trim();
            }
        }
        catch (IOException)
        {
            // A corrupt or locked prefs file must never stop the app starting.
            // Defaults are perfectly usable.
        }
        return map;
    }

    private static void Save(Dictionary<string, string> map)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllLines(File_, map.Select(kv => $"{kv.Key}={kv.Value}"));
        }
        catch (IOException)
        {
            // Losing a preference is not worth an error dialog.
        }
    }

    public static bool Get(string key, bool fallback)
        => Load().TryGetValue(key, out string? v) && bool.TryParse(v, out bool b) ? b : fallback;

    public static void Set(string key, bool value)
    {
        var map = Load();
        map[key] = value ? "true" : "false";
        Save(map);
    }

    /// <summary>
    /// Silence this PC's speakers while a phone is listening. On by default:
    /// hearing the same audio twice, a few tens of milliseconds apart, is the
    /// first thing every new user runs into.
    /// </summary>
    public static bool SilenceWhileStreaming
    {
        get => Prefs.Get("silence_while_streaming", true);
        set => Prefs.Set("silence_while_streaming", value);
    }

    /// <summary>True until the welcome flow has been completed once.</summary>
    public static bool ShowWelcome
    {
        get => Prefs.Get("show_welcome", true);
        set => Prefs.Set("show_welcome", value);
    }
}
