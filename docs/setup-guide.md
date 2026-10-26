# Setup guide: Spotify on a Sony Ericsson phone with Lyrion and LegacyLyrion A200

This guide sets up three things:

1. **[Lyrion Music Server](https://lyrion.org)** on a computer at home. It's the free, open-source successor to Logitech Media Server / Squeezebox Server.
2. **[Spotty](https://github.com/michaelherger/Spotty-Plugin)**, the plugin that brings Spotify into Lyrion, and optionally **[YouTube Music for LMS](https://github.com/schmij97/lms-ytmusic)**.
3. **LegacyLyrion A200** on a Sony Ericsson Aino, W995, C905, W705 or another A200-platform phone, which becomes a Spotify-style remote and a player in its own right.

**Contents:** [What you need](#what-you-need) · [1. Lyrion](#1-install-lyrion-music-server) · [2. Spotty](#2-add-spotify-with-the-spotty-plugin) · [YouTube Music](#optional-youtube-music) · [3. MP3](#3-make-sure-lyrion-can-stream-mp3) · [4. Install the app](#4-install-the-app-on-the-phone) · [5. First start](#5-first-start) · [6. Play on the phone](#6-play-on-the-phone-itself) · [Docked mode](#docked-mode-aino) · [Controls](#controls) · [Settings](#settings) · [Troubleshooting](#troubleshooting)

---

## What you need

- A computer that stays on while you listen, on the same network as the phone: Linux (a Raspberry Pi, NAS or home server), a Mac, or a Windows 10/11 PC.

  > **Linux or a Mac is the smoother choice.** Lyrion for Windows works, but it can get confused on PCs with more than one network adapter: Wi-Fi and Ethernet at the same time, a VPN, or the virtual adapters that VirtualBox, Hyper-V, WSL or Docker install. It may then listen on the wrong one, and phones can't connect, or connect and stay silent. If you use Windows anyway, see [Windows: several network adapters](#windows-several-network-adapters).

- A Spotify **Premium** account (Spotty doesn't work with free accounts).
- A Sony Ericsson A200-platform phone with **Wi-Fi** on the same network as the server. See [Supported phones](../README.md#supported-phones).
- A way to copy files to the phone: a USB cable (mass storage mode), a memory card reader, or Bluetooth.

---

## 1. Install Lyrion Music Server

Downloads are on **https://lyrion.org** (*Getting started*). Version 9 is current.

### Linux

Download the package for your system from the same page (`.deb` for Debian, Ubuntu and Raspberry Pi OS, `.rpm` for Fedora and openSUSE) and install it with your package manager, for example `sudo apt install ./lyrionmusicserver_9.x.x_amd64.deb`. Lyrion starts as a system service. Allow TCP 9000 and TCP/UDP 3483 if you run a firewall. On a Raspberry Pi, piCorePlayer can also run Lyrion for you.

### Windows

1. Download **Windows 64-bit** (`LyrionMusicServer-9.x.x-win64.exe`).
2. Run it and click through the installer. If you had an old 32-bit Logitech Media Server, the installer removes it.
3. Lyrion runs as a Windows service and starts with Windows. There's no tray icon; that's normal.
4. When Windows Firewall asks, **allow access on private networks**. The phone needs TCP 9000 (web and control) and TCP 3483 (players).

### macOS

1. Download **Apple macOS** (`LyrionMusicServer-9.x.x.dmg`).
2. Open the disk image and drag **Lyrion Music Server** into *Applications*.
3. Start it from *Applications*. A menu-bar item appears with *Start* / *Stop*.
4. If macOS asks whether Lyrion may accept incoming network connections, allow it.

### First run (all systems)

1. Open **http://localhost:9000** in a browser on that computer.
2. The setup wizard asks for your music and playlist folders. Point it at local music if you have any, or skip it if you only want Spotify.
3. You can skip the *mysqueezebox.com* account step; it isn't needed.

**Write down the computer's IP address** (Windows: `ipconfig` in a terminal, "IPv4 Address"; Mac: *System Settings → Wi-Fi → Details*; Linux: `hostname -I`). The phone app has no network discovery, so you type this address in on the phone. A fixed address (a DHCP reservation in your router) saves you from typing it again later.

---

## 2. Add Spotify with the Spotty plugin

1. In the Lyrion web page, go to **Settings → Manage Plugins**.
2. Find **Spotty** in the list of third-party plugins, tick it, click **Apply**, and let Lyrion restart.
3. Go to **Settings → Advanced** and pick **Spotty** from the drop-down at the top.
4. Authorise your Spotify account:
   - Open the Spotify app on a phone or computer on the same network, and start playing anything.
   - Open the list of devices ("Connect to a device") and choose the device Spotty offers. It's named after your Lyrion server.
   - The Spotty settings page picks up the account by itself after a few seconds.
   - **If no device shows up** (this happens on Windows in particular), use the web/browser authorisation option on the same Spotty settings page instead.
5. Check that it works: in the Lyrion web page, *My Apps → Spotty* should show your playlists.

Heads-up: Spotty is an unofficial plugin, and Spotify changes its API now and then, which sometimes breaks the sign-in step until the plugin is updated. If authorisation fails, update Spotty under *Manage Plugins* and check the Spotty thread on **forums.lyrion.org**.

### Optional: YouTube Music

YouTube Music comes from a separate, unofficial plugin, [YouTube Music for LMS](https://github.com/schmij97/lms-ytmusic). It needs no YouTube account, but it does need **Python 3** on the server computer, because it fetches the audio with the [yt-dlp](https://github.com/yt-dlp/yt-dlp) tool.

1. Install Python 3:
   - **Windows:** use the installer from python.org. Tick **Add Python to PATH**, choose **Customize installation**, and install it **for all users** (into `C:\Program Files`). Lyrion runs as a Windows service and only finds Python installed that way.
   - **macOS:** use the installer from python.org.
   - **Linux** (Debian, Ubuntu, Raspberry Pi OS): `sudo apt install python3 ffmpeg libio-socket-ssl-perl libnet-ssleay-perl`. Don't install yt-dlp from apt (that version is years old); the plugin downloads a current one in step 4.
2. In the Lyrion web page, go to **Settings → Manage Plugins**, scroll to **Additional Repositories** at the bottom, paste `https://raw.githubusercontent.com/schmij97/lms-ytmusic/main/repo.xml` and click **Apply**.
3. Refresh the page, tick **YouTube Music** in the new "YouTube Music for LMS" section, click **Apply**, and let Lyrion restart.
4. Go to **Settings → Advanced → YouTube Music** and click **Download yt-dlp**. If the page says ffmpeg is missing, use its download button for that too.
5. Check that it works: *My Apps → YouTube Music* in the Lyrion web page should show Featured, New Releases and Charts.

YouTube changes its systems often. When songs stop playing, click **Update yt-dlp** on the same settings page, and update the plugin under *Manage Plugins*. Like Spotty, the plugin is unofficial and works around YouTube's own apps, which YouTube's terms don't allow; use it at your own risk.

---

## 3. Make sure Lyrion can stream MP3

When the phone plays music itself, it asks Lyrion for MP3, the one format every one of these phones decodes, and Lyrion converts Spotify, YouTube Music and your own files on the fly. The Windows and Mac versions include the converter, so normally there's nothing to do.

If the phone shows the song but stays silent, go to **Settings → Advanced → File Types** and make sure the conversions that end in **MP3** aren't disabled. On Linux servers, install the `lame` package.

You can skip this step if you only want to use the phone as a remote.

---

## 4. Install the app on the phone

1. Download from the **[latest release](https://github.com/h33ted/LegacyLyrion-SE-A200/releases/latest)** on a computer (these phones can't open GitHub; its encryption is too new for them):
   - **Aino:** `LegacyLyrion-Aino.jar` and `LegacyLyrion-Aino.jad`
   - **W995, C905, W705 and the other 240x320 phones:** `LegacyLyrion-A200.jar` and `LegacyLyrion-A200.jad`
2. Copy **both** files into the same folder on the phone or its memory card, for example `Other` or `Games`. USB in *mass storage* (file transfer) mode, a card reader, or Bluetooth all work.
3. On the phone, open the file manager, find the `.jad` and open it. Confirm the installation. The phone warns that the app is not signed; that is expected.
4. LegacyLyrion appears under *Applications* (or *Games*, depending on where you saved it).

**Optional: preset the server address.** Open the `.jad` in a text editor before copying it and add a line such as

```
LL-Server: 192.168.1.20:9000
```

with your server's address. The app then starts with that address filled in.

### Allow network access

Because the app is unsigned, the phone asks before every network connection unless you allow them permanently:

1. Go to **Applications**, highlight **LegacyLyrion** (don't open it), and press **More → Permissions** (on some firmware: *More → Info → Permissions*, or *Settings → Security*).
2. Set **Internet access** (sometimes *Network access* or *Connect to network*) to **Always allow** or **Ask first time**.
3. If the phone offers a choice of connection when the app starts, pick your **Wi-Fi** network and tick *Remember* (or *Save as default*), so it never falls back to mobile data.

The wording differs between firmware versions; the setting is always on the app's own permission page.

---

## 5. First start

1. Start **LegacyLyrion**. The settings screen opens on the first start.
2. Enter the server's address in **Server IP** (for example `192.168.1.20`) and leave **Port** at `9000`. Press **Save**.
3. The app checks which music plugins your server has and shows only those on the home rail at the left; your library is always there:
   - none: it starts in the library;
   - only Spotty or only YouTube Music: it starts there;
   - both: all three sources, with Spotify first. A message such as *Found Spotify and YouTube Music* confirms it.
4. Pick **Players** to choose which Lyrion player to control, or turn on [Play on this phone](#6-play-on-the-phone-itself).

---

## 6. Play on the phone itself

In **Options → Settings**, tick **Play on this phone**. The phone becomes a Squeezebox player over SlimProto (TCP 3483), named after the model, for example **Sony Ericsson - U10i**. It shows up in Lyrion's web page and in every other Lyrion app, and is controlled like any other player. The music plays through the phone's speaker or headphones.

### Playback modes

Also in Settings, under **Phone playback**:

- **Chunked (most compatible, default):** the app downloads the MP3 itself, cuts it at frame boundaries into pieces of about 256 KB and plays them one after another, with the next one prefetched. There are very short gaps between pieces.
- **Direct stream (gapless if supported):** hands the live stream to the phone's player in one go. Gapless on firmware that supports endless streams; on others, try Chunked.

**Tip:** in the Lyrion web page, under *Settings → Player*, pick the phone and set **Audio → Bitrate Limiting** to **128 kbps**. Smaller downloads, less memory, steadier playback on 2009-era Wi-Fi.

---

## Docked mode (Aino)

Put the Aino on its charger lying sideways, for example in its desk stand, and leave it for a moment (2 seconds by default). The app asks **Docked?**:

- **Yes, keep the screen on:** docks now, and docks again without asking until you unplug the charger.
- **Not now:** doesn't ask again until you unplug the charger.
- **Always when docked:** docks straight away from now on (change it back in [Settings](#settings)).

Docked, the screen stays on and shows a landscape Now Playing: large artwork, a big clock with the date, the track, and play/pause and skip buttons. The keys work as on Now Playing (left/right skip, up/down volume, centre play/pause), and *More* has Choose player, Shuffle and Repeat.

Docked mode ends at once when you unplug the charger, after the same wait when you stand the phone upright, or with **Undock** (right soft key).

You can also dock by hand from **More → Docked mode** on any list or on Now Playing; that lasts until you press *Undock*. The screen only stays on while LegacyLyrion is in front: after *Hide*, the phone sleeps as usual.

Docked mode needs the Aino to report its charger to Java apps. The [Diagnostics log](#diagnostics-log) lists the sensors the phone offers (a line starting `Sensors:`); without `charger_state`, use *More → Docked mode* instead.

## Controls

| | Keypad | Touch (Aino) |
|---|---|---|
| Move | D-pad up/down or 2/8; left/right or 4/6 to page | Drag the list |
| Open / play | Centre key or 5 | Tap a row |
| Options (*Play now*, *Play next*, *Add to queue*, *Now playing*) | Left soft key (**More**) | Tap **More** |
| Back | Right soft key or **C** | Tap **Back** or the arrow at top left |
| Switch source (Spotify, YouTube Music, library) | Press left to reach the rail, up/down to switch, right or centre to return | Tap an icon on the rail |
| Now Playing | *More → Now playing*, or **Resume** on the home menu | Tap the strip above the soft keys |
| Hide (music keeps playing) | Left soft key on the home menu | Tap **Hide** |
| Search | With **Search** focused on the home menu, just type: multi-tap (2 = a b c), 0 for space, hold a key for its digit, # switches letters/digits, **C** deletes, centre searches | Tap the box for the phone's keyboard |
| Diagnostics log | Press **\*** twice on the home menu | *More → Diagnostics* |
| On Now Playing | Left/right skip, up/down volume, centre play/pause | Tap the buttons and the volume bar |

Each source's home menu has **Resume**, **Search**, its own menus with artwork previews, **Players** and **Options**:

- **Spotify:** Made for you, Playlists, Browse.
- **YouTube Music:** Featured, New releases, Charts, Moods and genres, Podcasts.
- **Library:** Recently added, Albums, Artists, Genres, Playlists.

## Settings

*Options* on the home menu, or *More → Settings*:

| Setting | What it does |
|---|---|
| Server IP, Port | Your Lyrion server, usually port `9000` |
| Play on this phone | Makes the phone a Lyrion player ([details](#6-play-on-the-phone-itself)) |
| Phone playback | [Chunked or Direct stream](#playback-modes) |
| Docked (Aino) | [*Ask*, *Always* or *Off*](#docked-mode-aino) when the phone is charging and lying sideways |
| Wait before docking (Aino) | How long it has to charge sideways before docked mode starts, in seconds (default `2`) |
| Screen rotation | *Turn with the phone*, *Turn with the phone (other way)* for phones whose sensor is mounted the other way round, or *Always upright* |
| Diagnostics | The [log](#diagnostics-log) |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Can't connect to the server | Same Wi-Fi as the server? Check the IP address and port `9000`, and that Lyrion is allowed through the server's firewall on private networks. Guest networks often block devices from seeing each other. |
| The phone asks before every request | [Allow network access](#allow-network-access) permanently. |
| The phone uses mobile data | Set your Wi-Fi as the default connection for the app ([Allow network access](#allow-network-access), step 3). |
| "Spotty is not installed or not signed in" | Check *My Apps → Spotty* in the Lyrion web page. If it's empty, re-authorise in *Settings → Advanced → Spotty*. |
| "YouTube Music plugin is not installed" | See [Optional: YouTube Music](#optional-youtube-music). |
| YouTube Music songs show but don't play | *Settings → Advanced → YouTube Music*: click **Update yt-dlp**, and check that Python is found. |
| Song shows but no sound on the phone | [Allow conversion to MP3](#3-make-sure-lyrion-can-stream-mp3), check the phone's media volume, and try the other [playback mode](#playback-modes). |
| Playback stutters | Set *Bitrate Limiting* to 128 kbps for the phone's player ([tip](#playback-modes)), and keep the phone close to the router. |
| "Response empty" errors | Fixed in 0.6.1, where every request opens its own connection. Update the app. |
| The installer refuses the file | Copy the `.jad` and `.jar` into the same folder and open the `.jad`, not the `.jar`. Make sure you took the files from a release, not a source download. |
| Docked mode never asks (Aino) | Check that *Settings → Docked* isn't *Off*, then look for `charger_state` on the `Sensors:` line in the [Diagnostics log](#diagnostics-log). If it's missing, use *More → Docked mode*. |
| The screen still turns off when docked | Some firmware ignores Java's backlight requests; the Diagnostics log says `Dock: keep-awake not supported` then. Raise the phone's own backlight timeout instead. |
| The screen turns the wrong way | *Settings → Screen rotation → Turn with the phone (other way)*, or *Always upright*. |
| Windows server: phones can't connect or stay silent | See below. |

### Windows: several network adapters

If the server runs on Windows and phones can't find it, can't connect, or show the song but play nothing, Lyrion has most likely picked the wrong network adapter. This happens on PCs with Wi-Fi and Ethernet both connected, a VPN, or virtual adapters from VirtualBox, Hyper-V, WSL or Docker.

1. Open **Control Panel → Network and Internet → Network Connections** and disable the adapters you don't need, or at least the VPN and virtual ones. Keep only the one on your home network.
2. If you need the others, make your home network Windows' first choice instead: right-click it → **Properties → Internet Protocol Version 4 → Properties → Advanced**, untick **Automatic metric** and enter a low number such as `10`.
3. Restart Lyrion (Services → *Lyrion Music Server* → Restart, or restart the PC), then restart the app on the phone.
4. In the app's Settings, enter the IP address of the adapter you kept (`ipconfig`, "IPv4 Address").

### Diagnostics log

Press **\*** twice on the home menu, or open *More → Diagnostics*. The log shows the app version, the player name and ID, which plugins were found, and every failed request together with the command that was sent, plus every SlimProto command and stream URL when the phone plays. Errors also appear as a full-screen message that points to it.

When you [report an issue](https://github.com/h33ted/LegacyLyrion-SE-A200/issues), include the phone model, firmware version, and the lines from this log around the error. **Remove your server's IP address** before posting if you'd rather not share it.

---

Source, issues and releases: **https://github.com/h33ted/LegacyLyrion-SE-A200**
