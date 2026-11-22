import sys, os, hashlib, urllib.request, subprocess

# Argument 1: Native Library Directory (passed from Java)
native_lib_dir = sys.argv[1] if len(sys.argv) > 1 else ""

# scriptdest is the path for a legacy wrapper if needed
scriptdest = "../youtube-dl.sh"
name = "yt-dlp"
download_name = name + ".download"
url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

# Clean up legacy and interrupted files, but retain a working yt-dlp until its
# replacement has downloaded successfully.
for path in (scriptdest, "youtube-dl", download_name):
    try:
        if os.path.exists(path): os.unlink(path)
    except:
        pass

print("Downloading '{}' to '{}'...".format(url, download_name))
try:
    # Use a real browser user-agent for download
    opener = urllib.request.build_opener()
    opener.addheaders = [('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')]
    urllib.request.install_opener(opener)
    
    urllib.request.urlretrieve(url, download_name)
    if not os.path.isfile(download_name) or os.path.getsize(download_name) == 0:
        raise IOError("Downloaded yt-dlp file is empty")
    # Verify SHA-256 against official release SHA2-256SUMS before executing the downloaded archive
    sums_url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS"
    with urllib.request.urlopen(sums_url, timeout=30) as resp:
        sums_text = resp.read().decode("utf-8", errors="replace")
    expected_sha256 = None
    for line in sums_text.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[-1].lstrip("*") == "yt-dlp":
            expected_sha256 = parts[0].lower()
            break
    if not expected_sha256 or len(expected_sha256) != 64:
        raise IOError("Unable to parse official SHA-256 checksum for yt-dlp")
    h = hashlib.sha256()
    with open(download_name, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    actual_sha256 = h.hexdigest().lower()
    if actual_sha256 != expected_sha256:
        raise IOError("yt-dlp SHA-256 verification failed: expected {}, got {}".format(expected_sha256, actual_sha256))
    subprocess.run(
        [os.path.join(native_lib_dir, "libytdl.so"), os.path.abspath(download_name), "--ignore-config", "--version"],
        check=True,
        timeout=30,
    )
    os.replace(download_name, name)
    print("Download successful.")
except Exception as e:
    try:
        if os.path.exists(download_name): os.unlink(download_name)
    except:
        pass
    print("Download failed: " + str(e))
    sys.exit(1)

# Ensure executable bit is NOT set on the data directory script 
# to avoid SELinux denials on Android 10+. 
# We run it via the native libytdl.so -> libpython.so bridge instead.
try:
    os.chmod(name, 0o600)
except:
    pass

# Create a .sh wrapper as a reference
try:
    with open(scriptdest, "w") as f:
        ytdl_dir = os.getcwd()
        cacert = os.path.abspath(os.path.join(ytdl_dir, "../cacert.pem"))

        f.write("#!/system/bin/sh\n")
        f.write("NATIVE_LIB_DIR=\"{}\"\n".format(native_lib_dir))
        f.write("export PYTHONHOME=\"{}\"\n".format(ytdl_dir))
        f.write("export PYTHONPATH=\"{}/python313.zip\"\n".format(ytdl_dir))
        f.write("export SSL_CERT_FILE=\"{}\"\n".format(cacert))
        f.write("export LD_LIBRARY_PATH=\"$NATIVE_LIB_DIR\"\n")
        f.write("exec \"$NATIVE_LIB_DIR/libytdl.so\" \"$@\"\n")

    os.chmod(scriptdest, 0o700)
    print("Created reference wrapper at {}".format(scriptdest))
except:
    print("Warning: Could not create wrapper")
